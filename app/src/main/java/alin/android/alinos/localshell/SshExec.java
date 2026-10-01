package alin.android.alinos.localshell;

import android.content.Context;
import android.util.Log;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import alin.android.alinos.bean.SshConfigBean;

/**
 * SSH 非 PTY 执行通道（基于 JSch）。
 *
 * <p><b>用途</b>：连接验证、一次性远程命令执行。不创建 PTY，不依赖 ssh/sshpass 进程，
 * 认证结果直接由 JSch 异常给出，语义明确。
 *
 * <p><b>与 PTY 的分工</b>：
 * <ul>
 *   <li>验证 / 一次性命令 → 本类；</li>
 *   <li>需要 TTY 的交互（登录 shell、sudo、全屏程序、跨命令保持状态）
 *       → 仍走 {@link LocalShellExecutor#create_session} 的 PTY 会话。</li>
 * </ul>
 */
public final class SshExec {

    private static final String TAG = "SshExec";

    /** 执行结果。 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(boolean ok, String message, int exitCode, String stdout, String stderr) {
            this.ok = ok;
            this.message = message;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        public boolean ok() {
            return ok;
        }
    }

    private SshExec() {
    }

    /** 仅验证连接与认证，成功后立即断开。 */
    public static Result verify(Context ctx, SshConfigBean config, int timeoutMs) {
        return run(ctx, config, null, timeoutMs);
    }

    /** 执行一次性远程命令（非交互）。 */
    public static Result exec(Context ctx, SshConfigBean config, String command, int timeoutMs) {
        return run(ctx, config, command, timeoutMs);
    }

    private static Result run(Context ctx, SshConfigBean config, String command, int timeoutMs) {
        if (config == null) return fail("配置为空");

        String host = config.getHost() == null ? "" : config.getHost().trim();
        String user = config.getUsername() == null ? "" : config.getUsername().trim();
        if (host.isEmpty()) return fail("主机地址为空");
        if (user.isEmpty()) return fail("用户名为空");

        int port = config.getPort() > 0 ? config.getPort() : 22;
        boolean useKey = "key".equals(config.getAuthType());
        if (timeoutMs <= 0) timeoutMs = 10000;

        Log.d(TAG, "verify start: " + user + "@" + host + ":" + port
                + " authType=" + config.getAuthType()
                + " useKey=" + useKey
                + " passwordEmpty=" + (config.getPassword() == null || config.getPassword().isEmpty())
                + " verifyOnly=" + (command == null || command.trim().isEmpty()));

        Session session = null;
        File keyFile = null;
        try {
            JSch jsch = new JSch();

            if (useKey) {
                String keyContent = config.getKeyContent();
                if (keyContent == null || keyContent.trim().isEmpty()) {
                    return fail("私钥内容为空，请修改配置");
                }
                keyFile = writeTempKey(ctx, keyContent);
                String passphrase = config.getKeyPassphrase();
                if (passphrase != null && !passphrase.isEmpty()) {
                    jsch.addIdentity(keyFile.getAbsolutePath(), passphrase);
                } else {
                    jsch.addIdentity(keyFile.getAbsolutePath());
                }
            }

            session = jsch.getSession(user, host, port);
            if (!useKey) {
                String password = config.getPassword();
                if (password == null || password.isEmpty()) {
                    return fail("密码为空，请修改配置");
                }
                session.setPassword(password);
            }
            // 与现有 ssh 命令的 StrictHostKeyChecking=accept-new 行为等价
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications",
                    useKey ? "publickey" : "password,keyboard-interactive");
            session.connect(timeoutMs);
            Log.d(TAG, "session connected (auth ok), verifyOnly="
                    + (command == null || command.trim().isEmpty()));

            // 只验证：连上即成功
            if (command == null || command.trim().isEmpty()) {
                return new Result(true, "连接验证成功", 0, "", "");
            }

            // 可选：一次性执行远程命令
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(timeoutMs);

            String stdout = readChannel(in, channel, timeoutMs);
            String stderr = readChannel(err, channel, 0);
            int exit = channel.getExitStatus();
            try {
                channel.disconnect();
            } catch (Exception ignored) {
            }

            boolean ok = exit == 0;
            return new Result(ok, ok ? "成功" : "命令退出码 " + exit, exit, stdout, stderr);
        } catch (JSchException e) {
            Log.w(TAG, "JSch error: " + e.getMessage());
            return fail(mapJSchError(e));
        } catch (Exception e) {
            Log.w(TAG, "ssh exec failed: " + e);
            return fail("连接异常: " + e);
        } finally {
            if (session != null) {
                try {
                    session.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (keyFile != null && !keyFile.delete()) {
                Log.w(TAG, "cannot delete temp key: " + keyFile);
            }
        }
    }

    /** 读取 channel 输出，直到 channel 关闭或超时。 */
    private static String readChannel(InputStream in, ChannelExec channel, int timeoutMs)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1000);
        while (!channel.isClosed() || in.available() > 0) {
            if (channel.isClosed() && in.available() == 0) break;
            if (in.available() > 0) {
                int n = in.read(buf, 0, Math.min(in.available(), buf.length));
                if (n < 0) break;
                bos.write(buf, 0, n);
            } else {
                if (System.currentTimeMillis() > deadline) break;
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 把 JSch 异常映射为对用户可读的中文提示。 */
    private static String mapJSchError(JSchException e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("auth fail")) return "认证失败：用户名/密码或私钥错误";
        if (lower.contains("auth cancel")) return "认证被取消";
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时：主机不可达或端口未开放";
        }
        if (lower.contains("connection refused")) return "连接被拒绝：端口未开放或服务未启动";
        if (lower.contains("unknownhost") || lower.contains("name or service not known")
                || lower.contains("nodename nor servname")) {
            return "无法解析主机名，请检查地址";
        }
        if (lower.contains("reject hostkey") || lower.contains("hostkey")) {
            return "主机密钥校验失败";
        }
        return "连接失败：" + msg;
    }

    /** 把私钥内容写到 cache 下的临时文件，供 JSch addIdentity 使用。 */
    private static File writeTempKey(Context ctx, String content) throws IOException {
        File dir = new File(ctx.getCacheDir(), "ssh_keys");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create key dir: " + dir);
        }
        File f = new File(dir, "key_" + System.currentTimeMillis() + ".pem");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            if (!content.endsWith("\n")) out.write('\n');
        }
        // 仅属主可读
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        return f;
    }

    private static Result fail(String message) {
        return new Result(false, message, -1, "", "");
    }
}
