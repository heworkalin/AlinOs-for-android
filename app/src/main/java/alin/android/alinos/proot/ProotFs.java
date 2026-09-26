package alin.android.alinos.proot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 容器文件系统操作：read / write / edit / ls / grep。
 *
 * <p><b>所有 path 参数都是容器内路径</b>（相对路径以 {@code /root} 为基准），
 * 由 {@link ProotPathMapper} 完成补全与 symlink 解析，宿主路径从不出现在接口上。
 *
 * <p>符号链接采用<b>写穿</b>语义：修改链接指向的真实文件，链接本身保持不变，
 * 并在结果中返回 {@code link_warning} 提示 AI。
 */
public final class ProotFs {

    /** 单次读取的最大行数，防止把上下文撑爆。 */
    public static final int MAX_READ_LINES = 2000;

    private final ProotPathMapper mapper;

    public ProotFs(File rootfs) {
        this.mapper = new ProotPathMapper(rootfs);
    }

    public ProotPathMapper mapper() {
        return mapper;
    }

    // =====================================================================
    // read
    // =====================================================================

    public static final class ReadResult {
        public String containerPath;
        public String realContainerPath;
        public String linkWarning;
        public String content;
        public int totalLines;
        public int startLine;
        public boolean truncated;
    }

    /** 读取文本文件；offset 为起始行号（1 基），limit 为最多行数。 */
    public ReadResult read(String path, int offset, int limit) throws IOException {
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File f = r.hostFile;
        if (!f.exists()) throw new IOException("file not found: " + r.containerPath);
        if (f.isDirectory()) throw new IOException("is a directory, use proot_ls instead: " + r.containerPath);

        List<String> lines = readLines(f);
        int total = lines.size();
        int start = Math.max(1, offset);
        int max = limit <= 0 ? MAX_READ_LINES : Math.min(limit, MAX_READ_LINES);
        int end = Math.min(total, start - 1 + max);
        if (start > total) start = total + 1;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(String.format("%6d\t%s%n", i, lines.get(i - 1)));
        }

        ReadResult out = new ReadResult();
        out.containerPath = r.containerPath;
        out.realContainerPath = r.realContainerPath;
        out.linkWarning = r.linkWarning();
        out.content = sb.toString();
        out.totalLines = total;
        out.startLine = start;
        out.truncated = end < total;
        return out;
    }

    // =====================================================================
    // write
    // =====================================================================

    public static final class WriteResult {
        public String containerPath;
        public String realContainerPath;
        public String linkWarning;
        public int bytes;
        public boolean append;
        public boolean existed;
    }

    /**
     * 写入文本文件（覆盖或追加）。自动创建父目录。
     *
     * <p>若路径是符号链接，会写入其指向的真实文件（链接保持不动）；
     * 若链接目标不存在（悬空链接），则在目标位置创建文件。
     */
    public WriteResult write(String path, String content, boolean append) throws IOException {
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File f = r.hostFile;
        boolean existed = f.exists();

        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("cannot create parent directory: " + mapper.toContainerPath(parent));
        }

        byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            out.write(data);
        }

        WriteResult w = new WriteResult();
        w.containerPath = r.containerPath;
        w.realContainerPath = r.realContainerPath;
        w.linkWarning = r.linkWarning();
        w.bytes = data.length;
        w.append = append;
        w.existed = existed;
        return w;
    }

    // =====================================================================
    // edit
    // =====================================================================

    public static final class EditResult {
        public String containerPath;
        public String realContainerPath;
        public String linkWarning;
        public int replacements;
        public int newBytes;
    }

    /**
     * 精确文本替换。
     *
     * @param replaceAll false 时要求 oldText 在文件中唯一出现，否则报错（避免误改）
     */
    public EditResult edit(String path, String oldText, String newText, boolean replaceAll)
            throws IOException {
        if (oldText == null || oldText.isEmpty()) {
            throw new IOException("old_text must not be empty");
        }
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File f = r.hostFile;
        if (!f.exists()) throw new IOException("file not found: " + r.containerPath);

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int first = content.indexOf(oldText);
        if (first < 0) {
            throw new IOException("old_text not found (it must match the file content exactly, "
                    + "including indentation)");
        }

        int count = 0;
        if (replaceAll) {
            int idx = 0;
            while ((idx = content.indexOf(oldText, idx)) >= 0) {
                count++;
                idx += oldText.length();
            }
        } else {
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                throw new IOException("old_text occurs multiple times; provide a longer unique snippet, "
                        + "or set replace_all=true");
            }
            count = 1;
        }

        String updated = replaceAll
                ? content.replace(oldText, newText == null ? "" : newText)
                : content.substring(0, first)
                        + (newText == null ? "" : newText)
                        + content.substring(first + oldText.length());

        Files.write(f.toPath(), updated.getBytes(StandardCharsets.UTF_8));

        EditResult e = new EditResult();
        e.containerPath = r.containerPath;
        e.realContainerPath = r.realContainerPath;
        e.linkWarning = r.linkWarning();
        e.replacements = count;
        e.newBytes = updated.getBytes(StandardCharsets.UTF_8).length;
        return e;
    }

    // =====================================================================
    // ls
    // =====================================================================

    public static final class LsResult {
        public String containerPath;
        public String linkWarning;
        public List<String> entries = new ArrayList<>();
        public int dirs;
        public int files;
        public int links;
        public boolean truncated;
    }

    /** 列目录（可选递归）。名称回显为容器内路径。 */
    public LsResult ls(String path, boolean recursive) throws IOException {
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File dir = r.hostFile;
        if (!dir.exists()) throw new IOException("directory not found: " + r.containerPath);
        if (!dir.isDirectory()) throw new IOException("not a directory (it may be a file): " + r.containerPath);

        LsResult out = new LsResult();
        out.containerPath = r.containerPath;
        out.linkWarning = r.linkWarning();
        walk(dir, r.containerPath, recursive, out, 0);
        return out;
    }

    private static final int MAX_LS_ENTRIES = 2000;

    private void walk(File dir, String containerPath, boolean recursive, LsResult out, int depth) {
        File[] children = dir.listFiles();
        if (children == null) return;
        List<File> sorted = new ArrayList<>();
        for (File c : children) sorted.add(c);
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File c : sorted) {
            if (out.entries.size() >= MAX_LS_ENTRIES) {
                out.truncated = true;
                return;
            }
            boolean link = Files.isSymbolicLink(c.toPath());
            String cp = containerPath.endsWith("/")
                    ? containerPath + c.getName()
                    : containerPath + "/" + c.getName();
            String kind;
            if (link) {
                kind = "l";
                out.links++;
            } else if (c.isDirectory()) {
                kind = "d";
                out.dirs++;
            } else {
                kind = "-";
                out.files++;
            }
            String extra = link ? " -> " + readLinkQuietly(c) : "";
            String size = c.isDirectory() && !link ? "-" : String.valueOf(c.length());
            out.entries.add(kind + " " + size + "\t" + cp + extra);

            if (recursive && c.isDirectory() && !link && depth < 16) {
                walk(c, cp, true, out, depth + 1);
            }
        }
    }

    // =====================================================================
    // grep / find
    // =====================================================================

    public static final class GrepResult {
        public String containerPath;
        public List<String> matches = new ArrayList<>();
        public boolean truncated;
        public String error;
    }

    /** 内容搜索（正则）；返回 `容器路径:行号:内容`。 */
    public GrepResult grep(String pattern, String path, boolean recursive) {
        GrepResult out = new GrepResult();
        Pattern re;
        try {
            re = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            out.error = "invalid regular expression: " + e.getMessage();
            return out;
        }

        try {
            ProotPathMapper.Resolved r = mapper.resolve(path);
            out.containerPath = r.containerPath;
            File target = r.hostFile;
            if (!target.exists()) {
                out.error = "path not found: " + r.containerPath;
                return out;
            }
            if (target.isDirectory()) {
                grepDir(target, r.containerPath, re, recursive, out, 0);
            } else {
                grepFile(target, r.containerPath, re, out);
            }
        } catch (Exception e) {
            out.error = String.valueOf(e.getMessage());
        }
        return out;
    }

    private static final int MAX_GREP_MATCHES = 1000;

    private void grepDir(File dir, String cp, Pattern re, boolean recursive,
                         GrepResult out, int depth) {
        File[] children = dir.listFiles();
        if (children == null || out.matches.size() >= MAX_GREP_MATCHES) return;
        for (File c : children) {
            if (out.matches.size() >= MAX_GREP_MATCHES) {
                out.truncated = true;
                return;
            }
            String ccp = cp.endsWith("/") ? cp + c.getName() : cp + "/" + c.getName();
            if (c.isDirectory()) {
                if (recursive && !Files.isSymbolicLink(c.toPath()) && depth < 16) {
                    grepDir(c, ccp, re, true, out, depth + 1);
                }
            } else {
                grepFile(c, ccp, re, out);
            }
        }
    }

    private void grepFile(File f, String cp, Pattern re, GrepResult out) {
        try {
            if (f.length() > 4L * 1024 * 1024) return; // 跳过超大文件
            List<String> lines = readLines(f);
            for (int i = 0; i < lines.size() && out.matches.size() < MAX_GREP_MATCHES; i++) {
                if (re.matcher(lines.get(i)).find()) {
                    out.matches.add(cp + ":" + (i + 1) + ":" + lines.get(i));
                }
            }
        } catch (Exception ignored) {
            // 二进制/无权限文件直接跳过
        }
    }

    // =====================================================================
    // edit (multi)
    // =====================================================================

    public static final class MultiEditResult {
        public String containerPath;
        public String realContainerPath;
        public String linkWarning;
        public int editCount;
        public int newBytes;
    }

    /**
     * 多编辑：每个 oldText 都相对<b>原始文件</b>匹配（与执行顺序无关），
     * 必须唯一且互不重叠。
     *
     * @param edits 每项为 {oldText, newText}
     */
    public MultiEditResult editMany(String path, List<String[]> edits, boolean replaceAll)
            throws IOException {
        if (edits == null || edits.isEmpty()) {
            throw new IOException("edits must not be empty");
        }
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File f = r.hostFile;
        if (!f.exists()) throw new IOException("file not found: " + r.containerPath);

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        List<int[]> spans = new ArrayList<>();
        List<String> news = new ArrayList<>();

        for (String[] e : edits) {
            String oldText = e[0];
            String newText = e[1] == null ? "" : e[1];
            if (oldText == null || oldText.isEmpty()) {
                throw new IOException("oldText must not be empty");
            }
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new IOException("oldText not found: " + preview(oldText));
            }
            if (replaceAll) {
                int idx = 0;
                while ((idx = content.indexOf(oldText, idx)) >= 0) {
                    spans.add(new int[]{idx, idx + oldText.length()});
                    news.add(newText);
                    idx += oldText.length();
                }
            } else {
                if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                    throw new IOException("oldText is not unique, provide a longer snippet: "
                            + preview(oldText));
                }
                spans.add(new int[]{first, first + oldText.length()});
                news.add(newText);
            }
        }

        // 排序并检查重叠
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(spans.get(a)[0], spans.get(b)[0]));
        for (int i = 1; i < order.size(); i++) {
            int[] prev = spans.get(order.get(i - 1));
            int[] cur = spans.get(order.get(i));
            if (cur[0] < prev[1]) {
                throw new IOException("edits overlap; merge them into one edit");
            }
        }

        // 从后往前替换，避免偏移
        StringBuilder sb = new StringBuilder(content);
        for (int i = order.size() - 1; i >= 0; i--) {
            int idx = order.get(i);
            int[] sp = spans.get(idx);
            sb.replace(sp[0], sp[1], news.get(idx));
        }

        String updated = sb.toString();
        Files.write(f.toPath(), updated.getBytes(StandardCharsets.UTF_8));

        MultiEditResult out = new MultiEditResult();
        out.containerPath = r.containerPath;
        out.realContainerPath = r.realContainerPath;
        out.linkWarning = r.linkWarning();
        out.editCount = spans.size();
        out.newBytes = updated.getBytes(StandardCharsets.UTF_8).length;
        return out;
    }

    private static String preview(String s) {
        s = s.replace("\n", "\\n");
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    // =====================================================================
    // find
    // =====================================================================

    public static final class FindResult {
        public String containerPath;
        public List<String> matches = new ArrayList<>();
        public boolean truncated;
        public String error;
    }

    /** 按 glob（如 *.txt、**\/*.json）查找文件。 */
    public FindResult find(String glob, String path, int limit) {
        FindResult out = new FindResult();
        if (limit <= 0) limit = 1000;
        try {
            ProotPathMapper.Resolved r = mapper.resolve(path);
            out.containerPath = r.containerPath;
            File base = r.hostFile;
            if (!base.exists()) {
                out.error = "path not found: " + r.containerPath;
                return out;
            }
            final java.nio.file.PathMatcher matcher = java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:" + glob);
            findWalk(base, r.containerPath, matcher, out, limit, 0);
        } catch (Exception e) {
            out.error = String.valueOf(e.getMessage());
        }
        return out;
    }

    private void findWalk(File dir, String cp, java.nio.file.PathMatcher matcher,
                          FindResult out, int limit, int depth) {
        File[] children = dir.listFiles();
        if (children == null || depth > 20) return;
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File c : children) {
            if (out.matches.size() >= limit) {
                out.truncated = true;
                return;
            }
            String ccp = cp.endsWith("/") ? cp + c.getName() : cp + "/" + c.getName();
            // 同时用「文件名」和「容器内完整路径」两种形式匹配 glob
            if (matcher.matches(java.nio.file.Paths.get(c.getName()))
                    || matcher.matches(java.nio.file.Paths.get(ccp.substring(1)))) {
                out.matches.add(ccp);
            }
            if (c.isDirectory() && !Files.isSymbolicLink(c.toPath())) {
                findWalk(c, ccp, matcher, out, limit, depth + 1);
            }
        }
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private static List<String> readLines(File f) throws IOException {
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }

    private static String readLinkQuietly(File f) {
        try {
            return Files.readSymbolicLink(f.toPath()).toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
