package alin.android.alinos.voice;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 语音模型下载管理器
 * 负责从 GitHub / HuggingFace 下载模型、解压到 assets/cache 目录
 * 
 * 下载地址来源：ASR_AND_TTS_TECHNICAL_SUMMARY.md
 * 支持代理：github 直连、gh-proxy、ghfast.top
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadManager";

    // ==================== 官方源（项目中的真实链接）====================

    /** sherpa-onnx 核心库 v1.13.2 */
    public static final String SHERPA_ONNX_AAR_URL = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar";
    public static final String SHERPA_ONNX_JAR_URL = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.jar";

    /** ASR 模型 */
    public static final String MODEL_PARAFORMER_ZH = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2";
    public static final String MODEL_PARAFORMER_TRILINGUAL = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2";
    public static final String MODEL_WHISPER_TINY = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2";
    public static final String MODEL_SENSE_VOICE = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2";

    /** KWS 模型 */
    // 注意：旧版 wenetspeech-3.3M-2024-01-01 模型包缺少 zipformer2 metadata，在 sherpa-onnx 1.13.5 下加载会 native 崩溃；
    // 两个入口统一使用兼容的 zh-en-3M-2025-12-20（中英文都支持）。
    public static final String MODEL_KWS_MOBILE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2";
    public static final String MODEL_KWS_ZH_EN =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2";

    /** 声纹模型（推荐：官方已加 metadata 的 campplus，sherpa-onnx 1.13.5 可直接加载）
     *  注意：原始 3D-Speaker 导出（如 huggingface 的 campplus_cn_en_common_200k.onnx）没有
     *  framework/output_dim 等 metadata，加载时 native 会 SIGSEGV 崩溃，必须用官方处理版。 */
    public static final String MODEL_SPEAKER_CAMPLUS =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx";

    /** VAD 静音检测模型 */
    public static final String MODEL_VAD_SILERO = 
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    /** TTS 模型 */
    public static final String MODEL_TTS_MELO =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2";
    public static final String MODEL_TTS_AISHELL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-aishell3.tar.bz2";
    public static final String MODEL_TTS_XIAOYA =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2";

    // ==================== 代理地址（按优先级排列）====================

    /** 
     * 代理列表，下载失败时自动尝试下一个
     * 用户也可以手动切换优先级
     */
    private static final String[] PROXY_LIST = {
            // gh-proxy.com（国内代理，速度较快）
            "https://gh-proxy.com/",
            // ghfast.top（备用代理）
            "https://ghfast.top/",
    };

    /** 当前使用的代理索引（0 = 无代理，1+ = 代理） */
    private int proxyIndex = 0;

    private Context context;
    private DownloadListener listener;
    private File downloadCacheDir;

    /**
     * 下载监听器（回调）
     */
    public interface DownloadListener {
        void onDownloadStarted(String modelName);
        void onDownloadProgress(String modelName, int progress, long downloaded, long total);
        void onDownloadSuccess(String modelName, File targetDir);
        void onDownloadFailed(String modelName, String errorMsg);
    }

    public ModelDownloadManager(Context context, DownloadListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.downloadCacheDir = new File(context.getDir("model_download_cache", Context.MODE_PRIVATE), "cache");
        downloadCacheDir.mkdirs();
    }

    // ==================== 公开下载方法 ====================

    /**
     * 下载 sherpa-onnx 核心库
     */
    public void downloadSherpaOnnx(boolean downloadAar, boolean downloadJar) {
        if (downloadAar) {
            downloadFile(SHERPA_ONNX_AAR_URL, "sherpa-onnx-1.13.2.aar", "sherpa");
        }
        if (downloadJar) {
            downloadFile(SHERPA_ONNX_JAR_URL, "sherpa-onnx-1.13.2.jar", "sherpa");
        }
    }

    /**
     * 下载 ASR 离线模型
     * @param modelKey "paraformer" / "sensevoice" / "whisper"
     */
    public void downloadAsrModel(String modelKey) {
        switch (modelKey) {
            case "paraformer":
                downloadFile(MODEL_PARAFORMER_ZH, "sherpa-onnx-paraformer-zh-small.tar.bz2", "asr/paraformer");
                break;
            case "paraformer_trilingual":
                downloadFile(MODEL_PARAFORMER_TRILINGUAL, "sherpa-onnx-paraformer-trilingual.tar.bz2", "asr/paraformer_trilingual");
                break;
            case "whisper":
                downloadFile(MODEL_WHISPER_TINY, "sherpa-onnx-whisper-tiny.tar.bz2", "asr/whisper");
                break;
            case "sensevoice":
                downloadFile(MODEL_SENSE_VOICE, "sherpa-onnx-sense-voice.tar.bz2", "asr/sensevoice");
                break;
        }
    }

    /**
     * 下载 KWS 唤醒模型
     * @param modelKey "kws_mobile" / "kws_zh_en"
     */
    public void downloadKwsModel(String modelKey) {
        switch (modelKey) {
            case "kws_mobile":
                downloadFile(MODEL_KWS_MOBILE, "sherpa-onnx-kws-mobile.tar.bz2", "kws");
                break;
            case "kws_zh_en":
                downloadFile(MODEL_KWS_ZH_EN, "sherpa-onnx-kws-zh-en.tar.bz2", "kws");
                break;
        }
    }

    /**
     * 下载声纹模型（单文件，无需解压）
     */
    public void downloadSpeakerModel() {
        downloadFile(MODEL_SPEAKER_CAMPLUS, "campplus.onnx", "speaker");
    }

    /**
     * 下载 VAD 静音检测模型（单文件，无需解压）
     */
    public void downloadVadModel() {
        downloadFile(MODEL_VAD_SILERO, "silero_vad.onnx", "vad");
    }

    /**
     * 下载 TTS 模型（默认 MeloTTS 中英双语）
     */
    public void downloadTtsModel() {
        downloadTtsModel("melo");
    }

    public void downloadTtsModel(String modelKey) {
        switch (modelKey) {
            case "melo":
                downloadFile(MODEL_TTS_MELO, "vits-melo-tts-zh_en.tar.bz2", "tts/melo");
                break;
            case "aishell":
                downloadFile(MODEL_TTS_AISHELL, "vits-zh-aishell3.tar.bz2", "tts/aishell");
                break;
            case "xiaoya":
                downloadFile(MODEL_TTS_XIAOYA, "vits-piper-zh_CN-xiao_ya-medium.tar.bz2", "tts/xiaoya");
                break;
            default:
                downloadFile(MODEL_TTS_MELO, "vits-melo-tts-zh_en.tar.bz2", "tts/melo");
                break;
        }
    }

    // ==================== 核心下载逻辑 ====================

    private void downloadFile(String downloadUrl, String filename, String targetSubDir) {
        // 检查是否已存在
        File targetDir = new File(getModelCacheDir(), targetSubDir);
        if (targetDir.exists() && targetDir.list().length > 0) {
            Log.d(TAG, "模型已存在：" + targetSubDir);
            if (listener != null) {
                listener.onDownloadSuccess(targetSubDir, targetDir);
            }
            return;
        }

        // 创建临时文件
        File tempFile = new File(downloadCacheDir, filename);

        // 启动下载（文件大小在 doInBackground 中自动获取）
        new DownloadTask(downloadUrl, tempFile, targetSubDir).execute();
    }

    /**
     * 获取远程文件大小（异步方式，避免主线程阻塞）
     * 注意：由于 AsyncTask 已在线程池中执行，此处可直接使用
     */
    private long getRemoteFileSize(String urlString) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();
            return conn.getContentLengthLong();
        } catch (Exception e) {
            Log.e(TAG, "获取文件大小失败（将使用未知大小下载）", e);
            return -1;
        }
    }

    private void tryNextProxy() {
        proxyIndex++;
        Log.d(TAG, "尝试代理 " + proxyIndex + "/" + (PROXY_LIST.length + 1));
    }

    /**
     * 添加代理前缀到 URL
     * proxyIndex = 0: 直连（无代理）
     * proxyIndex = 1: 使用 PROXY_LIST[0]
     * proxyIndex = 2: 使用 PROXY_LIST[1]
     */
    private String addProxy(String originalUrl) {
        if (proxyIndex == 0) {
            // 直连，不使用代理
            return originalUrl;
        }
        if (proxyIndex - 1 >= PROXY_LIST.length) {
            // 所有代理都试过，返回原始 URL
            return originalUrl;
        }
        // gh-proxy 格式：https://gh-proxy.com/原始URL
        // ghfast.top 格式：https://ghfast.top/原始URL
        return PROXY_LIST[proxyIndex - 1] + originalUrl;
    }

    /**
     * 获取模型缓存目录
     */
    private File getModelCacheDir() {
        File dir = new File(context.getFilesDir(), "voice_models");
        dir.mkdirs();
        return dir;
    }

    // ==================== 下载任务 ====================

    private class DownloadTask extends AsyncTask<Void, Integer, Boolean> {
        private String downloadUrl;
        private File tempFile;
        private String targetSubDir;

        public DownloadTask(String url, File tempFile, String targetSubDir) {
            this.downloadUrl = url;
            this.tempFile = tempFile;
            this.targetSubDir = targetSubDir;
        }

        @Override
        protected void onPreExecute() {
            if (listener != null) {
                listener.onDownloadStarted(targetSubDir);
            }
            // 删除旧临时文件
            tempFile.delete();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            long totalSize = -1;

            // ===== 第一步：HEAD 请求获取文件大小 =====
            try {
                HttpURLConnection headConn = (HttpURLConnection) new URL(addProxy(downloadUrl)).openConnection();
                headConn.setRequestMethod("HEAD");
                headConn.setConnectTimeout(10000);
                headConn.setReadTimeout(10000);
                headConn.connect();
                totalSize = headConn.getContentLengthLong();
                headConn.disconnect();
                Log.d(TAG, "文件大小：" + totalSize + " bytes");
            } catch (IOException e) {
                Log.w(TAG, "无法获取文件大小，将按未知大小下载");
            }

            // ===== 第二步：GET 请求下载文件 =====
            Boolean result = downloadWithRetry(addProxy(downloadUrl), tempFile, totalSize);

            // 如果失败且还有代理可用，重试
            if (!result && proxyIndex < PROXY_LIST.length) {
                Log.d(TAG, "直连下载失败，尝试代理...");
                tryNextProxy();
                result = downloadWithRetry(addProxy(downloadUrl), tempFile, totalSize);
            }

            // 如果再次失败且还有第二个代理，再试
            if (!result && proxyIndex < PROXY_LIST.length) {
                Log.d(TAG, "代理1失败，尝试代理2...");
                tryNextProxy();
                result = downloadWithRetry(addProxy(downloadUrl), tempFile, totalSize);
            }

            return result;
        }

        /**
         * 从指定 URL 下载文件
         */
        private Boolean downloadWithRetry(String url, File destFile, long totalSize) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);

                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(destFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                long downloaded = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    // 计算进度
                    int progress = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : 0;
                    publishProgress(progress, (int) (downloaded / 1024), totalSize > 0 ? (int) (totalSize / 1024) : 0);
                }

                fos.close();
                in.close();
                conn.disconnect();

                Log.d(TAG, "下载完成：" + destFile.getAbsolutePath() + " (" + downloaded + " bytes)");
                return true;

            } catch (IOException e) {
                Log.e(TAG, "下载失败：" + e.getMessage());
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (listener != null) {
                listener.onDownloadProgress(targetSubDir, values[0], values[1] * 1024, values.length > 2 ? values[2] * 1024 : -1);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (!success) {
                // 所有源都失败
                tempFile.delete();
                if (listener != null) {
                    listener.onDownloadFailed(targetSubDir, "下载失败：所有下载源均不可用");
                }
                return;
            }

            // 解压模型文件
            File targetDir = new File(getModelCacheDir(), targetSubDir);
            if (extractArchive(tempFile, targetDir)) {
                if (listener != null) {
                    listener.onDownloadSuccess(targetSubDir, targetDir);
                }
            } else {
                // 解压失败，删除临时文件
                tempFile.delete();
                if (listener != null) {
                    listener.onDownloadFailed(targetSubDir, "解压失败");
                }
            }
        }
    }

    /**
     * 解压归档文件（支持 .tar.bz2, .tar.gz, .zip，公开方法）
     */
    public boolean extractArchive(File archiveFile, File targetDir) {
        if (!archiveFile.exists()) {
            Log.e(TAG, "归档文件不存在：" + archiveFile.getAbsolutePath());
            return false;
        }

        targetDir.mkdirs();

        try {
            String fileName = archiveFile.getName().toLowerCase();
            
            // .tar.bz2 解压
            if (fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2")) {
                return extractTarBz2(archiveFile, targetDir);
            } 
            // .tar.gz 解压
            else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
                return extractTarGz(archiveFile, targetDir);
            }
            // .zip 解压
            else if (fileName.endsWith(".zip")) {
                return extractZip(archiveFile, targetDir);
            }
            // 单文件（如 .onnx）直接复制
            else {
                File outFile = new File(targetDir, archiveFile.getName());
                copyFile(archiveFile, outFile);
                return true;
            }

        } catch (Exception e) {
            Log.e(TAG, "解压失败：" + e.getMessage(), e);
            return false;
        }
    }

    private boolean extractTarBz2(File archiveFile, File targetDir) throws IOException, InterruptedException {
        // 使用 tar 命令解压（Android 自带 tar）
        String[] cmd = {"tar", "-xjf", archiveFile.getAbsolutePath(), "-C", targetDir.getAbsolutePath()};
        Process process = Runtime.getRuntime().exec(cmd);
        int exitCode = process.waitFor();
        return exitCode == 0;
    }

    private boolean extractTarGz(File archiveFile, File targetDir) throws IOException, InterruptedException {
        String[] cmd = {"tar", "-xzf", archiveFile.getAbsolutePath(), "-C", targetDir.getAbsolutePath()};
        Process process = Runtime.getRuntime().exec(cmd);
        int exitCode = process.waitFor();
        return exitCode == 0;
    }

    private boolean extractZip(File archiveFile, File targetDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(archiveFile));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(targetDir, entry.getName());
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                copyStream(zis, outFile);
            }
            zis.closeEntry();
        }
        zis.close();
        return true;
    }

    private void copyStream(InputStream in, File outFile) throws IOException {
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
        }
        fos.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        out.close();
        in.close();
    }

    // ==================== 清理 ====================

    /**
     * 删除指定模型缓存
     */
    public void deleteModel(String modelKey) {
        File modelDir = new File(getModelCacheDir(), modelKey);
        deleteRecursive(modelDir);
        Log.d(TAG, "已删除模型缓存：" + modelKey);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    /**
     * 清除所有下载缓存
     */
    public void clearAllCache() {
        deleteRecursive(new File(getModelCacheDir().toURI()));
        deleteRecursive(downloadCacheDir);
    }

    /**
     * 检查模型是否已安装
     */
    public boolean isModelInstalled(String modelKey) {
        File modelDir = new File(getModelCacheDir(), modelKey);
        return modelDir.exists() && modelDir.list().length > 0;
    }

    /**
     * 获取模型目录（返回文件路径供 sherpa-onnx 使用）
     */
    public File getModelDir(String modelKey) {
        return new File(getModelCacheDir(), modelKey);
    }
}
