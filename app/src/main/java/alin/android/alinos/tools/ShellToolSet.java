package alin.android.alinos.tools;

import android.content.Context;

import org.json.JSONObject;

import java.util.Map;

import alin.android.alinos.localshell.LocalShellExecutor;

/**
 * 宿主侧 PTY 终端工具集（INTERNAL）。
 *
 * <p><b>不暴露给 AI</b>，仅供测试界面 / 内部调用。AI 侧的 shell 工作走
 * {@link ContainerToolSet} 的 {@code bash}（proot 单次执行）。
 *
 * <p>这些能力与 proot 无关：它们操作的是宿主侧裸 Shell 会话。
 * 返回值统一经 {@link ToolMeta#normalize(JSONObject)} 归一化。
 */
public class ShellToolSet {

    private ShellToolSet() {
    }

    /** 由 {@link ToolRegistry#init(Context)} 调用（幂等）。 */
    public static void register() {
        LocalShellExecutor ex = LocalShellExecutor.getInstance();

        ToolRegistry.register("localshell_create_session",
                "Create a PTY shell session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Unique session id"),
                        ToolMeta.param("session_name", "string", false, "", "Optional display name")),
                p -> ToolMeta.normalize(ex.create_session(
                        p.optString("session_id", ""), p.optString("session_name", ""))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_destroy_session",
                "Destroy a PTY shell session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id")),
                p -> ToolMeta.normalize(ex.destroy_session(p.optString("session_id", ""))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_list_sessions",
                "List all PTY shell sessions.",
                new ToolMeta.Param[0],
                p -> ToolMeta.normalize(ex.list_sessions()),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_search_session",
                "Search PTY shell sessions.",
                ToolMeta.params(
                        ToolMeta.param("query", "string", true, "", "Search keyword"),
                        ToolMeta.param("by", "string", false, "name", "Field to match")),
                p -> ToolMeta.normalize(ex.search_session(
                        p.optString("query", ""), p.optString("by", "name"))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_session_status",
                "Get the status of a PTY shell session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id")),
                p -> ToolMeta.normalize(ex.session_status(p.optString("session_id", ""))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_rename_session",
                "Rename a PTY shell session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("new_name", "string", true, "", "New display name")),
                p -> ToolMeta.normalize(ex.rename_session(
                        p.optString("session_id", ""), p.optString("new_name", ""))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_exec",
                "Execute a command in a PTY session and return rendered output.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("command", "string", true, "", "Command to run"),
                        ToolMeta.param("wait_ms", "long", false, "0", "Milliseconds to wait before reading"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count for line-based modes"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors")),
                p -> ToolMeta.normalize(ex.shell_exec(
                        p.optString("session_id", ""), p.optString("command", ""),
                        p.optLong("wait_ms", 0), p.optString("return_mode", "last_20"),
                        p.optInt("lines", 20), p.optBoolean("color_escape", false))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_exec_capture",
                "Run a one-shot command and capture stdout/stderr/exit code (no PTY session needed).",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", false, "", "Session id (optional hint)"),
                        ToolMeta.param("command", "string", true, "", "Command to run"),
                        ToolMeta.param("timeout_ms", "long", false, "30000", "Timeout in milliseconds"),
                        ToolMeta.param("max_bytes", "int", false, "65536", "Maximum captured bytes")),
                p -> ToolMeta.normalize(ex.exec_capture(
                        p.optString("session_id", ""), p.optString("command", ""),
                        p.optLong("timeout_ms", 30000), p.optInt("max_bytes", 65536))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_write",
                "Write text into a PTY session's stdin.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("text", "string", true, "", "Text to write"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors"),
                        ToolMeta.param("cursor_mark", "boolean", false, "false", "Mark cursor position")),
                p -> ToolMeta.normalize(ex.shell_write(
                        p.optString("session_id", ""), p.optString("text", ""),
                        p.optString("return_mode", "last_20"), p.optInt("lines", 20),
                        p.optBoolean("color_escape", false), p.optBoolean("cursor_mark", false))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_send_key",
                "Send a special key (Ctrl+A~Z, Tab, Enter, arrows, F1~F12...) to a PTY session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("key", "string", true, "", "Key name"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors"),
                        ToolMeta.param("cursor_mark", "boolean", false, "false", "Mark cursor position")),
                p -> ToolMeta.normalize(ex.shell_send_key(
                        p.optString("session_id", ""), p.optString("key", ""),
                        p.optString("return_mode", "last_20"), p.optInt("lines", 20),
                        p.optBoolean("color_escape", false), p.optBoolean("cursor_mark", false))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_send_keys",
                "Send a sequence of keys separated by '|' to a PTY session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("keys", "string", true, "", "Keys joined by '|'"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors"),
                        ToolMeta.param("cursor_mark", "boolean", false, "false", "Mark cursor position")),
                p -> ToolMeta.normalize(ex.shell_send_keys(
                        p.optString("session_id", ""), p.optString("keys", ""),
                        p.optString("return_mode", "last_20"), p.optInt("lines", 20),
                        p.optBoolean("color_escape", false), p.optBoolean("cursor_mark", false))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_read",
                "Read the current rendered screen of a PTY session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors"),
                        ToolMeta.param("cursor_mark", "boolean", false, "false", "Mark cursor position"),
                        ToolMeta.param("wait_ms", "long", false, "0", "Milliseconds to wait before reading")),
                p -> ToolMeta.normalize(ex.shell_read(
                        p.optString("session_id", ""), p.optString("return_mode", "last_20"),
                        p.optInt("lines", 20), p.optBoolean("color_escape", false),
                        p.optBoolean("cursor_mark", false), p.optLong("wait_ms", 0))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_read_history_canvas",
                "Read the scrollback (history) canvas of a PTY session.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("return_mode", "string", false, "last_20", "How much output to return"),
                        ToolMeta.param("lines", "int", false, "20", "Line count"),
                        ToolMeta.param("color_escape", "boolean", false, "false", "Keep ANSI colors")),
                p -> ToolMeta.normalize(ex.read_history_canvas(
                        p.optString("session_id", ""), p.optString("return_mode", "last_20"),
                        p.optInt("lines", 20), p.optBoolean("color_escape", false))),
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_shell_get_debug_view",
                "Dump a PTY session screen as a debug view.",
                ToolMeta.params(
                        ToolMeta.param("session_id", "string", true, "", "Session id"),
                        ToolMeta.param("show_line_numbers", "boolean", false, "false", "Show line numbers"),
                        ToolMeta.param("show_styles", "boolean", false, "true", "Include style info"),
                        ToolMeta.param("show_cursor", "boolean", false, "false", "Mark cursor")),
                p -> ToolMeta.normalize(ex.shell_get_debug_view(
                        p.optString("session_id", ""), p.optBoolean("show_line_numbers", false),
                        p.optBoolean("show_styles", true), p.optBoolean("show_cursor", false))),
                ToolMeta.Scope.DEBUG, ToolMeta.Category.SHELL);

        ToolRegistry.register("localshell_get_session_status",
                "List alive/dead state of all known PTY sessions (internal map).",
                new ToolMeta.Param[0],
                p -> {
                    JSONObject o = ToolMeta.ok();
                    try {
                        Map<String, Boolean> m = ex.getSessionStatus();
                        for (Map.Entry<String, Boolean> e : m.entrySet()) {
                            o.put(e.getKey(), e.getValue());
                        }
                    } catch (Exception e) {
                        return ToolMeta.error(e.getMessage());
                    }
                    return o;
                },
                ToolMeta.Scope.DEBUG, ToolMeta.Category.SHELL);
    }
}
