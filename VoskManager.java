package alin.android.alinos;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class VoskManager {
    private static final String TAG = "VoskManager";
    private static final float SAMPLE_RATE = 16000.0f;
    private static final String MODEL_NAME_IN_ASSETS = "vosk-model-small-cn-0.22";
    private static final String MODEL_DIR_INTERNAL = "model";
    private static VoskManager instance;

    private Model model;
    private Recognizer recognizer;
    private final Context appContext;
    private RecognizeCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface RecognizeCallback {
        void onModelInitSuccess();
        void onModelInitError(String error);
        void onPartialResult(String partialText);
        void onFinalResult(String finalText);
    }

    public static synchronized VoskManager getInstance(Context context) {
        if (instance == null) {
            instance = new VoskManager(context.getApplicationContext());
        }
        return instance;
    }

    private VoskManager(Context appContext) {
        this.appContext = appContext;
        // ✅ 移除 JNA 初始化，Vosk 库会自动处理
        Log.d(TAG, "VoskManager 初始化");
    }

    /**
     * 初始化 Vosk 模型和识别器
     */
    public void init(RecognizeCallback callback) {
        this.callback = callback;
        new Thread(() -> {
            try {
                // 复制模型文件到内部存储
                copyModelToInternalStorage();

                // 验证模型目录
                File modelDir = new File(appContext.getFilesDir(), MODEL_DIR_INTERNAL + "/" + MODEL_NAME_IN_ASSETS);
                if (!modelDir.exists() || modelDir.listFiles() == null || Objects.requireNonNull(modelDir.listFiles()).length == 0) {
                    throw new IOException("模型文件复制失败，目标路径为空：" + modelDir.getAbsolutePath());
                }

                // ✅ 使用简化的模型加载方式
                String modelPath = modelDir.getAbsolutePath();
                Log.d(TAG, "加载模型路径: " + modelPath);
                
                // 直接加载模型
                model = new Model(modelPath);
                recognizer = new Recognizer(model, SAMPLE_RATE);
                Log.d(TAG, "Vosk 模型和识别器初始化成功");

                // 回调主线程通知成功
                mainHandler.post(callback::onModelInitSuccess);
            } catch (Exception e) {
                Log.e(TAG, "模型初始化失败", e);
                mainHandler.post(() -> callback.onModelInitError("初始化错误：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 复制 assets 中的模型文件到应用内部存储
     */
    private void copyModelToInternalStorage() throws IOException {
        File modelDir = new File(appContext.getFilesDir(), MODEL_DIR_INTERNAL);
        File targetModelDir = new File(modelDir, MODEL_NAME_IN_ASSETS);

        // 若模型已存在且非空，跳过复制
        if (targetModelDir.exists() && targetModelDir.listFiles() != null && Objects.requireNonNull(targetModelDir.listFiles()).length > 0) {
            Log.d(TAG, "模型文件已存在，跳过复制：" + targetModelDir.getAbsolutePath());
            return;
        }

        // 开始复制
        AssetManager assets = appContext.getAssets();
        copyAssetsFolder(assets, MODEL_NAME_IN_ASSETS, targetModelDir.getAbsolutePath());
        Log.d(TAG, "模型文件复制完成：" + targetModelDir.getAbsolutePath());
    }

    /**
     * 递归复制 assets 文件夹
     */
    private void copyAssetsFolder(AssetManager assets, String srcAssetsPath, String destPath) throws IOException {
        File destDir = new File(destPath);
        if (!destDir.exists()) {
            boolean mkdirSuccess = destDir.mkdirs();
            if (!mkdirSuccess) {
                throw new IOException("创建目标文件夹失败：" + destPath);
            }
        }

        String[] fileNames = assets.list(srcAssetsPath);
        if (fileNames == null || fileNames.length == 0) {
            throw new IOException("assets 中未找到模型文件：" + srcAssetsPath);
        }

        for (String fileName : fileNames) {
            String srcPath = srcAssetsPath + File.separator + fileName;
            String destFilePath = destPath + File.separator + fileName;

            if (isAssetFile(assets, srcPath)) {
                copyAssetFile(assets, srcPath, destFilePath);
            } else {
                copyAssetsFolder(assets, srcPath, destFilePath);
            }
        }
    }

    /**
     * 判断 assets 路径是否为文件（非文件夹）
     */
    private boolean isAssetFile(AssetManager assets, String path) {
        try {
            InputStream is = assets.open(path);
            is.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 复制单个 assets 文件
     */
    private void copyAssetFile(AssetManager assets, String srcPath, String destFilePath) throws IOException {
        try (InputStream in = assets.open(srcPath);
             OutputStream out = new FileOutputStream(destFilePath)) {

            byte[] buffer = new byte[1024 * 4];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            Log.d(TAG, "复制文件成功：" + destFilePath);
        }
    }

    /**
     * 投喂音频数据进行识别
     */
    public void feedAudio(byte[] pcmData) {
        if (recognizer == null || callback == null) return;
        try {
            boolean accepted = recognizer.acceptWaveForm(pcmData, pcmData.length);
            if (accepted) {
                String finalResult = recognizer.getResult();
                mainHandler.post(() -> callback.onFinalResult(parseResult(finalResult)));
            } else {
                String partialResult = recognizer.getPartialResult();
                mainHandler.post(() -> callback.onPartialResult(parsePartial(partialResult)));
            }
        } catch (Exception e) {
            Log.e(TAG, "音频处理错误", e);
        }
    }

    /**
     * 解析最终识别结果
     */
    private String parseResult(String json) {
        if (json.contains("\"text\":\"")) {
            int start = json.indexOf("\"text\":\"") + 8;
            int end = json.indexOf("\"", start);
            if (end > start) return json.substring(start, end);
        }
        return "";
    }

    /**
     * 解析中间识别结果
     */
    private String parsePartial(String json) {
        if (json.contains("\"partial\":\"")) {
            int start = json.indexOf("\"partial\":\"") + 11;
            int end = json.indexOf("\"", start);
            if (end > start) return json.substring(start, end);
        }
        return "";
    }

    /**
     * 释放资源
     */
    public void release() {
        try {
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            Log.d(TAG, "Vosk 资源释放成功");
        } catch (Exception e) {
            Log.e(TAG, "释放资源失败", e);
        }
    }

    public Recognizer getRecognizer() {
        return recognizer;
    }
}