package alin.android.alinos.tools;

import android.content.Context;
import android.util.Log;

import java.io.*;
import java.security.MessageDigest;

public class AmApkInstaller {

    private static final String TAG = "AmApkInstaller";
    private static final String ASSET_APK_NAME = "TermuxAm-debug.apk";
    // 开发备注：ColorOS 平台 am 调用特殊限制，由于作者的设备就是这个导致排查了时间非常长最后发现居然是这个问题，特此备注：
    // 1. 普通发行版用户无任何影响，默认非调试环境，am 调用完全正常；
    // 2. 开发者调试时：若当前应用被系统标记为【调试应用】，ColorOS 底层防护机制会拦截 am 组件调用、篡改环境解析，直接导致进程异常崩溃；
    // 3. 核心根因：与 exec 劫持、LD_PRELOAD 无关，仅受系统调试模式管控；
    // 4. 开发调试必做：开发者选项 → 选择调试应用，取消本应用调试标记（设为「无」），am 即可正常调用。
    // 目标路径，的目的只是去修复AM，核心原因是因为原版内置的和我们的应用包名不一致，导致AM无法正常工作
    // 这个虽然是工具，但是请注意这个。只是用于修复am的不需要把它做到tools搜索服务中
    public static String getTargetPath(Context ctx) {
        return ctx.getFilesDir().getAbsolutePath()
                + "/usr/libexec/termux-am/am.apk";
    }

    // 检查+释放，每次APP启动都调用
    public static void installIfNeeded(Context ctx) {
        try {
            File targetFile = new File(getTargetPath(ctx));
            File parentDir = targetFile.getParentFile();
            if (!parentDir.exists()) parentDir.mkdirs();

            // 获取 assets 里的 MD5
            String assetMd5 = getAssetMd5(ctx, ASSET_APK_NAME);

            // 如果文件存在，比对MD5
            boolean needInstall = true;
            if (targetFile.exists()) {
                String fileMd5 = getFileMd5(targetFile);
                if (assetMd5.equals(fileMd5)) {
                    needInstall = false;
                }
            }

            if (needInstall) {
                copyAssetToFile(ctx, ASSET_APK_NAME, targetFile);
                Log.d(TAG, "TermuxAm APK 已安装/更新");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 从 assets 复制到目标文件
    private static void copyAssetToFile(Context ctx, String assetName, File outFile) throws IOException {
        try (InputStream in = ctx.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    // MD5工具
    private static String getFileMd5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                md.update(buf, 0, len);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String getAssetMd5(Context ctx, String assetName) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = ctx.getAssets().open(assetName)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                md.update(buf, 0, len);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
