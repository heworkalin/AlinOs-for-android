package alin.android.alinos.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Environment description tool — the "read this first" skill for AI / MCP clients.
 *
 * <p>It describes the working environment at a high level: a full Ubuntu userland where the
 * model can work freely (including installing packages). It intentionally does <b>not</b>
 * expose implementation details of the underlying sandbox.
 *
 * <p>All prompt-facing text is English.
 */
public class EnvironmentToolSet {

    private EnvironmentToolSet() {
    }

    public static void register() {
        ToolRegistry.register("system_environment",
                "Describe the working environment: which tools are available and how paths and "
                + "commands behave. Read this first when unsure about your capabilities.",
                new ToolMeta.Param[0],
                p -> buildEnvironmentInfo()
        );
    }

    private static JSONObject buildEnvironmentInfo() {
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("server", "AlinOs Agent Server");

            JSONObject environment = new JSONObject();
            environment.put("os", "Ubuntu 24.04 LTS (noble)");
            environment.put("user", "root");
            environment.put("working_dir", "/root");
            environment.put("package_manager", "apt / dpkg");
            environment.put("capabilities", "Full Linux userland: install any package, build software, "
                    + "run services, edit files, and use the shell freely.");
            environment.put("shell", "bash");
            result.put("environment", environment);

            JSONObject paths = new JSONObject();
            paths.put("rule", "All path arguments are environment-internal paths.");
            paths.put("absolute", "e.g. /etc/hosts");
            paths.put("relative", "relative paths resolve against /root, so \"notes.txt\" means \"/root/notes.txt\"");
            paths.put("home", "~ expands to /root");
            paths.put("symlinks", "read/write/edit follow symlinks: the target is modified and the "
                    + "result includes link_warning with the resolved path");
            result.put("paths", paths);

            result.put("tools", new JSONArray()
                    .put("bash - execute a shell command")
                    .put("read - read a file")
                    .put("write - create or overwrite a file")
                    .put("edit - exact text replacement, supports multiple edits per call")
                    .put("ls - list directory contents")
                    .put("grep - search file contents")
                    .put("find - find files by glob pattern")
                    .put("search_tools - search the tool registry"));

            result.put("guidelines", new JSONArray()
                    .put("Use bash for anything a shell can do; use read/write/edit for file work.")
                    .put("Each bash call is a fresh shell: chain commands with \" && \" when state matters.")
                    .put("Install packages non-interactively, e.g. apt-get update && apt-get install -y <pkg>.")
                    .put("For long-running work set a larger timeout on the bash call."));

            result.put("safety", new JSONArray()
                    .put("Ask the user before anything destructive or irreversible.")
                    .put("Do not run commands unrelated to the user's request."));
        } catch (Exception ignored) {
        }
        return result;
    }
}
