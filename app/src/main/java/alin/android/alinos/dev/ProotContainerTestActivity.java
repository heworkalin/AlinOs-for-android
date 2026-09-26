package alin.android.alinos.dev;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import alin.android.alinos.R;
import alin.android.alinos.localshell.LocalShellExecutor;

import org.json.JSONObject;

/**
 * Proot 容器部署测试界面（下载 + 解压）。
 *
 * <p>发行版固定为 <b>Ubuntu 24.04 (noble)</b>，架构随设备自动选择。
 *
 * <p>关于镜像源：
 * <ul>
 *   <li>保留完整源列表，支持切换；默认「自动」会依次尝试全部源。</li>
 *   <li>某些源在特定地区/网络会被临时封禁，故不做删减，由用户自行切换。</li>
 *   <li>所有请求带浏览器 User-Agent（部分站点必须有 UA 才响应）。</li>
 *   <li><b>不能只看 HTTP 200</b>：部分源对不存在的文件返回伪 200 的 HTML 错误页，
 *       因此下载后必须校验 xz 文件头（{@code FD 37 7A 58 5A 00}）。</li>
 * </ul>
 *
 * <p>本页仅用于测试，不启动 proot，也不注册为 AI 工具。
 */
public class ProotContainerTestActivity extends AppCompatActivity {

    private static final String DISTRO = "ubuntu";
    private static final String CODENAME = "noble";

    /** 浏览器 UA —— 部分镜像站无 UA 会拒绝访问。 */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 日期条目：兼容 href 里的 %3A 编码与 title 里的明文冒号。 */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(20\\d{6}_\\d{2}(?:%3A|:)\\d{2})");

    /** xz 文件魔数。 */
    private static final byte[] XZ_MAGIC = {(byte) 0xFD, '7', 'z', 'X', 'Z', 0x00};

    /** 镜像源定义。%s = 架构（arm64 / armhf / amd64 / i386）。 */
    private static final class Mirror {
        final String name;
        final String baseTpl;

        Mirror(String name, String baseTpl) {
            this.name = name;
            this.baseTpl = baseTpl;
        }

        String base(String arch) {
            return String.format(Locale.US, baseTpl, arch);
        }
    }

    /**
     * 完整镜像源列表（顺序无关，自动模式会全部尝试）。
     * 各站在不同地区可用性不同，故全部保留、不按单次测试结果裁剪。
     */
    private static final Mirror[] MIRRORS = {
            new Mirror("官方 images.linuxcontainers.org",
                    "https://images.linuxcontainers.org/images/ubuntu/noble/%s/default/"),
            new Mirror("南大 NJU",
                    "https://mirrors.nju.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("清华 TUNA",
                    "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("北外 BFSU",
                    "https://mirrors.bfsu.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("中科院 ISCAS",
                    "https://mirror.iscas.ac.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("中科大 USTC",
                    "https://mirrors.ustc.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("华为云",
                    "https://mirrors.huaweicloud.com/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("腾讯云",
                    "https://mirrors.cloud.tencent.com/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("阿里云",
                    "https://mirrors.aliyun.com/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("兰大 LZU",
                    "https://mirror.lzu.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("南方科大 SUSTech",
                    "https://mirrors.sustech.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
            new Mirror("北大 PKU",
                    "https://mirrors.pku.edu.cn/lxc-images/images/ubuntu/noble/%s/default/"),
    };

    private TextView tvTarget;
    private TextView tvLog;
    private ScrollView svLog;
    private EditText etDate;
    private Spinner spSource;
    private ProgressBar pbProgress;
    private Button btnFetchDate;
    private Button btnDownload;
    private Button btnExtract;
    private Button btnAll;
    private Button btnInit;
    private Button btnStart;

    private volatile boolean busy = false;

    private final String arch = detectArch();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proot_container_test);

        tvTarget = findViewById(R.id.tv_target);
        tvLog = findViewById(R.id.tv_log);
        svLog = findViewById(R.id.sv_log);
        etDate = findViewById(R.id.et_date);
        spSource = findViewById(R.id.sp_source);
        pbProgress = findViewById(R.id.pb_progress);
        btnFetchDate = findViewById(R.id.btn_fetch_date);
        btnDownload = findViewById(R.id.btn_download);
        btnExtract = findViewById(R.id.btn_extract);
        btnAll = findViewById(R.id.btn_all);
        btnInit = findViewById(R.id.btn_init);
        btnStart = findViewById(R.id.btn_start);

        tvLog.setTextIsSelectable(true);

        List<String> names = new ArrayList<>();
        names.add("自动（依次尝试全部）");
        for (Mirror m : MIRRORS) names.add(m.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSource.setAdapter(adapter);

        tvTarget.setText("发行版: Ubuntu 24.04 (noble)"
                + "\n架构: " + arch
                + "\n容器目录: " + containerDir().getAbsolutePath()
                + "\n下载缓存: " + archiveFile().getAbsolutePath());

        btnFetchDate.setOnClickListener(v -> runAsync("获取日期", this::fetchDateTask));
        btnDownload.setOnClickListener(v -> runAsync("下载", () -> {
            String d = etDate.getText().toString().trim();
            deploy(d.isEmpty() ? null : d);
        }));
        btnExtract.setOnClickListener(v -> runAsync("解压", this::extractTask));
        btnAll.setOnClickListener(v -> runAsync("一键部署", () -> {
            String d = etDate.getText().toString().trim();
            if (deploy(d.isEmpty() ? null : d)) extractTask();
        }));
        btnInit.setOnClickListener(v -> runAsync("初始化", this::initContainerTask));
        btnStart.setOnClickListener(v -> runAsync("启动", this::startProotTask));

        log("就绪：Ubuntu 24.04 (noble) / " + arch);
        log("共 " + MIRRORS.length + " 个镜像源，默认自动依次尝试");
    }

    // ---------------------------------------------------------------------
    // 路径 / 架构
    // ---------------------------------------------------------------------

    private File containerDir() {
        return new File(getFilesDir(), "containers/proot/" + DISTRO + "-" + CODENAME + "_" + arch);
    }

    private File downloadDir() {
        return new File(getFilesDir(), "containers/_rootfs");
    }

    private File archiveFile() {
        return new File(downloadDir(), DISTRO + "-" + CODENAME + "_" + arch + "-rootfs.tar.xz");
    }

    private static String detectArch() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.startsWith("armeabi")) return "armhf";
        if (abi.startsWith("x86_64")) return "amd64";
        if (abi.startsWith("x86")) return "i386";
        return "arm64";
    }

    /** 当前生效的源列表：选「自动」= 全部；否则只取选中的那一个。 */
    private List<Mirror> activeSources() {
        int pos = spSource.getSelectedItemPosition();
        if (pos <= 0) return Arrays.asList(MIRRORS);
        return Collections.singletonList(MIRRORS[pos - 1]);
    }

    // ---------------------------------------------------------------------
    // 任务调度
    // ---------------------------------------------------------------------

    private void runAsync(String tag, Runnable task) {
        if (busy) {
            log("[" + tag + "] 已有任务在执行，请等待完成");
            return;
        }
        busy = true;
        setButtonsEnabled(false);
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log("[" + tag + "] 异常: " + e);
            } finally {
                busy = false;
                setButtonsEnabled(true);
            }
        }, tag).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            btnFetchDate.setEnabled(enabled);
            btnDownload.setEnabled(enabled);
            btnExtract.setEnabled(enabled);
            btnAll.setEnabled(enabled);
            btnInit.setEnabled(enabled);
            btnStart.setEnabled(enabled);
            spSource.setEnabled(enabled);
        });
    }

    // ---------------------------------------------------------------------
    // 日期解析
    // ---------------------------------------------------------------------

    private void fetchDateTask() {
        String date = resolveDate();
        if (date == null) {
            log("获取日期失败，请手动填写（如 20260926_07:42）");
            return;
        }
        runOnUiThread(() -> etDate.setText(date));
        log("采用日期: " + date);
    }

    /**
     * 读取目录页，返回该源上真实存在且可下载的日期。
     * 日期一律来自对方目录页列出的目录名，<b>绝不拼接当前时间</b>。
     */
    private String resolveDate() {
        for (Mirror m : activeSources()) {
            log("读取目录 [" + m.name + "]");
            List<String> ds = parseDates(httpGetString(m.base(arch)));
            if (ds.isEmpty()) {
                log("  → 无日期（被封禁或为动态页面）");
                continue;
            }
            int from = Math.max(0, ds.size() - 3);
            for (int i = ds.size() - 1; i >= from; i--) {
                String date = ds.get(i);
                if (probeXz(m.base(arch) + date + "/rootfs.tar.xz", m.name)) return date;
            }
            return ds.get(ds.size() - 1);
        }
        return null;
    }

    private List<String> parseDates(String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = DATE_PATTERN.matcher(html);
        while (m.find()) {
            String d = m.group(1).replace("%3A", ":");
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 下载
    // ---------------------------------------------------------------------

    private boolean deploy(String manualDate) {
        downloadDir().mkdirs();
        File dst = archiveFile();
        File part = new File(dst.getAbsolutePath() + ".part");
        List<Mirror> sources = activeSources();

        for (Mirror m : sources) {
            // 日期来自该源目录页真实列出的目录名，不拼接当前时间。
            List<String> dates = new ArrayList<>();
            if (manualDate != null && !manualDate.isEmpty()) {
                dates.add(manualDate);
            } else {
                log("读取目录 [" + m.name + "]");
                List<String> ds = parseDates(httpGetString(m.base(arch)));
                if (ds.isEmpty()) {
                    log("  → 无日期（被封禁或为动态页面），换源");
                    continue;
                }
                int from = Math.max(0, ds.size() - 5);
                for (int i = ds.size() - 1; i >= from; i--) dates.add(ds.get(i));
                log("  → 候选日期: " + dates);
            }

            for (String date : dates) {
                String url = m.base(arch) + date + "/rootfs.tar.xz";
                log("尝试 [" + m.name + "] " + date);
                updateProgress(0);

                if (!downloadTo(url, part)) {
                    log("  → 下载失败");
                    continue;
                }
                if (!isXzFile(part)) {
                    log("  → 非 xz（伪 200 错误页），丢弃");
                    part.delete();
                    continue;
                }

                // 哈希校验：优先比对官方 SHA256SUMS
                String actualSha = computeSha256(part);
                String expectedSha = fetchExpectedSha256(m, date);
                if (expectedSha != null) {
                    if (!expectedSha.equals(actualSha)) {
                        log("  → SHA-256 不匹配，丢弃");
                        log("      期望 " + expectedSha);
                        log("      实际 " + actualSha);
                        part.delete();
                        continue;
                    }
                    log("  ✓ SHA-256 校验通过（官方 SHA256SUMS）");
                } else {
                    log("  ⚠ 该源无 SHA256SUMS，仅记录本地哈希");
                }

                if (dst.exists()) dst.delete();
                if (!part.renameTo(dst)) {
                    log("  → 重命名失败: " + part + " → " + dst);
                    return false;
                }
                if (actualSha != null) writeSha256Stamp(dst, actualSha);
                log("下载完成 ✓ [" + m.name + "] " + date + "  " + dst.length() + " 字节");
                return true;
            }
        }
        log("下载失败：已尝试 " + sources.size() + " 个源");
        return false;
    }

    private boolean downloadTo(String urlStr, File dst) {
        HttpURLConnection conn = null;
        try {
            long existing = dst.exists() ? dst.length() : 0;
            conn = openConnection(urlStr);
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = conn.getResponseCode();
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                existing = 0;
            } else {
                log("  → HTTP " + code);
                return false;
            }

            long len = conn.getContentLength();
            long total = len > 0 ? existing + len : -1;
            long expectedTotal = total;

            byte[] buf = new byte[64 * 1024];
            long done = existing;
            long lastReport = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dst, append)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 500) {
                        lastReport = now;
                        reportDownload(done, expectedTotal);
                    }
                }
            }
            reportDownload(done, expectedTotal);
            return expectedTotal < 0 || done >= expectedTotal;
        } catch (Exception e) {
            log("  → 下载异常: " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void reportDownload(long done, long total) {
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            updateProgress(pct);
            log(String.format(Locale.US, "  %d%%  %d / %d 字节", pct, done, total));
        } else {
            log("  已下载 " + done + " 字节");
        }
    }

    // ---------------------------------------------------------------------
    // 解压
    // ---------------------------------------------------------------------

    private void extractTask() {
        File archive = archiveFile();
        if (!archive.exists() || archive.length() == 0) {
            log("镜像不存在，请先下载: " + archive);
            return;
        }
        if (!isXzFile(archive)) {
            log("镜像文件不是 xz 格式，可能下载到了错误页，请重新下载");
            return;
        }
        // 固化校验：若存在下载时记录的哈希，重新核对一次
        if (shaFile().exists()) {
            String expectedSha = readStamp();
            String actualSha = computeSha256(archive);
            if (expectedSha != null && !expectedSha.equals(actualSha)) {
                log("固化校验失败：镜像与记录哈希不符，可能损坏，请重新下载");
                return;
            }
            log("固化校验通过 ✓ " + expectedSha);
        }

        File dest = containerDir();
        dest.mkdirs();

        log("解压: " + archive.getName() + "  →  " + dest.getAbsolutePath());
        updateProgress(0);

        // 参考 tmoe(Termux)：临时套一层 proot --link2symlink 调 tar 解压，
        // 把硬链接降级为符号链接，规避 Android 不允许创建硬链接的限制。
        if (!extractWithProot(archive, dest)) {
            log("proot 方式不可用，回退到 libtar.so 直接解压");
            extractWithLibtar(archive, dest);
        }

        File osRelease = new File(dest, "etc/os-release");
        if (osRelease.exists()) {
            updateProgress(100);
            log("解压完成 ✓ " + dest.getAbsolutePath());
        } else {
            log("解压可能不完整：未找到 etc/os-release");
        }
    }

    /**
     * 参考 tmoe 的解压方式：用 bootstrap 内的 proot 套一层调用 tar：
     * <pre>proot --link2symlink tar -xJf archive -C dest</pre>
     * proot 会把 hard link 转成 symlink，避免 Android 的 link() 权限问题。
     */
    private boolean extractWithProot(File archive, File dest) {
        File prefix = new File(getFilesDir(), "default");
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);

        // proot 伪装成 .so 放在 nativeLibraryDir —— Android 官方允许执行的位置。
        File proot = new File(nativeDir, "libproot.so");
        if (!proot.exists()) proot = new File(prefix, "bin/proot");

        // proot 内部会对目标程序执行真正的 execve，私有目录会被拒；
        // libtar.so 位于 nativeLibraryDir，所以优先作为 proot 的执行目标。
        File tarTarget = new File(nativeDir, "libtar.so");
        if (!tarTarget.exists()) tarTarget = new File(prefix, "bin/tar");

        if (!proot.exists() || !tarTarget.exists()) {
            log("proot/tar 不可用: proot=" + proot + ", tar=" + tarTarget);
            return false;
        }

        // 新版 proot 已静态链接 talloc，不再需要 libtalloc.so.2。
        // loader 随 proot 一起放进 nativeLibraryDir（伪装成 libproot-loader.so）；
        // 旧版 proot 的 loader 在私有目录 libexec/proot/loader，作为回退。
        File loader = new File(nativeDir, "libproot-loader.so");
        if (!loader.exists()) loader = new File(prefix, "libexec/proot/loader");
        File tmp = new File(getCacheDir(), "proot_tmp");
        File l2s = new File(getCacheDir(), "proot_l2s");
        tmp.mkdirs();
        l2s.mkdirs();

        // 参考 libtar.so 的执行方式：直接用 Runtime.exec 执行 nativeLibraryDir 里的
        // ELF（Android 允许该目录执行），不套 shell、不套执行器。
        // 参考 tmoe：proot --link2symlink <tar> -xJf <archive> -C <dest>
        // 目标程序用 nativeLibraryDir 里的 libtar.so —— proot 内部会对它做真正的
        // execve，而私有目录会被 Permission denied 拒绝，nativeLibraryDir 才是
        // Android 允许执行的位置（与 libproot.so 同理）。
        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add(tarTarget.getAbsolutePath());
        cmd.add("-xJf");
        cmd.add(archive.getAbsolutePath());
        cmd.add("-C");
        cmd.add(dest.getAbsolutePath());
       

        java.util.List<String> env = new java.util.ArrayList<>();
        env.add("PROOT_TMP_DIR=" + tmp.getAbsolutePath());
        env.add("PROOT_L2S_DIR=" + l2s.getAbsolutePath());
        if (loader.exists()) env.add("PROOT_LOADER=" + loader.getAbsolutePath());

        log("proot 命令: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(
                    cmd.toArray(new String[0]),
                    env.toArray(new String[0]),
                    dest);

            final StringBuilder out = new StringBuilder();
            Thread tOut = new Thread(() -> drain(p.getInputStream(), out));
            Thread tErr = new Thread(() -> drain(p.getErrorStream(), out));
            tOut.start();
            tErr.start();

            int exit = p.waitFor();
            try { tOut.join(1500); } catch (InterruptedException ignored) {}
            try { tErr.join(1500); } catch (InterruptedException ignored) {}
            log("proot 退出码: " + exit);
            String text = out.toString().trim();
            if (!text.isEmpty()) log("proot 输出:\n" + clip(text));
            return new File(dest, "etc/os-release").exists();
        } catch (Exception e) {
            log("proot 解压异常: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // 容器初始化（全部硬编码，无需联网）
    // ---------------------------------------------------------------------

    /**
     * 最小化初始化。LXC 官方 rootfs 解压后只缺这几样，全部在本地生成：
     * <ol>
     *   <li>/etc/resolv.conf —— LXC 原样是指向 systemd stub 的坏 symlink，换成真实 DNS；</li>
     *   <li>/etc/hostname —— LXC 原样是占位符 LXC_NAME；</li>
     *   <li>/etc/hosts —— 把 127.0.1.1 LXC_NAME 换成 localhost；</li>
     *   <li>补齐 /dev /proc /sys /run /dev/shm 等目录；</li>
     *   <li>解包 assets 里的伪造 /proc（proot_proc.tar.xz）并补运行时数据。</li>
     * </ol>
     */
    private void initContainerTask() {
        File rootfs = containerDir();
        if (!new File(rootfs, "etc/os-release").exists()) {
            log("容器尚未解压，请先执行 ① 下载 + ② 解压");
            return;
        }
        log("=== 初始化容器（硬编码，无需联网）===");

        // 1. resolv.conf：LXC 原样是指向 systemd stub 的坏 symlink，必须换成真实文件。
        //    注意：目标是悬空 symlink，File.exists() 会返回 false，所以不能靠它判断，
        //    必须无条件 delete()（删除的是链接本身，不会跟随目标）。
        File resolv = new File(rootfs, "etc/resolv.conf");
        if (!resolv.delete() && resolv.exists()) {
            log("删除旧 /etc/resolv.conf 失败");
        } else {
            log("已移除 LXC 的悬空 resolv.conf symlink");
        }
        writeText(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 223.5.5.5\n");
        log("写入 /etc/resolv.conf");

        // 2. hostname
        writeText(new File(rootfs, "etc/hostname"), "localhost\n");
        log("写入 /etc/hostname = localhost");

        // 3. hosts
        writeText(new File(rootfs, "etc/hosts"),
                "127.0.0.1\tlocalhost\n"
                        + "::1\t\tlocalhost ip6-localhost ip6-loopback\n"
                        + "fe00::0\t\tip6-localnet\n"
                        + "ff00::0\t\tip6-mcastprefix\n"
                        + "ff02::1\t\tip6-allnodes\n"
                        + "ff02::2\t\tip6-allrouters\n");
        log("写入 /etc/hosts");

        // 4. 目录
        for (String d : new String[]{"dev", "dev/shm", "dev/pts", "proc", "sys", "run", "tmp", "root"}) {
            new File(rootfs, d).mkdirs();
        }
        log("补齐 /dev /proc /sys /run /dev/shm 等目录");

        // 5. 伪造 /proc
        unpackProotProc(rootfs);
        log("初始化完成 ✓");
    }

    /** 把 assets 里的 proot_proc.tar.xz 解包到 rootfs，并补上从宿主 /proc 读到的动态数据。 */
    private void unpackProotProc(File rootfs) {
        File tmp = new File(getFilesDir(), "proot_proc.tar.xz");
        try (InputStream in = getAssets().open("proot_proc.tar.xz");
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            log("释放 proot_proc.tar.xz 失败: " + e);
            return;
        }

        File libTar = new File(getApplicationInfo().nativeLibraryDir, "libtar.so");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    libTar.getAbsolutePath(), "-xJf",
                    tmp.getAbsolutePath(), "-C", rootfs.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), new StringBuilder());
            int exit = p.waitFor();
            log("解包伪造 /proc 完成，退出码 " + exit);
        } catch (Exception e) {
            log("解包伪造 /proc 异常: " + e);
        }

        // 动态数据：从宿主 /proc 读出来写到 .tmoe-container.*（tmoe install 就是这么干的）
        File procDir = new File(rootfs, "usr/local/etc/tmoe-linux/proot_proc");
        procDir.mkdirs();
        String[] dynamic = {"stat", "version", "cpuinfo", "meminfo", "uptime", "loadavg", "mounts"};
        int ok = 0;
        for (String name : dynamic) {
            File host = new File("/proc/" + name);
            if (!host.canRead()) continue;
            try {
                writeBytes(new File(procDir, ".tmoe-container." + name), readAll(host));
                ok++;
            } catch (Exception ignored) {
            }
        }
        log("动态 /proc 数据生成 " + ok + "/" + dynamic.length + " 项");
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private void writeText(File f, String text) {
        writeBytes(f, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void writeBytes(File f, byte[] data) {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception e) {
            log("写入失败 " + f + ": " + e);
        }
    }

    // ---------------------------------------------------------------------
    // 启动容器（proot）
    // ---------------------------------------------------------------------

    /** 拼接 proot 启动命令并执行一次测试命令（/proc 默认走伪造）。 */
    private void startProotTask() {
        File rootfs = containerDir();
        if (!new File(rootfs, "etc/os-release").exists()) {
            log("容器尚未解压，请先执行 ① 下载 + ② 解压");
            return;
        }
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libproot-loader.so");
        File tmp = new File(getCacheDir(), "proot_tmp");
        File l2s = new File(getCacheDir(), "proot_l2s");
        tmp.mkdirs();
        l2s.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--root-id");
        cmd.add("--link2symlink");
        cmd.add("--kill-on-exit");
        cmd.add("--pwd=/root");
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());

        // 伪造 /proc：把 proot_proc 里的每个文件挂到 /proc/<name>
        File procDir = new File(rootfs, "usr/local/etc/tmoe-linux/proot_proc");
        int mounts = 0;
        File[] files = procDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (name.startsWith(".tmoe-container.")) {
                    name = name.substring(".tmoe-container.".length());
                }
                cmd.add("-b");
                cmd.add(f.getAbsolutePath() + ":/proc/" + name);
                mounts++;
            }
        }
        log("伪造 /proc 挂载项: " + mounts + " 个");

        // env -i：清空环境，只给容器必需变量
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("USER=root");
        cmd.add("HOSTNAME=localhost");
        cmd.add("TERM=xterm-256color");
        cmd.add("TMPDIR=/tmp");
        cmd.add("LANG=C.UTF-8");
        cmd.add("SHELL=/bin/bash");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add("uname -a; echo ---; id; echo ---; cat /etc/os-release; echo ---; ls /; echo PROOT_START_OK");

        List<String> env = new ArrayList<>();
        if (loader.exists()) env.add("PROOT_LOADER=" + loader.getAbsolutePath());
        env.add("PROOT_TMP_DIR=" + tmp.getAbsolutePath());
        env.add("PROOT_L2S_DIR=" + l2s.getAbsolutePath());

        log("启动命令: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(cmd.toArray(new String[0]),
                    env.toArray(new String[0]), rootfs);
            StringBuilder sb = new StringBuilder();
            Thread tOut = new Thread(() -> drain(p.getInputStream(), sb));
            Thread tErr = new Thread(() -> drain(p.getErrorStream(), sb));
            tOut.start();
            tErr.start();
            int exit = p.waitFor();
            try { tOut.join(1500); } catch (InterruptedException ignored) {}
            try { tErr.join(1500); } catch (InterruptedException ignored) {}
            log("启动退出码: " + exit);
            String text = sb.toString().trim();
            if (!text.isEmpty()) log("输出:\n" + clip(text));
        } catch (Exception e) {
            log("启动异常: " + e);
        }
    }

    /** 读取子进程输出到 StringBuilder。 */
    private static void drain(InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sb) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 单引号软封装，供 shell 命令使用。 */
    private static String shq(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String clip(String s) {
        s = s.trim();
        return s.length() <= 600 ? s : s.substring(0, 600) + " …(" + s.length() + " 字节)";
    }

    /** 回退方案：直接用 libtar.so 解压，再修复硬链接。 */
    private void extractWithLibtar(File archive, File dest) {
        File libTar = new File(getApplicationInfo().nativeLibraryDir, "libtar.so");
        if (!libTar.exists()) {
            log("libtar.so 不存在: " + libTar);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    libTar.getAbsolutePath(),
                    "-xJf", archive.getAbsolutePath(),
                    "-C", dest.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int shown = 0;
                while ((line = r.readLine()) != null) {
                    if (shown++ < 60) log("tar: " + line);
                }
            }

            int exit = p.waitFor();
            log("tar 退出码: " + exit);

            // Android app 数据目录不允许创建硬链接（SELinux 拒绝 link），
            // 而 LXC rootfs 含硬链接（perl / gunzip 等），这里逐个补齐。
            if (exit != 0) {
                repairHardLinks(archive, dest);
            }
        } catch (Exception e) {
            log("解压异常: " + e);
        }
    }

    /**
     * 修复因 Android 不允许硬链接而缺失的文件。
     *
     * <p>原理：用 {@code tar -tvJf} 列出归档，找出所有 hard link 条目
     * （形如 {@code hrw-r--r-- ... name link to target}），
     * 逐个尝试 {@code Files.createLink}；若硬链接仍被拒绝，则退化为文件复制。
     * 这样无需启动 proot，也不依赖 {@code proot --link2symlink}。
     */
    private void repairHardLinks(File archive, File dest) {
        File libTar = new File(getApplicationInfo().nativeLibraryDir, "libtar.so");
        if (!libTar.exists()) {
            log("硬链接修复跳过：libtar.so 不存在");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    libTar.getAbsolutePath(), "-tvJf", archive.getAbsolutePath());
            // tar 输出会随 locale 变化（中文环境下 hard link 显示为「连接到」），
            // 固定为 C locale，保证解析稳定。
            pb.environment().put("LC_ALL", "C");
            Process p = pb.start();

            int fixed = 0;
            int failed = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int idx = line.indexOf(" link to ");
                    int sepLen = " link to ".length();
                    if (idx < 0) {
                        // 兼容中文 locale 的 tar 输出「连接到」
                        idx = line.indexOf(" 连接到 ");
                        sepLen = " 连接到 ".length();
                    }
                    if (idx < 0) continue;
                    String left = line.substring(0, idx).trim();
                    String target = line.substring(idx + sepLen).trim();
                    String[] cols = left.split("\\s+");
                    if (cols.length == 0) continue;

                    String linkPath = stripDot(cols[cols.length - 1]);
                    String targetPath = stripDot(target);
                    if (linkPath.isEmpty() || targetPath.isEmpty()) continue;

                    File linkFile = new File(dest, linkPath);
                    File targetFile = new File(dest, targetPath);
                    if (!targetFile.exists() || linkFile.exists()) continue;

                    File parent = linkFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try {
                        java.nio.file.Files.deleteIfExists(linkFile.toPath());
                        java.nio.file.Files.createLink(linkFile.toPath(), targetFile.toPath());
                        fixed++;
                    } catch (Exception linkErr) {
                        try {
                            java.nio.file.Files.copy(targetFile.toPath(), linkFile.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            linkFile.setExecutable(targetFile.canExecute(), false);
                            fixed++;
                        } catch (Exception copyErr) {
                            failed++;
                            if (failed <= 5) log("  ✗ 修复失败: " + linkPath + " ← " + targetPath);
                        }
                    }
                }
            }
            p.waitFor();
            log("硬链接修复: 成功 " + fixed + " 项" + (failed > 0 ? "，失败 " + failed + " 项" : ""));
        } catch (Exception e) {
            log("硬链接修复异常: " + e);
        }
    }

    private static String stripDot(String path) {
        return path.startsWith("./") ? path.substring(2) : path;
    }

    // ---------------------------------------------------------------------
    // 网络 / 校验工具
    // ---------------------------------------------------------------------

    private File shaFile() {
        return new File(archiveFile().getAbsolutePath() + ".sha256");
    }

    /** 从同目录 SHA256SUMS 解析 rootfs.tar.xz 的期望哈希；无则返回 null。 */
    private String fetchExpectedSha256(Mirror m, String date) {
        String text = httpGetString(m.base(arch) + date + "/SHA256SUMS");
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] cols = s.split("\\s+");
            if (cols.length >= 2 && cols[cols.length - 1].equals("rootfs.tar.xz")) {
                return cols[0].toLowerCase(Locale.US);
            }
        }
        return null;
    }

    /** 流式计算文件 SHA-256；失败返回 null。 */
    private String computeSha256(File f) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入固化记录：{@code <sha256>  <文件名>}。 */
    private void writeSha256Stamp(File archive, String sha) {
        try (FileOutputStream out = new FileOutputStream(shaFile())) {
            out.write((sha + "  " + archive.getName() + "\n").getBytes("UTF-8"));
        } catch (Exception e) {
            log("  ⚠ 固化记录写入失败: " + e);
        }
    }

    /** 读取固化记录里的哈希；无则返回 null。 */
    private String readStamp() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(shaFile())))) {
            String line = r.readLine();
            if (line == null) return null;
            String[] cols = line.trim().split("\\s+");
            return cols.length >= 1 ? cols[0].toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private String httpGetString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlStr);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < 256 * 1024) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    total += n;
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 只读前 6 字节判断是否真实 xz（不下载整文件）。 */
    private boolean probeXz(String url, String srcName) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = conn.getInputStream()) {
                byte[] head = new byte[6];
                int n = readFully(in, head);
                if (n == 6 && isXz(head)) {
                    log("  ✓ " + srcName + " 有真实文件");
                    return true;
                }
                log("  ✗ " + srcName + " 非 xz（伪 200 错误页）");
                return false;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean isXzFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[6];
            int n = readFully(in, h);
            return n == 6 && isXz(h);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isXz(byte[] h) {
        if (h.length < XZ_MAGIC.length) return false;
        for (int i = 0; i < XZ_MAGIC.length; i++) {
            if (h[i] != XZ_MAGIC[i]) return false;
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private void updateProgress(int pct) {
        int v = Math.max(0, Math.min(100, pct));
        runOnUiThread(() -> pbProgress.setProgress(v));
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            boolean atBottom = isLogAtBottom();
            tvLog.append(msg + "\n");
            // 粘性到底：只有用户本来就停在底部时才自动跟随；
            // 用户手动上翻查看历史时不会被强行拉回。
            if (atBottom) {
                svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /** 用户当前是否停在日志底部（留 2dp 容差）。 */
    private boolean isLogAtBottom() {
        if (svLog == null || tvLog == null) return true;
        int diff = tvLog.getBottom() - (svLog.getScrollY() + svLog.getHeight());
        return diff <= dp(2);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
