package alin.android.alinos.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 环境说明工具集 —— 面向 MCP 客户端的"帮助技能"。
 *
 * AI 通过 MCP 连接本服务端后，应首先调用 {@code system_environment} 明确
 * 当前终端的能力范围（capability scope）：这是什么环境、能做什么、
 * 不支持什么、操作边界在哪。
 *
 * 内容基于 {@link alin.android.alinos.prompt.PromptService#getDefaultSystemPrompt()}
 * 与项目实际环境（LocalShellConstants / LocalShellEnvironment）改写。
 */
public class EnvironmentToolSet {

    private EnvironmentToolSet() {}

    public static void register() {
        ToolRegistry.register("system_environment",
                "获取当前 MCP 服务端终端的能力范围说明（首次连接必读）："
                + "这是什么环境、支持哪些能力域、支持的命令集、不支持什么、文件访问边界、使用规范与安全注意事项。"
                + "不传参数。",
                new ToolMeta.Param[0],
                p -> buildEnvironmentInfo()
        );
    }

    /** 构建结构化能力范围说明。 */
    private static JSONObject buildEnvironmentInfo() {
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("server", "AlinOs MCP Server");
            result.put("device", "Android 手机（本地终端服务端）");

            // ---- 终端身份 ----
            JSONObject runtime = new JSONObject();
            runtime.put("type", "交互式 PTY 终端（Android 应用沙箱内，非标准 bash、非 Termux 完整发行版）");
            runtime.put("shell", "/system/bin/sh");
            runtime.put("rootfs", "预编译精简 rootfs（416 个二进制：bash/curl/ssh/scp 等）");
            runtime.put("proot", "未启用（无虚拟化，直接运行在 Android 沙箱）");
            runtime.put("root", "无（普通应用权限，非 root）");
            result.put("runtime", runtime);

            // ---- 支持的能力范围（能做什么） ----
            JSONObject capabilityScope = new JSONObject();
            capabilityScope.put("终端会话",
                    new JSONArray()
                            .put("创建/销毁/列出/搜索/重命名多个独立 PTY 会话（localshell_*_session）")
                            .put("会话存活状态查询"));
            capabilityScope.put("命令执行",
                    new JSONArray()
                            .put("执行任意 shell 命令并等待输出（shell_exec）")
                            .put("向会话写入文本/按键，操作交互式程序、菜单、对话框（shell_write / shell_send_key）")
                            .put("读取终端画面快照轮询进度（shell_read），读取完整历史输出（read_history_canvas）"));
            capabilityScope.put("网络",
                    new JSONArray()
                            .put("curl 下载文件、调用 HTTP API")
                            .put("ssh/scp/sftp 连接并操作远程服务器（ssh_connect / ssh_list_configs）"));
            capabilityScope.put("文件系统",
                    new JSONArray()
                            .put("读写 Android 共享存储 /storage/emulated/0（全盘可访问）")
                            .put("读写应用私有目录与 HOME（bash 的 rm/cp/mv/mkdir/tar/gzip 等）"));
            capabilityScope.put("进程管理",
                    new JSONArray()
                            .put("ps/top 查看进程，kill 结束进程"));
            result.put("capability_scope", capabilityScope);

            // ---- 支持的命令集 ----
            result.put("supported_commands", new JSONArray()
                    .put("bash").put("curl").put("ssh").put("scp").put("sftp")
                    .put("ssh-keygen").put("ls").put("cat").put("cp").put("mv")
                    .put("rm").put("mkdir").put("grep").put("find").put("tar")
                    .put("gzip").put("ps").put("kill").put("ping").put("top")
                    .put("mount").put("date").put("echo"));

            // ---- 不支持的范围（不能做什么，能力边界） ----
            JSONObject notSupported = new JSONObject();
            notSupported.put("包管理器", "无 apt/dpkg/yum，不能安装/卸载软件包");
            notSupported.put("开发工具链", "无 git / python / vim / tmux / screen");
            notSupported.put("系统权限", "非 root：/system、/vendor 等系统分区只读，不能改系统设置、不能提权");
            notSupported.put("虚拟化", "未启用 proot，不能在终端内运行完整 Linux 发行版（apt update / systemd 等不可用）");
            result.put("not_supported", notSupported);

            // ---- 文件访问边界 ----
            JSONObject fileAccess = new JSONObject();
            fileAccess.put("可读写",
                    new JSONArray()
                            .put("/storage/emulated/0（共享存储，全盘）")
                            .put("应用私有数据目录")
                            .put("HOME（/data/user/0/alin.android.alinos/files/home）"));
            fileAccess.put("只读或受限",
                    new JSONArray()
                            .put("/system、/vendor 等系统分区（只读）")
                            .put("其他应用的私有数据目录（Android 沙箱隔离，不可访问）"));
            result.put("file_access", fileAccess);

            // ---- 使用规范 ----
            result.put("guidelines", new JSONArray()
                    .put("默认会话 ID 为 \"default\"，除非明确创建了其他会话")
                    .put("这是交互式 PTY：前台有进程在跑（下载/安装/编译）时，新命令会排队或干扰前台——绝对不要用 sleep/echo 等命令等待，用 shell_read(waitMs) 轮询进度")
                    .put("下载/安装类任务首次 exec 用 waitMs=1200，之后 shell_read(waitMs=2000~3000)")
                    .put("菜单/列表导航：colorEscape=true 识别[反色]高亮行，方向键计算步数，批量按键用 '|' 分隔（如 Down|Down|Enter）")
                    .put("看到提示符 # 或 $ 才能执行下一条 shell_exec；返回 {status:'session_died'} 时用 create_session 重建")
                    .put("回复简洁专业，中文优先"));

            // ---- 安全警告 ----
            result.put("safety", new JSONArray()
                    .put("本服务端拥有真实设备文件系统与终端的执行权限，执行破坏性命令前（rm -rf、格式化、覆盖重要文件、批量 kill 等）必须三思，并优先向用户确认")
                    .put("不执行与用户任务无关的扫描、上传、敏感数据读取")
                    .put("未知能力先调用 search_tools 查询，不要臆造工具名"));
        } catch (Exception ignored) {
            // 结构化构建失败时返回纯文本
        }
        return result;
    }
}
