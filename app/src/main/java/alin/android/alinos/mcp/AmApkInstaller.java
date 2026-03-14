package alin.android.alinos.mcp;

import android.content.Context;
import android.util.Log;

import java.io.*;
import java.security.MessageDigest;

public class AmApkInstaller {

    private static final String TAG = "AmApkInstaller";
    private static final String ASSET_APK_NAME = "TermuxAm-debug.apk";

    // 目标路径
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
