package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class TextToSpeechActivity extends AppCompatActivity {
    private static final String TAG = "TTSTest";

    // 补充声明「开始朗读」按钮
    private TextToSpeech tts;
    private EditText et_input_text;
    private Button btn_test_cn, btn_test_en, btn_test_mix, btn_clear, btn_start_speak, btn_stop_speak;

    // TTS默认配置（可根据需求调整，自动生效）
    private float DEFAULT_SPEECH_RATE = 1.0f; // 语速（0.5-2.0，1.0为正常）
    private float DEFAULT_PITCH = 1.0f;       // 音调（0.5-2.0，1.0为正常）
    private Locale currentLocale = Locale.SIMPLIFIED_CHINESE; // 默认语言

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_to_speech);

        Log.d(TAG, "🏁 TTS测试页面创建");
        initViews();

        // 第一步：检查是否有TTS引擎（优先默认引擎）
        checkTtsEngineAvailability();
    }

    /**
     * 初始化UI组件 + 按钮事件绑定（核心修改：拆分按钮职责）
     */
    private void initViews() {
        et_input_text = findViewById(R.id.et_input_text);
        btn_test_cn = findViewById(R.id.btn_test_cn);
        btn_test_en = findViewById(R.id.btn_test_en);
        btn_test_mix = findViewById(R.id.btn_test_mix);
        btn_clear = findViewById(R.id.btn_clear);
        btn_start_speak = findViewById(R.id.btn_start_speak); // 绑定开始朗读按钮
        btn_stop_speak = findViewById(R.id.btn_stop_speak);

        // 测试按钮：仅填充示例文本，不直接朗读
        btn_test_cn.setOnClickListener(v -> fillExampleText("大家好，这是中文TTS测试！"));
        btn_test_en.setOnClickListener(v -> fillExampleText("Hello, this is English TTS test!"));
        btn_test_mix.setOnClickListener(v -> fillExampleText("Hello！这是中英混合TTS测试。Android TTS is working!"));
        
        // 功能按钮：清空、开始朗读、停止朗读
        btn_clear.setOnClickListener(v -> et_input_text.setText(""));
        btn_start_speak.setOnClickListener(v -> startSpeaking()); // 核心：开始朗读逻辑
        btn_stop_speak.setOnClickListener(v -> stopSpeaking());

        // 初始禁用功能按钮
        setButtonsEnabled(false);
    }

    /**
     * 填充示例文本到输入框（替代原直接朗读逻辑）
     */
    private void fillExampleText(String exampleText) {
        et_input_text.setText(exampleText);
        et_input_text.setSelection(exampleText.length()); // 光标定位到文本末尾
        Toast.makeText(this, "已填充示例文本", Toast.LENGTH_SHORT).show();
    }

    /**
     * 检查TTS引擎可用性（保留优先默认引擎逻辑）
     */
    private void checkTtsEngineAvailability() {
        Log.d(TAG, "🔍 开始检查TTS引擎（优先默认引擎）");

        String defaultEngineName = null;
        List<TextToSpeech.EngineInfo> engineList = null;
        boolean hasAvailableEngine = false;

        try {
            if (tts != null) {
                defaultEngineName = tts.getDefaultEngine();
            } else {
                Log.d(TAG, "当前TTS实例为空，创建临时实例检测默认引擎");
                TextToSpeech tempTts = new TextToSpeech(this, null);
                defaultEngineName = tempTts.getDefaultEngine();
                engineList = tempTts.getEngines();
                tempTts.shutdown();
            }

            if (defaultEngineName != null && !defaultEngineName.isEmpty()) {
                hasAvailableEngine = true;
                Log.d(TAG, "✅ 检测到系统默认TTS引擎：" + defaultEngineName);
            } else {
                Log.w(TAG, "⚠️ 未检测到默认TTS引擎，兜底检查已安装引擎列表");
                if (engineList == null) {
                    TextToSpeech tempTts = new TextToSpeech(this, null);
                    engineList = tempTts.getEngines();
                    tempTts.shutdown();
                }
                if (engineList != null && !engineList.isEmpty()) {
                    hasAvailableEngine = true;
                    Log.d(TAG, "✅ 未找到默认引擎，但检测到 " + engineList.size() + " 个已安装引擎");
                    for (TextToSpeech.EngineInfo engine : engineList) {
                        Log.d(TAG, "  引擎: " + engine.name + " - " + engine.label);
                    }
                }
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "检查TTS引擎空指针异常: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "检查TTS引擎其他异常: " + e.getMessage());
        }

        if (hasAvailableEngine) {
            initTts();
        } else {
            Log.w(TAG, "⚠️ 未检测到任何TTS引擎");
            showNoTtsEngineDialog();
            setButtonsEnabled(false);
        }
    }

    /**
     * 初始化TTS + 自动配置默认参数（核心：自动加载配置）
     */
    private void initTts() {
        Log.d(TAG, "🚀 开始初始化TTS（自动加载默认配置）");

        tts = new TextToSpeech(getApplicationContext(), status -> {
            Log.d(TAG, "🎯 TTS初始化回调，状态码: " + status);

            runOnUiThread(() -> {
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "✅ TTS初始化成功");

                    // 自动应用默认配置（无需手动调整）
                    tts.setSpeechRate(DEFAULT_SPEECH_RATE);
                    tts.setPitch(DEFAULT_PITCH);
                    // 初始语言设为简体中文，后续动态识别文本语言覆盖
                    int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "⚠️ 中文支持不完整，自动切换为英文");
                        tts.setLanguage(Locale.ENGLISH);
                        currentLocale = Locale.ENGLISH;
                        Toast.makeText(this, "中文语音包缺失，默认切换为英文", Toast.LENGTH_SHORT).show();
                    }

                    // 启用所有功能按钮（含开始朗读）
                    setButtonsEnabled(true);
                    Toast.makeText(this, "TTS已就绪，可输入文本后点击「开始朗读」", Toast.LENGTH_SHORT).show();

                } else {
                    Log.e(TAG, "❌ TTS初始化失败，状态码：" + status);
                    Toast.makeText(this, "TTS初始化失败，请检查设置", Toast.LENGTH_LONG).show();
                    setButtonsEnabled(false);
                    showNoTtsEngineDialog();
                }
            });
        });

        // 监听朗读进度（便于动态场景下的状态回调）
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "🔊 朗读开始：" + utteranceId);
                runOnUiThread(() -> Toast.makeText(TextToSpeechActivity.this, "开始朗读", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "✅ 朗读完成：" + utteranceId);
                runOnUiThread(() -> Toast.makeText(TextToSpeechActivity.this, "朗读完成", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "❌ 朗读错误：" + utteranceId);
                runOnUiThread(() -> Toast.makeText(TextToSpeechActivity.this, "朗读异常", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 核心：开始朗读（动态识别文本语言 + 自动适配配置）
     */
    private void startSpeaking() {
        if (tts == null) {
            Toast.makeText(this, "TTS未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 获取输入框文本（实际场景可替换为动态传入的文本）
        String text = et_input_text.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "请输入要朗读的文本", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 动态识别文本语言（核心：自动适配，替代手动选择）
        currentLocale = detectTextLanguage(text);
        Log.d(TAG, "🔍 动态识别文本语言：" + currentLocale.getDisplayName());

        // 3. 执行朗读（自动应用识别的语言和默认配置）
        speakText(text, currentLocale);
    }

    /**
     * 动态识别文本的主要语言（适配中文/英文/混合）
     * 逻辑：含中文字符则优先中文，纯英文则英文，混合仍优先中文
     */
    private Locale detectTextLanguage(String text) {
        // 匹配中文字符的正则
        Pattern chinesePattern = Pattern.compile("[\\u4e00-\\u9fa5]");
        if (chinesePattern.matcher(text).find()) {
            return Locale.SIMPLIFIED_CHINESE; // 含中文，用简体中文
        } else {
            return Locale.ENGLISH; // 纯英文，用英文
        }
        // 混合语言场景：优先用中文引擎（多数TTS引擎支持混合朗读）
    }

    /**
     * 执行文本朗读（适配动态语言 + 自动配置）
     */
    private void speakText(String text, Locale locale) {
        try {
            // 停止之前的朗读，避免队列堆积
            tts.stop();

            // 自动适配识别的语言
            int langResult = tts.setLanguage(locale);
            if (langResult == TextToSpeech.LANG_MISSING_DATA) {
                String langTip = locale == Locale.SIMPLIFIED_CHINESE ? "中文" : "英文";
                Toast.makeText(this, "缺少" + langTip + "语音数据，请下载语音包", Toast.LENGTH_SHORT).show();
                return;
            } else if (langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                String langTip = locale == Locale.SIMPLIFIED_CHINESE ? "中文" : "英文";
                Toast.makeText(this, "不支持" + langTip + "朗读，自动切换为默认语言", Toast.LENGTH_SHORT).show();
                tts.setLanguage(currentLocale); // 回退到默认语言
            }

            // 开始朗读（QUEUE_FLUSH：清空队列，立即朗读）
            int speakResult = tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "tts_" + System.currentTimeMillis() // 唯一标识，用于进度监听
            );

            if (speakResult != TextToSpeech.SUCCESS) {
                Toast.makeText(this, "朗读请求失败", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "朗读异常: " + e.getMessage(), e);
            Toast.makeText(this, "朗读异常", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 停止朗读（保留原有逻辑）
     */
    private void stopSpeaking() {
        if (tts != null) {
            tts.stop();
            Toast.makeText(this, "已停止朗读", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 启用/禁用功能按钮（包含开始朗读按钮）
     */
    private void setButtonsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            btn_test_cn.setEnabled(enabled);    // 示例文本按钮
            btn_test_en.setEnabled(enabled);
            btn_test_mix.setEnabled(enabled);
            btn_clear.setEnabled(enabled);      // 清空按钮
            btn_start_speak.setEnabled(enabled); // 开始朗读按钮
            btn_stop_speak.setEnabled(enabled);  // 停止朗读按钮
        });
    }

    // 以下方法保留原有逻辑，仅优化弹窗跳转兼容性
    private void showNoTtsEngineDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("需要TTS引擎");
            builder.setMessage("当前设备没有可用的文字转语音引擎。\n\n请安装一个TTS引擎来使用语音功能。");

            builder.setPositiveButton("安装引擎", (dialog, which) -> {
                try {
                    // 优先跳转到系统TTS安装页（更通用）
                    Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent("android.settings.TEXT_TO_SPEECH_SETTINGS");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e2) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("market://details?id=com.google.android.tts"));
                            startActivity(intent);
                        } catch (Exception e3) {
                            Toast.makeText(this, "无法打开安装页面，请手动安装TTS引擎", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

            builder.setNegativeButton("取消", null);
            builder.setCancelable(false);
            builder.show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "🔄 页面恢复");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "⏸️ 页面暂停");
        stopSpeaking(); // 切后台自动停止朗读
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🗑️ 页面销毁");
        // 释放TTS资源，避免内存泄漏
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}