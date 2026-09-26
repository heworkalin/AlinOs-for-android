package alin.android.alinos.proot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器内路径 → 宿主真实路径 的映射器。
 *
 * <p><b>核心原则：AI 永远看不到 Android 宿主路径。</b>
 * AI 给出的所有路径都被当作「容器内路径」，由本类完成三级转换：
 * <pre>
 *   AI 输入 "etc/hosts" / "~/a.txt" / "/usr/bin/perl"
 *     ↓ ① 补全：相对路径以 cwd（默认 /root）为基准，~ 展开为 /root
 *   容器绝对 "/etc/hosts" / "/root/a.txt" / "/usr/bin/perl"
 *     ↓ ② 容器语义解析 symlink（逐级、最多 64 层，写穿链接）
 *   容器真实 "/etc/hosts" / "/root/a.txt" / "/usr/bin/perl5.38.2"
 *     ↓ ③ 拼接 rootfs
 *   宿主文件 &lt;rootfs&gt;/etc/hosts 等
 * </pre>
 *
 * <p><b>为什么不直接用 Java 的 {@link File#getCanonicalPath()}：</b>
 * rootfs 内大量符号链接是 {@code proot --link2symlink} 产生的<b>绝对链接</b>，
 * 如 {@code /usr/bin/perl -> /usr/bin/perl5.38.2}。在容器语义下它指向
 * {@code <rootfs>/usr/bin/perl5.38.2}，但 Java 会解析成宿主的
 * {@code /usr/bin/perl5.38.2}（Android 系统目录！）。因此必须自己按容器语义解析。
 *
 * <p><b>写穿链接（write-through）：</b>遇到符号链接时<b>不删除链接</b>，而是解析到
 * 其指向的真实文件，直接修改真实文件；调用方可通过 {@link Resolved#linkWarning()}
 * 获得「该路径是链接」的警告回显给 AI。
 */
public final class ProotPathMapper {

    /** symlink 解析深度上限（防环）。 */
    private static final int MAX_SYMLINK_DEPTH = 64;

    /** 容器内默认工作目录。 */
    public static final String DEFAULT_CWD = "/root";

    private final File rootfs;

    public ProotPathMapper(File rootfs) {
        this.rootfs = rootfs;
    }

    public File rootfs() {
        return rootfs;
    }

    /** 一次路径解析的结果。 */
    public static final class Resolved {
        /** AI 原始输入。 */
        public final String input;
        /** 补全后的容器绝对路径（symlink 解析前）。 */
        public final String containerPath;
        /** 解析 symlink 后的容器真实路径。 */
        public final String realContainerPath;
        /** 宿主文件。 */
        public final File hostFile;
        /** 是否经过符号链接。 */
        public final boolean viaSymlink;

        Resolved(String input, String containerPath, String realContainerPath,
                 File hostFile, boolean viaSymlink) {
            this.input = input;
            this.containerPath = containerPath;
            this.realContainerPath = realContainerPath;
            this.hostFile = hostFile;
            this.viaSymlink = viaSymlink;
        }

        /** 供工具回显的链接警告；非链接时返回 {@code null}。 */
        public String linkWarning() {
            if (!viaSymlink) return null;
            return "Note: " + containerPath + " is a symbolic link; the operation was applied to its "
                    + "target " + realContainerPath + " (the link itself is unchanged)";
        }
    }

    // ---------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------

    /** 以容器默认工作目录 {@value #DEFAULT_CWD} 为基准解析。 */
    public Resolved resolve(String input) throws IOException {
        return resolve(input, DEFAULT_CWD);
    }

    /**
     * 解析 AI 给定的容器路径。
     *
     * @param input AI 输入（相对/绝对/~/空）
     * @param cwd   相对路径的基准容器目录
     */
    public Resolved resolve(String input, String cwd) throws IOException {
        String abs = toContainerAbsolute(input, cwd);
        String real = resolveSymlinks(abs);
        File host = hostFile(real);
        return new Resolved(input, abs, real, host, !real.equals(abs));
    }

    /** 解析出宿主文件（不保证存在）。 */
    public File hostFile(String containerPath) {
        String p = containerPath.startsWith("/") ? containerPath.substring(1) : containerPath;
        return p.isEmpty() ? rootfs : new File(rootfs, p);
    }

    // ---------------------------------------------------------------------
    // ① 补全
    // ---------------------------------------------------------------------

    /** 把 AI 输入补全为容器内绝对路径。 */
    public String toContainerAbsolute(String input, String cwd) {
        String p = input == null ? "" : input.trim();
        if (p.isEmpty() || p.equals("~")) {
            p = "/root";
        } else if (p.startsWith("~/")) {
            p = "/root" + p.substring(1);
        } else if (!p.startsWith("/")) {
            String base = (cwd == null || cwd.trim().isEmpty()) ? DEFAULT_CWD : cwd.trim();
            if (!base.startsWith("/")) base = "/" + base;
            p = base + "/" + p;
        }
        return normalize(p);
    }

    /** 容器语义的路径规范化：处理 {@code .} / {@code ..}，且永不越过根。 */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        if (out.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append('/').append(s);
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // ② 容器语义 symlink 解析（写穿链接）
    // ---------------------------------------------------------------------

    /**
     * 逐级解析容器内符号链接，返回容器内的真实路径。
     * 绝对链接按「容器内绝对路径」处理（不会逃逸到宿主）。
     */
    public String resolveSymlinks(String absPath) throws IOException {
        String path = normalize(absPath);
        for (int guard = 0; guard < MAX_SYMLINK_DEPTH; guard++) {
            String[] parts = path.split("/");
            StringBuilder cur = new StringBuilder();
            boolean hit = false;

            for (int i = 1; i < parts.length; i++) {
                if (parts[i].isEmpty()) continue;
                cur.append('/').append(parts[i]);

                File host = hostFile(cur.toString());
                if (!Files.isSymbolicLink(host.toPath())) continue;

                String target = Files.readSymbolicLink(host.toPath()).toString();
                int slash = cur.lastIndexOf("/");
                String base = slash > 0 ? cur.substring(0, slash) : "";
                String resolved = target.startsWith("/")
                        ? normalize(target)
                        : normalize(base + "/" + target);

                StringBuilder rest = new StringBuilder();
                for (int j = i + 1; j < parts.length; j++) rest.append('/').append(parts[j]);

                path = normalize(resolved + rest);
                hit = true;
                break;
            }

            if (!hit) return path;
        }
        throw new IOException("too many levels of symbolic links (possible loop): " + absPath);
    }

    // ---------------------------------------------------------------------
    // ③ 展示：容器内路径（绝不回显宿主路径）
    // ---------------------------------------------------------------------

    /** 把宿主文件转回容器内路径，供工具回显。 */
    public String toContainerPath(File hostFile) {
        String root = rootfs.getAbsolutePath();
        String abs = hostFile.getAbsolutePath();
        if (abs.equals(root)) return "/";
        if (abs.startsWith(root + "/")) return abs.substring(root.length());
        return abs; // 理论上不会发生
    }
}
