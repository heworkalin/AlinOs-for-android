package alin.android.alinos.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.proot.ProotContainerManager;
import alin.android.alinos.proot.ProotFs;
import alin.android.alinos.proot.ProotPathMapper;

/**
 * 工作环境工具集：bash / read / write / edit / ls / grep / find。
 *
 * <p>面向 AI 的描述全部为英文，并且<b>只说明这是一个 Ubuntu 工作环境</b>，
 * 不暴露底层实现细节。路径一律为环境内路径（相对路径以 {@code /root} 为基准），
 * 宿主机路径不可见。
 *
 * <p>符号链接写穿：读写链接指向的真实文件，链接本身不变，结果里返回 {@code link_warning}。
 */
public class ContainerToolSet {

    private ContainerToolSet() {
    }

    /** 由 {@link ToolRegistry#init(Context)} 调用（幂等）。 */
    public static void register(Context ctx) {
        registerBash(ctx);
        registerRead(ctx);
        registerWrite(ctx);
        registerEdit(ctx);
        registerLs(ctx);
        registerGrep(ctx);
        registerFind(ctx);
    }

    private static ProotFs fs(Context ctx) {
        return new ProotFs(ProotContainerManager.containerDir(ctx));
    }

    // =====================================================================
    // bash
    // =====================================================================

    private static void registerBash(Context ctx) {
        ToolRegistry.register("bash",
                "Execute a bash command in the working environment. Use it for builds, package "
                + "installation (apt), running scripts, git, and any other shell work. The "
                + "environment is a full Ubuntu userland running as root. Each call starts a fresh "
                + "shell, so chain related commands together when state matters. All paths are "
                + "environment-internal.",
                ToolMeta.params(
                        ToolMeta.param("command", "string", true, "", "Shell command to execute"),
                        ToolMeta.param("timeout", "long", false, "60",
                                "Timeout in seconds (default 60, max 600)")),
                p -> {
                    long seconds = p.optLong("timeout", 60L);
                    if (seconds <= 0) seconds = 60L;
                    if (seconds > 600L) seconds = 600L;
                    ProotContainerManager.Result r = ProotContainerManager.exec(
                            ctx, p.optString("command", ""), seconds * 1000L);
                    return bashJson(r, p.optString("command", ""));
                });
    }

    // =====================================================================
    // read / write / edit
    // =====================================================================

    private static void registerRead(Context ctx) {
        ToolRegistry.register("read",
                "Read the contents of a file.",
                ToolMeta.params(
                        ToolMeta.param("path", "string", true, "",
                                "Path to the file to read (relative or absolute)"),
                        ToolMeta.param("offset", "int", false, "1",
                                "Line number to start reading from (1-indexed)"),
                        ToolMeta.param("limit", "int", false, "2000",
                                "Maximum number of lines to read")),
                p -> {
                    ProotFs.ReadResult r = fs(ctx).read(
                            p.optString("path", ""),
                            p.optInt("offset", 1),
                            p.optInt("limit", ProotFs.MAX_READ_LINES));
                    JSONObject o = new JSONObject();
                    o.put("path", r.containerPath);
                    o.put("real_path", r.realContainerPath);
                    putLinkWarning(o, r.linkWarning);
                    o.put("total_lines", r.totalLines);
                    o.put("start_line", r.startLine);
                    o.put("truncated", r.truncated);
                    o.put("content", r.content);
                    return o;
                });
    }

    private static void registerWrite(Context ctx) {
        ToolRegistry.register("write",
                "Create or overwrite files.",
                ToolMeta.params(
                        ToolMeta.param("path", "string", true, "",
                                "Path to the file to write (relative or absolute)"),
                        ToolMeta.param("content", "string", true, "", "Content to write to the file")),
                p -> {
                    ProotFs.WriteResult w = fs(ctx).write(
                            p.optString("path", ""),
                            p.optString("content", ""),
                            false);
                    JSONObject o = new JSONObject();
                    o.put("path", w.containerPath);
                    o.put("real_path", w.realContainerPath);
                    putLinkWarning(o, w.linkWarning);
                    o.put("bytes", w.bytes);
                    o.put("created", !w.existed);
                    return o;
                });
    }

    private static void registerEdit(Context ctx) {
        ToolRegistry.register("edit",
                "Make precise file edits with exact text replacement, including multiple disjoint "
                + "edits in one call. Each oldText is matched against the original file, not "
                + "incrementally; edits must not overlap.",
                ToolMeta.params(
                        ToolMeta.param("path", "string", true, "",
                                "Path to the file to edit (relative or absolute)"),
                        ToolMeta.param("edits", "array", true, "",
                                "One or more targeted replacements: "
                                        + "[{\"oldText\": \"...\", \"newText\": \"...\"}]"),
                        ToolMeta.param("replace_all", "boolean", false, "false",
                                "Replace every occurrence of each oldText instead of requiring uniqueness")),
                p -> {
                    JSONArray arr = p.optJSONArray("edits");
                    if (arr == null || arr.length() == 0) {
                        throw new IllegalArgumentException("edits must be a non-empty array");
                    }
                    List<String[]> edits = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        edits.add(new String[]{
                                e.optString("oldText", ""),
                                e.optString("newText", "")});
                    }
                    ProotFs.MultiEditResult r = fs(ctx).editMany(
                            p.optString("path", ""), edits, p.optBoolean("replace_all", false));
                    JSONObject o = new JSONObject();
                    o.put("path", r.containerPath);
                    o.put("real_path", r.realContainerPath);
                    putLinkWarning(o, r.linkWarning);
                    o.put("edits_applied", r.editCount);
                    o.put("new_bytes", r.newBytes);
                    return o;
                });
    }

    // =====================================================================
    // ls / grep / find
    // =====================================================================

    private static void registerLs(Context ctx) {
        ToolRegistry.register("ls",
                "List directory contents.",
                ToolMeta.params(
                        ToolMeta.param("path", "string", false, "/root",
                                "Directory to list (default: /root)"),
                        ToolMeta.param("recursive", "boolean", false, "false",
                                "List subdirectories recursively"),
                        ToolMeta.param("limit", "int", false, "500",
                                "Maximum number of entries to return")),
                p -> {
                    ProotFs.LsResult r = fs(ctx).ls(
                            p.optString("path", ProotPathMapper.DEFAULT_CWD),
                            p.optBoolean("recursive", false));
                    int limit = p.optInt("limit", 500);
                    List<String> entries = r.entries;
                    boolean truncated = r.truncated;
                    if (limit > 0 && entries.size() > limit) {
                        entries = entries.subList(0, limit);
                        truncated = true;
                    }
                    JSONObject o = new JSONObject();
                    o.put("path", r.containerPath);
                    putLinkWarning(o, r.linkWarning);
                    o.put("dirs", r.dirs);
                    o.put("files", r.files);
                    o.put("links", r.links);
                    o.put("truncated", truncated);
                    o.put("entries", new JSONArray(entries));
                    return o;
                });
    }

    private static void registerGrep(Context ctx) {
        ToolRegistry.register("grep",
                "Search file contents for patterns. Returns matches as path:line:content.",
                ToolMeta.params(
                        ToolMeta.param("pattern", "string", true, "",
                                "Search pattern (regular expression)"),
                        ToolMeta.param("path", "string", false, "/root",
                                "Directory or file to search (default: /root)"),
                        ToolMeta.param("ignore_case", "boolean", false, "false",
                                "Case-insensitive search"),
                        ToolMeta.param("recursive", "boolean", false, "true",
                                "Recurse into subdirectories"),
                        ToolMeta.param("limit", "int", false, "100",
                                "Maximum number of matches to return")),
                p -> {
                    String pattern = p.optString("pattern", "");
                    if (p.optBoolean("ignore_case", false)) {
                        pattern = "(?i)" + pattern;
                    }
                    ProotFs.GrepResult r = fs(ctx).grep(
                            pattern,
                            p.optString("path", ProotPathMapper.DEFAULT_CWD),
                            p.optBoolean("recursive", true));
                    int limit = p.optInt("limit", 100);
                    List<String> matches = r.matches;
                    boolean truncated = r.truncated;
                    if (limit > 0 && matches.size() > limit) {
                        matches = matches.subList(0, limit);
                        truncated = true;
                    }
                    JSONObject o = new JSONObject();
                    o.put("path", r.containerPath == null ? "" : r.containerPath);
                    if (r.error != null) o.put("error", r.error);
                    o.put("match_count", matches.size());
                    o.put("truncated", truncated);
                    o.put("matches", new JSONArray(matches));
                    return o;
                });
    }

    private static void registerFind(Context ctx) {
        ToolRegistry.register("find",
                "Find files by glob pattern. Matches both file names and full paths, "
                + "e.g. '*.json' or 'etc/**/*.conf'.",
                ToolMeta.params(
                        ToolMeta.param("pattern", "string", true, "",
                                "Glob pattern, e.g. '*.ts', '**/*.json'"),
                        ToolMeta.param("path", "string", false, "/root",
                                "Directory to search in (default: /root)"),
                        ToolMeta.param("limit", "int", false, "1000",
                                "Maximum number of results")),
                p -> {
                    ProotFs.FindResult r = fs(ctx).find(
                            p.optString("pattern", "*"),
                            p.optString("path", ProotPathMapper.DEFAULT_CWD),
                            p.optInt("limit", 1000));
                    JSONObject o = new JSONObject();
                    o.put("path", r.containerPath == null ? "" : r.containerPath);
                    if (r.error != null) o.put("error", r.error);
                    o.put("match_count", r.matches.size());
                    o.put("truncated", r.truncated);
                    o.put("matches", new JSONArray(r.matches));
                    return o;
                });
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static void putLinkWarning(JSONObject o, String warning) {
        if (warning != null) {
            try {
                o.put("link_warning", warning);
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject bashJson(ProotContainerManager.Result r, String command) {
        JSONObject o = new JSONObject();
        try {
            o.put("command", command);
            o.put("ok", r.ok());
            o.put("exit_code", r.exitCode);
            o.put("timeout", r.timeout);
            o.put("stdout", clip(r.stdout));
            o.put("stderr", clip(r.stderr));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Truncate long output so it cannot blow up the model context. */
    private static String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= 16000 ? s : s.substring(0, 16000)
                + "\n...(truncated, " + s.length() + " bytes total)";
    }
}
