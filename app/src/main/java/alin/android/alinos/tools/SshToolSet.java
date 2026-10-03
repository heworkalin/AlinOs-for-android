package alin.android.alinos.tools;

import android.content.Context;

import alin.android.alinos.db.SshDbHelper;
import alin.android.alinos.localshell.SshExec;
import alin.android.alinos.bean.SshConfigBean;

/**
 * 远端 SSH 工具集（INTERNAL）。
 *
 * <p>不暴露给 AI，仅供测试界面与内部调用。基于已保存的 SSH 配置执行一次性远端命令。
 */
public class SshToolSet {

    private SshToolSet() {
    }

    public static void register(final Context ctx) {
        final SshDbHelper db = new SshDbHelper(ctx);

        ToolRegistry.register("ssh_list_configs",
                "List saved SSH configurations (uuid / name / host / user).",
                new ToolMeta.Param[0],
                p -> {
                    org.json.JSONObject o = ToolMeta.ok();
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (SshConfigBean c : db.getAllConfigs()) {
                        org.json.JSONObject item = new org.json.JSONObject();
                        item.put("id", c.getId());
                        item.put("uuid", c.getUuid());
                        item.put("name", c.getName());
                        item.put("host", c.getHost());
                        item.put("port", c.getPort());
                        item.put("username", c.getUsername());
                        item.put("auth_type", c.getAuthType());
                        arr.put(item);
                    }
                    o.put("total", arr.length());
                    o.put("configs", arr);
                    return o;
                },
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SSH);

        ToolRegistry.register("ssh_verify",
                "Verify connectivity and authentication for a saved SSH config.",
                ToolMeta.params(
                        ToolMeta.param("uuid", "string", true, "", "SSH config uuid"),
                        ToolMeta.param("timeout_ms", "int", false, "10000", "Timeout in milliseconds")),
                p -> sshResult(SshExec.verify(ctx,
                        db.getConfigByUuid(p.optString("uuid", "")),
                        p.optInt("timeout_ms", 10000))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SSH);

        ToolRegistry.register("ssh_exec",
                "Run a one-shot command on a remote host over SSH.",
                ToolMeta.params(
                        ToolMeta.param("uuid", "string", true, "", "SSH config uuid"),
                        ToolMeta.param("command", "string", true, "", "Command to run remotely"),
                        ToolMeta.param("timeout_ms", "int", false, "30000", "Timeout in milliseconds")),
                p -> sshResult(SshExec.exec(ctx,
                        db.getConfigByUuid(p.optString("uuid", "")),
                        p.optString("command", ""),
                        p.optInt("timeout_ms", 30000))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SSH);
    }

    private static org.json.JSONObject sshResult(SshExec.Result r) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("status", r.ok() ? "success" : "error");
            if (!r.ok()) o.put("error", r.message == null ? "ssh failed" : r.message);
            o.put("exit_code", r.exitCode);
            o.put("stdout", r.stdout);
            o.put("stderr", r.stderr);
        } catch (Exception ignored) {
        }
        return o;
    }
}
