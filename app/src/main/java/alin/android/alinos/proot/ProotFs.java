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

    /** 单次读取的最大字节数（对齐 pi 的 50KB）。 */
    public static final int MAX_READ_BYTES = 50 * 1024;

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
        /** 空内容/空行区间等提示，英文。 */
        public String warning;
        /** true 表示路径是目录，不可作为文件读取。 */
        public boolean isDirectory;
        public String content;
        public int totalLines;
        public int startLine;
        public boolean truncated;
        /** 截断时的续读起始行（1 基）；未截断时为最后一行 + 1。 */
        public int nextOffset;
    }

    /** 读取文本文件；offset 为起始行号（1 基），limit 为最多行数。
     *
     * <p>{@code content} 为磁盘原始内容（保留原行终止符，不加行号前缀）。
     * 路径是目录时不抛异常，而是返回 {@code isDirectory=true} + 英文警告。 */
    public ReadResult read(String path, int offset, int limit) throws IOException {
        ProotPathMapper.Resolved r = mapper.resolve(path);
        File f = r.hostFile;
        if (!f.exists()) throw new IOException("file not found: " + r.containerPath);

        if (f.isDirectory()) {
            ReadResult out = new ReadResult();
            out.containerPath = r.containerPath;
            out.realContainerPath = r.realContainerPath;
            out.linkWarning = r.linkWarning();
            out.isDirectory = true;
            out.content = "";
            out.totalLines = 0;
            out.startLine = 1;
            out.truncated = false;
            out.warning = "Note: this path is a directory, not a file; it cannot be read as a file. "
                    + "Use the ls tool to list its contents.";
            return out;
        }

        // 原始字节按行切分，保留行终止符：content 就是磁盘原始内容。
        byte[] rawBytes = Files.readAllBytes(f.toPath());
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        List<String> segs = splitKeepEnds(raw);
        int total = segs.size();
        int start = Math.max(1, offset);
        int max = limit <= 0 ? MAX_READ_LINES : Math.min(limit, MAX_READ_LINES);
        int end = Math.min(total, start - 1 + max);

        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        int lastIncluded = start - 1;
        boolean firstLineOverLimit = false;
        for (int i = start; i <= end; i++) {
            String seg = segs.get(i - 1);
            int segBytes = seg.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + segBytes > MAX_READ_BYTES) {
                if (bytes == 0) {
                    sb.append(truncateUtf8(seg, MAX_READ_BYTES));
                    lastIncluded = i;
                    firstLineOverLimit = true;
                }
                break;
            }
            sb.append(seg);
            bytes += segBytes;
            lastIncluded = i;
        }

        ReadResult out = new ReadResult();
        out.containerPath = r.containerPath;
        out.realContainerPath = r.realContainerPath;
        out.linkWarning = r.linkWarning();
        out.content = sb.toString();
        out.totalLines = total;
        out.startLine = start > total ? total + 1 : start;
        out.nextOffset = lastIncluded + 1;
        out.truncated = lastIncluded < total;
        if (total == 0) {
            out.warning = "Note: this file is empty (0 bytes); there is no content to display.";
        } else if (sb.length() == 0) {
            out.warning = "Note: no content in the requested line range (the file has "
                    + total + " line(s)).";
        } else if (firstLineOverLimit) {
            out.warning = "Note: line " + start + " exceeds the " + MAX_READ_BYTES
                    + "-byte read limit and was truncated.";
        } else if (out.truncated) {
            out.warning = "Note: output truncated at " + MAX_READ_BYTES
                    + " bytes. Use offset=" + out.nextOffset + " to continue.";
        }
        return out;
    }

    /** 按 UTF-8 字符边界截断到至多 maxBytes。 */
    private static String truncateUtf8(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        int end = maxBytes;
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    /** 按 {@code \n} 切分并保留每段末尾的换行符（原始内容保真）。 */
    private static List<String> splitKeepEnds(String raw) {
        List<String> segs = new ArrayList<>();
        int i = 0, n = raw.length();
        while (i < n) {
            int j = raw.indexOf('\n', i);
            if (j < 0) {
                segs.add(raw.substring(i));
                break;
            }
            segs.add(raw.substring(i, j + 1));
            i = j + 1;
        }
        return segs;
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
        try {
            // 按文件串行化，避免并发写的读-改-写互相覆盖。
            FileMutationQueue.run(f, () -> {
                try (FileOutputStream out = new FileOutputStream(f, append)) {
                    out.write(data);
                }
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
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
        public boolean usedFuzzyMatch;
        public String diff;
        public int firstChangedLine;
    }

    /**
     * 单个编辑：语义与 {@link #editMany} 完全一致（精确优先，失败 fuzzy）。
     *
     * @param replaceAll false 时要求 oldText 唯一，否则报错；true 时替换全部出现
     */
    public EditResult edit(String path, String oldText, String newText, boolean replaceAll)
            throws IOException {
        List<String[]> edits = new ArrayList<>();
        edits.add(new String[]{oldText, newText});
        MultiEditResult m = editMany(path, edits, replaceAll);

        EditResult e = new EditResult();
        e.containerPath = m.containerPath;
        e.realContainerPath = m.realContainerPath;
        e.linkWarning = m.linkWarning;
        e.replacements = m.editCount;
        e.newBytes = m.newBytes;
        e.usedFuzzyMatch = m.usedFuzzyMatch;
        e.diff = m.diff;
        e.firstChangedLine = m.firstChangedLine;
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
        public boolean usedFuzzyMatch;
        public String diff;
        public int firstChangedLine;
    }

    /**
     * 多编辑：每个 oldText 都相对<b>原始文件</b>匹配（与执行顺序无关）。
     *
     * <p>语义对齐 pi：精确匹配优先，失败回退 fuzzy 匹配；每个 oldText 必须唯一；
     * BOM / 行尾在匹配前归一、写回时还原；替换后无变化则报错。
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
        try {
            return FileMutationQueue.run(f, () -> editManyLocked(r, f, edits, replaceAll));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
    }

    private MultiEditResult editManyLocked(ProotPathMapper.Resolved r, File f,
                                           List<String[]> edits, boolean replaceAll)
            throws IOException {
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        // BOM 在匹配前剥离，写回时恢复（模型不会把不可见 BOM 写进 oldText）。
        String bom = "";
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
            bom = "\uFEFF";
            raw = raw.substring(1);
        }
        String ending = EditEngine.detectLineEnding(raw);
        String normalized = EditEngine.normalizeToLF(raw);

        List<String[]> normEdits = new ArrayList<>();
        for (String[] e : edits) {
            normEdits.add(new String[]{
                    EditEngine.normalizeToLF(e[0]),
                    EditEngine.normalizeToLF(e[1] == null ? "" : e[1])});
        }

        EditEngine.Result res = EditEngine.apply(normalized, normEdits, r.containerPath, replaceAll);

        String finalContent = bom + EditEngine.restoreLineEndings(res.newContent, ending);
        byte[] outBytes = finalContent.getBytes(StandardCharsets.UTF_8);
        Files.write(f.toPath(), outBytes);

        MultiEditResult out = new MultiEditResult();
        out.containerPath = r.containerPath;
        out.realContainerPath = r.realContainerPath;
        out.linkWarning = r.linkWarning();
        out.editCount = res.editCount;
        out.newBytes = outBytes.length;
        out.usedFuzzyMatch = res.usedFuzzyMatch;
        out.diff = res.diff;
        out.firstChangedLine = res.firstChangedLine;
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

    private String readLinkQuietly(File f) {
        try {
            // 剥掉 rootfs 前缀：proot 的伪硬链接目标是宿主绝对路径，
            // 直接回显会把宿主路径泄露给 AI。
            return mapper.hostTargetToContainer(Files.readSymbolicLink(f.toPath()).toString());
        } catch (Exception e) {
            return "?";
        }
    }
}
