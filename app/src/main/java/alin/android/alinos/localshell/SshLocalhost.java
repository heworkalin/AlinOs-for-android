package alin.android.alinos.localshell;

import android.content.Context;
import android.util.Log;

import alin.android.alinos.bean.SshConfigBean;
import alin.android.alinos.db.SshDbHelper;
import alin.android.alinos.tools.ToolMeta;
import alin.android.alinos.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * SSH localhost 工具集。
 * 提供两个能力：列出配置（安全脱敏）、通过 UUID 连接并创建终端会话，支持自定义会话ID。
 * <p>
 * AI 可见字段：uuid、name、description（密码/密钥/主机地址全部内部隐藏）。
 * 连接流程：临时会话验证全部交互 → 销毁临时会话 → 新建干净正式会话
 * 执行 clear && exec ssh 重建连接、自动补输密码，得到无冗余输出终端后返回 sessionId。
 * 支持传入自定义 sessionId，ID 规范与 localshell_create_session 完全一致：
 * 仅允许字母数字下划线短线，最大64字符。
 * 所有错误返回脱敏提示，不泄露主机、端口、账号、密码、密钥等敏感数据。
 */
public class SshLocalhost {

    private static final String TAG = "SshLocalhost";
    // 和 ToolRegistry localshell_create_session 统一 ID 正则规范
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * 列出所有 SSH 配置（仅返回 AI 安全可见的字段）。
     *
     * @return JSON 数组：[{uuid, name, description}, ...]
     */
    public static JSONArray listConfigs(Context context) {
        JSONArray arr = new JSONArray();
        try {
            SshDbHelper db = new SshDbHelper(context);
            List<SshConfigBean> configs = db.getAllConfigs();
            for (SshConfigBean c : configs) {
                JSONObject item = new JSONObject();
                item.put("uuid", c.getUuid() != null ? c.getUuid() : "");
                item.put("name", c.getName() != null ? c.getName() : "");
                item.put("description", c.getDescription() != null ? c.getDescription() : "");
                arr.put(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "listConfigs 失败", e);
        }
        return arr;
    }

    /**
     * 通过 UUID 连接 SSH 配置，创建终端会话。支持传入自定义正式会话ID。
     * 完整对齐 SshTestActivity 交互逻辑，新增「销毁临时会话、重建干净正式会话」流程。
     * <p>
     * 标准完整流程：
     * 1. 查询配置 UUID，校验合法性
     * 2. 创建临时验证会话，完成 ssh 交互校验（主机密钥、yes 确认、密码验证）
     * 3. 校验全部通过 → 销毁临时验证会话
     * 4. 使用 AI 传入 customSessionId 或自动生成，新建干净正式会话，下发 clear && exec ssh 命令
     * 5. 正式会话自动补输登录密码，清除登录提示冗余输出
     * 6. 正式会话初始化完成后，返回 sessionId 给 AI，使用 localshell_* 系列工具操作
     * 7. 任意步骤出错立即销毁当前会话，返回脱敏错误信息
     *
     * @param context         上下文
     * @param uuid            SSH 配置唯一标识
     * @param customSessionId 可选，自定义正式会话 ID；格式同 localshell_create_session，为空则自动生成
     * @return 结果 JSON，成功时包含 sessionId
     */
    public static JSONObject connectByUuid(Context context, String uuid, String customSessionId) {
        SshConfigBean config = null;
        LocalShellExecutor exec = LocalShellExecutor.getInstance();
        String tempSid = null;
        String formalSid = null;
        try {
            // 步骤1：查询配置
            SshDbHelper db = new SshDbHelper(context);
            config = db.getConfigByUuid(uuid);
            if (config == null) {
                return error("参数错误：传入的 SSH 配置 UUID 不存在，请调用 ssh_list_configs 获取有效 UUID 列表");
            }

            // 校验自定义 ID 格式（AI 传入时）
            if (customSessionId != null && !customSessionId.trim().isEmpty()) {
                String inputId = customSessionId.trim();
                if (!SESSION_ID_PATTERN.matcher(inputId).matches()) {
                    return error("自定义 sessionId 格式非法：仅允许大小写字母、数字、下划线、短线，长度 1~64 字符");
                }
                // 检测会话是否已存在
                JSONObject sessionCheck = exec.session_status(inputId);
                if ("success".equals(sessionCheck.optString("status"))
                        && "alive".equals(sessionCheck.optString("state"))) {
                    return error("自定义 sessionId 已存在且会话存活，请更换其他 ID 或先销毁原有会话");
                }
                formalSid = inputId;
            }

            // 步骤2：创建【临时验证会话】，完成登录校验交互
            tempSid = "ssh_" + config.getId() + "_temp_" + System.currentTimeMillis();
            JSONObject createTempResult = exec.create_session(
                    tempSid,
                    config.getName() != null ? config.getName() : "SSH临时验证"
            );
            if (!"success".equals(createTempResult.optString("status"))) {
                return error("会话创建失败：无法初始化临时终端 PTY 环境，请检查本地 Termux 环境是否正常");
            }

            // 构建 SSH 连接指令，关闭首次密钥强校验
            String sshBaseCmd = "ssh " + config.getUsername() + "@" + config.getHost()
                    + " -p " + config.getPort()
                    + " -o StrictHostKeyChecking=no -o ConnectTimeout=4";

            // 下发连接指令到临时会话
            exec.shell_write(tempSid, sshBaseCmd + "\r", "last_20", 20, true, false);
            sleep(800);

            // 读取终端输出
            JSONObject readResult = exec.shell_read(tempSid, "last_20", 20, true, false);
            String screenOutput = readResult.optString("content", "");

            // 分支1：检测主机密钥冲突/验证失败（旧密钥不匹配）
            if (screenOutput.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")
                    || screenOutput.contains("Host key verification failed")) {
                exec.shell_send_key(tempSid, "CTRL_C");
                sleep(300);
                exec.shell_exec(
                        tempSid,
                        "ssh-keygen -R \"[" + config.getHost() + "]:" + config.getPort() + "\"",
                        1000
                );
                sleep(200);
                exec.shell_write(tempSid, sshBaseCmd + "\r", "last_20", 20, true, false);
                sleep(800);
                readResult = exec.shell_read(tempSid, "last_20", 20, true, false);
                screenOutput = readResult.optString("content", "");
            }

            // 分支2：首次连接需要确认主机指纹 yes/no
            if (screenOutput.contains("continue connecting (yes/no")
                    || screenOutput.contains("fingerprint")
                    || screenOutput.contains("authenticity of host")) {
                exec.shell_write(tempSid, "yes\r", "last_20", 20, true, false);
                sleep(1500);
                readResult = exec.shell_read(tempSid, "last_20", 20, true, false);
                screenOutput = readResult.optString("content", "");
            }

            // 分支3：密码认证流程校验
            if ("password".equals(config.getAuthType())) {
                if (config.getPassword() == null || config.getPassword().isEmpty()) {
                    // 销毁临时会话
                    if (tempSid != null) {
                        exec.destroy_session(tempSid);
                    }
                    return error("配置异常：当前 SSH 配置选择密码认证，但未填写登录密码，请编辑配置补充密码");
                }
                int pwdPromptCount = countKeyword(screenOutput, "password");
                if (pwdPromptCount > 0) {
                    exec.shell_write(tempSid, config.getPassword() + "\r", "last_20", 20, true, false);
                    sleep(2500);
                    readResult = exec.shell_read(tempSid, "last_20", 20, true, false);
                    screenOutput = readResult.optString("content", "");
                    int afterPwdPrompt = countKeyword(screenOutput, "password");
                    if (afterPwdPrompt > pwdPromptCount
                            || screenOutput.contains("Permission denied")
                            || screenOutput.contains("try again")) {
                        if (tempSid != null) {
                            exec.destroy_session(tempSid);
                        }
                        return error("认证失败：SSH 登录密码验证错误，请核对配置内登录密码");
                    }
                }
            } else if ("key".equals(config.getAuthType())) {
                // 私钥认证暂未实现
                if (tempSid != null) {
                    exec.destroy_session(tempSid);
                }
                return error("功能未就绪：当前工具暂不支持私钥密钥登录，请将 SSH 配置切换为密码认证方式");
            }

            // 分支4：捕获连接拒绝/超时错误
            if (screenOutput.contains("Connection refused")) {
                if (tempSid != null) {
                    exec.destroy_session(tempSid);
                }
                return error("连接失败：目标主机对应端口未开放 SSH 服务，或服务未启动");
            }
            if (screenOutput.contains("Connection timed out") || screenOutput.contains("timed out")) {
                if (tempSid != null) {
                    exec.destroy_session(tempSid);
                }
                return error("连接失败：网络超时，无法连通目标主机，请检查网络环境");
            }

            // ====================== 校验全部通过，执行核心要求流程 ======================
            // 1. 销毁刚才用于验证的临时 session
            if (tempSid != null) {
                exec.destroy_session(tempSid);
                tempSid = null;
                sleep(200);
            }

            // 2. 新建正式干净会话：优先使用 AI 传入自定义 ID，无则自动生成
            if (formalSid == null || formalSid.isEmpty()) {
                formalSid = "ssh_" + config.getId() + "_" + System.currentTimeMillis();
            }
            JSONObject createFormalResult = exec.create_session(
                    formalSid,
                    config.getName() != null ? config.getName() : "SSH正式连接"
            );
            if (!"success".equals(createFormalResult.optString("status"))) {
                if (formalSid != null) {
                    exec.destroy_session(formalSid);
                }
                return error("正式终端会话创建失败：PTY 环境初始化异常，可能自定义 ID 已被占用");
            }

            // 3. 下发清理 + 重启 ssh 命令：clear && exec ssh xxx
            String formalSshCmd = "clear && exec " + sshBaseCmd;
            exec.shell_write(formalSid, formalSshCmd + "\r", "last_20", 20, true, false);
            sleep(2000);

            // 4. 正式会话再次自动补输一次密码，清除登录冗余提示
            JSONObject formalRead = exec.shell_read(formalSid, "last_20", 20, true, false);
            String formalOutput = formalRead.optString("content", "");
            if (formalOutput.contains("password") || formalOutput.contains("Password")) {
                exec.shell_write(formalSid, config.getPassword() + "\r", "last_20", 20, true, false);
                sleep(1500);
            }

            // 5. 全部干净终端初始化完成，返回 sessionId，AI 调用 localshell_* 工具操作
            JSONObject finalRead = exec.shell_read(formalSid, "last_20", 20, true, false);
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("sessionId", formalSid);
            result.put("message", "SSH 干净终端会话创建完成，请使用 localshell_shell_exec/localshell_shell_read 等 localshell_系列工具操作该会话");
            result.put("lastOutput", finalRead.optString("content", ""));
            return result;

        } catch (Exception e) {
            // 全局兜底异常，销毁所有残留会话
            Log.e(TAG, "connectByUuid 全局异常", e);
            if (tempSid != null) {
                try {
                    exec.destroy_session(tempSid);
                } catch (Exception ignore) {
                    // 忽略销毁异常
                }
            }
            if (formalSid != null) {
                try {
                    exec.destroy_session(formalSid);
                } catch (Exception ignore) {
                    // 忽略销毁异常
                }
            }
            return error("未知异常：SSH 连接流程执行失败，请检查 Termux 环境或 SSH 配置完整性");
        }
    }

    /**
     * 兼容旧调用：不传自定义 sessionId，自动生成。
     */
    public static JSONObject connectByUuid(Context context, String uuid) {
        return connectByUuid(context, uuid, null);
    }

    /**
     * 将两个工具注册到 ToolRegistry（由 ToolRegistry.init 调用）。
     */
    public static void registerTools(Context context) {
        // 1. 列出配置（安全脱敏）
        ToolRegistry.register("ssh_list_configs",
                "列出所有 SSH 连接配置。返回 uuid、名称和描述，不包含密码/主机等敏感信息。",
                new ToolMeta.Param[0],
                p -> {
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
                    result.put("configs", listConfigs(context));
                    return result;
                });

        // 2. 通过 UUID 连接，支持自定义 sessionId
        ToolRegistry.register("ssh_connect",
                "通过配置 UUID 建立 SSH 干净终端会话。内部流程：临时会话完成登录校验 → 销毁临时会话 → "
                        + "新建正式终端并执行 clear 清空冗余输出，自动补输密码。"
                        + "可选传入 customSessionId 自定义会话 ID，ID 规范与 localshell_create_session 一致：仅字母数字下划线短线，1~64 字符；不传则自动生成。"
                        + "成功返回可用 sessionId，后续必须使用 localshell_* 系列工具（shell_exec/shell_read/shell_send_key 等）操作该终端。"
                        + "出错会返回明确脱敏故障原因，不会泄露主机、账号、密码信息。",
                ToolMeta.params(
                        ToolMeta.param("uuid", "string", true, "", "SSH 配置的 UUID（从 ssh_list_configs 获取）"),
                        ToolMeta.param("customSessionId", "string", false, "", "自定义正式会话 ID，格式同 localshell_create_session，留空自动生成")
                ),
                p -> connectByUuid(context, p.getString("uuid"), p.optString("customSessionId", "")));
    }

    /**
     * 统一构造标准化错误返回 JSON。
     *
     * @param msg 脱敏、无敏感信息的错误描述
     * @return 固定格式错误对象
     */
    private static JSONObject error(String msg) {
        JSONObject err = new JSONObject();
        try {
            err.put("status", "error");
            err.put("message", msg);
        } catch (Exception ignored) {
            // 忽略 JSON 异常
        }
        return err;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 统计文本内指定关键词出现次数，用于判断密码重复提示。
     */
    private static int countKeyword(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }
        return count;
    }
}