package alin.android.alinos.manager;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.Locale;

public class TextToSpeechManager {
    private TextToSpeech tts;
    private boolean isEngSupported = false;
    private boolean isCnSupported = false;
    private final Context context; // 新增上下文引用

    public TextToSpeechManager(Context context) {
        this.context = context; // 初始化上下文
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // 初始化英语支持
                int engResult = tts.setLanguage(Locale.ENGLISH);
                isEngSupported = (engResult != TextToSpeech.LANG_MISSING_DATA)
                        && (engResult != TextToSpeech.LANG_NOT_SUPPORTED);

                // 初始化中文支持
                int cnResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                isCnSupported = (cnResult != TextToSpeech.LANG_MISSING_DATA)
                        && (cnResult != TextToSpeech.LANG_NOT_SUPPORTED);
            } else {
                Toast.makeText(context, "文本朗读初始化失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 自动判断文本语言并朗读
    public void speak(String text) {
        if (TextUtils.isEmpty(text)) return;

        boolean isEnglish = text.matches(".*[a-zA-Z]+.*");
        if (isEnglish && isEngSupported) {
            tts.setLanguage(Locale.ENGLISH);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        } else if (isCnSupported) {
            tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        } else {
            // 使用保存的 context 显示 Toast，而非 tts.getContext()
            Toast.makeText(context, "不支持当前语言朗读", Toast.LENGTH_SHORT).show();
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}