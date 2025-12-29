package alin.android.alinos.utils;

import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.Locale;

public class TtsChecker {
    private static final String TAG = "TtsChecker";

    public static void checkAndInstallTts(Context context, OnTtsCheckListener listener) {
        // 使用一个数组来包装TTS实例，这样可以绕过final限制
        final TextToSpeech[] ttsHolder = new TextToSpeech[1];

        ttsHolder[0] = new TextToSpeech(context.getApplicationContext(),
                status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "✅ TTS引擎检查通过");
                        if (listener != null) {
                            listener.onTtsAvailable();
                        }
                    } else {
                        Log.e(TAG, "❌ TTS引擎检查失败，状态码：" + status);
                        if (listener != null) {
                            listener.onTtsMissing();
                        }
                    }

                    // 安全地关闭TTS实例
                    if (ttsHolder[0] != null) {
                        ttsHolder[0].shutdown();
                        ttsHolder[0] = null;
                    }
                });
    }

    public static void openTtsSettings(Context context) {
        try {
            // 尝试多种跳转方式
            Intent intent = new Intent();
            intent.setAction("com.android.settings.TTS_SETTINGS");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e1) {
            try {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                Log.e(TAG, "无法打开TTS设置：" + e2.getMessage());
            }
        }
    }

    public interface OnTtsCheckListener {
        void onTtsAvailable();
        void onTtsMissing();
    }
}