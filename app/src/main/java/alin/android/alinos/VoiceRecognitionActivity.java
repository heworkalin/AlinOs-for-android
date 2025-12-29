package alin.android.alinos;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceRecognitionActivity extends AppCompatActivity {
    
    private static final String TAG = "VoiceRecognition";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    // UI 组件
    private ImageView ivModelStatus;
    private TextView tvModelStatus, tvResult, tvVolume, tvTime, tvConfidence, tvStat;
    private ProgressBar pbModelLoading, pbVolume;
    private Button btnRecord, btnPlay, btnRecognize;
    
    // 语音识别核心组件
    private Model model;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private MediaPlayer mediaPlayer;
    
    // 状态标志
    private boolean isModelLoaded = false;
    private boolean isRecording = false;
    private boolean isPlaying = false;
    private boolean isRecognizing = false;
    
    // 录音文件
    private File recordingFile;
    private FileOutputStream audioOutputStream;
    private long recordingStartTime = 0;
    private int bufferSize;
    
    // 处理器和线程池
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler();
    private final Handler volumeHandler = new Handler();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    
    // 状态枚举
    private enum RecognitionState {
        READY,
        RECORDING,
        PLAYING,
        RECOGNIZING
    }
    
    private RecognitionState currentState = RecognitionState.READY;
    
    // 存储最近一次的原始JSON和解析结果
    private String lastRawJson = "";
    private String lastParsedText = "";
    private double lastConfidence = 0.0;
    
    // 定时任务
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long elapsedTime = System.currentTimeMillis() - recordingStartTime;
                tvTime.setText(String.format("录音时长: %ds", elapsedTime / 1000));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };
    
    private final Runnable volumeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && audioRecord != null) {
                updateVolumeLevel();
                volumeHandler.postDelayed(this, 100);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_recognition);
        
        initViews();
        checkPermissions();
    }
    
    private void initViews() {
        ivModelStatus = findViewById(R.id.iv_model_status);
        tvModelStatus = findViewById(R.id.tv_model_status);
        pbModelLoading = findViewById(R.id.pb_model_loading);
        btnRecord = findViewById(R.id.btn_record);
        btnPlay = findViewById(R.id.btn_play);
        btnRecognize = findViewById(R.id.btn_recognize);
        tvResult = findViewById(R.id.tv_result);
        tvVolume = findViewById(R.id.tv_volume);
        pbVolume = findViewById(R.id.pb_volume);
        tvTime = findViewById(R.id.tv_time);
        tvConfidence = findViewById(R.id.tv_confidence);
        tvStat = findViewById(R.id.tv_stat);
        
        // 按钮点击监听
        btnRecord.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });
        
        btnPlay.setOnClickListener(v -> {
            if (!isPlaying) {
                playRecording();
            } else {
                stopPlaying();
            }
        });
        
        btnRecognize.setOnClickListener(v -> {
            if (!isRecognizing) {
                startManualRecognition();
            } else {
                stopManualRecognition();
            }
        });
        
        // 初始状态
        updateModelStatus("正在检查权限...", false);
        updateUIState(RecognitionState.READY);
        btnRecord.setEnabled(false);
        btnPlay.setEnabled(false);
        btnRecognize.setEnabled(false);
        
        // 识别结果点击事件（查看原始数据）
        tvResult.setOnClickListener(v -> {
            String resultText = tvResult.getText().toString();
            if (!resultText.contains("等待识别") && !resultText.contains("正在录音") 
                    && !resultText.contains("正在识别") && !resultText.isEmpty()) {
                // 使用最近保存的原始JSON和解析结果
                showRawDataDialog(lastParsedText, lastRawJson, lastConfidence);
            }
        });
    }
    
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.RECORD_AUDIO}, 
                    REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            initModel();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                updateModelStatus("需要录音权限", false);
                Toast.makeText(this, "需要录音权限才能使用语音识别功能", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void initModel() {
        updateModelStatus("正在初始化模型...", false);
        
        executorService.execute(() -> {
            try {
                // ✅ 检查：如果你的范例音频是中文，请改为："vosk-model-small-cn-0.22"
                // 如果是英文，保持："vosk-model-small-en-us-0.15"
                String modelName = "vosk-model-small-cn-0.22";
                
                // 创建模型目录
                File modelDir = new File(getFilesDir(), "models/" + modelName);
                if (!modelDir.exists()) {
                    modelDir.mkdirs();
                }
                
                // 复制模型文件
                copyModelAssets(modelName, modelDir);
                
                // 检查模型文件是否复制成功
                File finalMdlFile = new File(modelDir, "am/final.mdl");
                if (!finalMdlFile.exists()) {
                    Log.e(TAG, "模型文件不存在: " + finalMdlFile.getAbsolutePath());
                    mainHandler.post(() -> {
                        updateModelStatus("模型文件复制失败", false);
                        Toast.makeText(this, 
                                "模型文件复制失败，请检查assets中的模型文件", 
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                
                // ✅ 加载模型和识别器
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, SAMPLE_RATE);
                
                mainHandler.post(() -> {
                    isModelLoaded = true;
                    updateModelStatus("模型加载成功", true);
                    updateUIState(RecognitionState.READY);
                    btnRecord.setEnabled(true);
                    tvStat.setText("就绪");
                    tvStat.setTextColor(0xFF4CAF50);
                    
                    // 添加调试信息
                    Log.d(TAG, "模型加载成功，准备识别");
                    Toast.makeText(this, "模型加载成功，可以开始录音", Toast.LENGTH_SHORT).show();
                });
                
                Log.i(TAG, "模型初始化成功，路径: " + modelDir.getAbsolutePath());
                
            } catch (Exception e) {
                Log.e(TAG, "模型初始化失败", e);
                mainHandler.post(() -> {
                    updateModelStatus("初始化失败", false);
                    Toast.makeText(this, 
                            "模型初始化失败: " + e.getMessage(), 
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 复制模型文件
     */
    private void copyModelAssets(String assetPath, File targetDir) throws IOException {
        if (targetDir.exists() && targetDir.list() != null && targetDir.list().length > 0) {
            Log.d(TAG, "模型文件已存在，跳过复制: " + targetDir.getAbsolutePath());
            return;
        }
        
        if (!targetDir.exists()) {
            boolean mkdirSuccess = targetDir.mkdirs();
            if (!mkdirSuccess) {
                throw new IOException("创建目标文件夹失败: " + targetDir.getAbsolutePath());
            }
        }
        
        copyAssetsRecursive(assetPath, targetDir);
        Log.d(TAG, "模型文件复制完成: " + targetDir.getAbsolutePath());
    }
    
    /**
     * 递归复制assets文件夹
     */
    private void copyAssetsRecursive(String assetPath, File targetDir) throws IOException {
        String[] assetList = getAssets().list(assetPath);
        
        if (assetList == null || assetList.length == 0) {
            return;
        }
        
        for (String assetName : assetList) {
            String currentAssetPath = assetPath + "/" + assetName;
            File targetFile = new File(targetDir, assetName);
            
            try {
                InputStream inputStream = getAssets().open(currentAssetPath);
                copyFile(inputStream, targetFile);
                inputStream.close();
            } catch (IOException e) {
                if (!targetFile.exists()) {
                    boolean mkdirSuccess = targetFile.mkdir();
                    if (!mkdirSuccess) {
                        throw new IOException("创建文件夹失败: " + targetFile.getAbsolutePath());
                    }
                }
                copyAssetsRecursive(currentAssetPath, targetFile);
            }
        }
    }
    
    private void copyFile(InputStream inputStream, File targetFile) throws IOException {
        try (OutputStream outputStream = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[1024 * 4];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.flush();
            Log.d(TAG, "复制文件: " + targetFile.getAbsolutePath());
        }
    }
    
    private void updateModelStatus(String message, boolean success) {
        tvModelStatus.setText(message);
        if (success) {
            ivModelStatus.setImageResource(android.R.drawable.ic_menu_help);
            pbModelLoading.setVisibility(View.GONE);
        } else {
            ivModelStatus.setImageResource(android.R.drawable.ic_dialog_alert);
        }
    }
    
    private void updateUIState(RecognitionState state) {
        currentState = state;
        
        switch (state) {
            case READY:
                btnRecord.setText("开始录音");
                btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark));
                btnRecord.setEnabled(isModelLoaded);
                btnPlay.setText("播放录音");
                btnPlay.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
                btnPlay.setEnabled(recordingFile != null && recordingFile.exists());
                btnRecognize.setText("开始识别");
                btnRecognize.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark));
                btnRecognize.setEnabled(recordingFile != null && recordingFile.exists() && isModelLoaded);
                tvStat.setText("就绪");
                tvStat.setTextColor(0xFF4CAF50);
                break;
                
            case RECORDING:
                btnRecord.setText("停止录音");
                btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
                btnPlay.setEnabled(false);
                btnRecognize.setEnabled(false);
                tvStat.setText("录音中");
                tvStat.setTextColor(0xFFFF5722);
                break;
                
            case PLAYING:
                btnRecord.setEnabled(false);
                btnPlay.setText("停止播放");
                btnPlay.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
                btnRecognize.setEnabled(false);
                tvStat.setText("播放中");
                tvStat.setTextColor(0xFF795548);
                break;
                
            case RECOGNIZING:
                btnRecord.setEnabled(false);
                btnPlay.setEnabled(false);
                btnRecognize.setText("停止识别");
                btnRecognize.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
                tvStat.setText("识别中");
                tvStat.setTextColor(0xFFFF9800);
                break;
        }
    }
    
    /**
     * 开始录音
     */
    private void startRecording() {
        if (!isModelLoaded) {
            Toast.makeText(this, "模型未加载完成，请稍后", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 1. 初始化 AudioRecord
            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "录音设备初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 2. 创建录音文件
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            recordingFile = new File(getExternalCacheDir(), "recording_" + timestamp + ".pcm");
            audioOutputStream = new FileOutputStream(recordingFile);
            
            // 3. 开始录音
            audioRecord.startRecording();
            isRecording = true;
            recordingStartTime = System.currentTimeMillis();
            
            // 4. 更新 UI 状态
            updateUIState(RecognitionState.RECORDING);
            tvResult.setText("正在录音...请说话");
            tvVolume.setText("音量: --");
            pbVolume.setProgress(0);
            
            // 5. 启动计时器和音量监测
            timerHandler.post(timerRunnable);
            volumeHandler.post(volumeUpdateRunnable);
            
            // 6. ✅ 启动音频处理和识别线程
            new Thread(this::recordAndRecognizeAudioLoop).start();
            
            Toast.makeText(this, "开始录音和识别", Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Log.e(TAG, "开始录音失败", e);
            Toast.makeText(this, "开始录音失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            resetRecordingState();
        }
    }
    
    /**
     * ✅ 同时录音和识别的循环
     */
    private void recordAndRecognizeAudioLoop() {
        byte[] buffer = new byte[bufferSize];
        
        while (isRecording && audioRecord != null) {
            try {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (bytesRead > 0) {
                    // 写入文件
                    audioOutputStream.write(buffer, 0, bytesRead);
                    
                    // 进行语音识别
                    if (recognizer != null) {
                        // 将音频数据送入识别器
                        boolean accepted = recognizer.acceptWaveForm(buffer, bytesRead);
                        
                        if (accepted) {
                            // 最终结果
                            String resultJson = recognizer.getResult();
                            Log.d(TAG, "最终识别结果JSON: " + resultJson);
                            mainHandler.post(() -> parseAndDisplayResult(resultJson, true));
                        } else {
                            // 中间结果
                            String partialResultJson = recognizer.getPartialResult();
                            Log.d(TAG, "中间识别结果JSON: " + partialResultJson);
                            mainHandler.post(() -> parseAndDisplayResult(partialResultJson, false));
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "音频处理失败", e);
                break;
            }
        }
    }
    
    private void updateVolumeLevel() {
        if (audioRecord == null) return;
        
        short[] buffer = new short[bufferSize / 2];
        int bytesRead = audioRecord.read(buffer, 0, buffer.length);
        
        if (bytesRead > 0) {
            long sum = 0;
            for (int i = 0; i < bytesRead; i++) {
                sum += buffer[i] * buffer[i];
            }
            double rms = Math.sqrt(sum / bytesRead);
            double db = 20 * Math.log10(rms);
            int progress = (int) Math.min(100, Math.max(0, (db + 60) * 100 / 60));
            
            mainHandler.post(() -> {
                tvVolume.setText(String.format("音量: %.1f dB", db));
                pbVolume.setProgress(progress);
            });
        }
    }
    
    private void stopRecording() {
        isRecording = false;
        
        // 停止计时器和音量监测
        timerHandler.removeCallbacks(timerRunnable);
        volumeHandler.removeCallbacks(volumeUpdateRunnable);
        
        // 停止录音
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "停止录音失败", e);
            }
        }
        
        // 关闭文件流
        if (audioOutputStream != null) {
            try {
                audioOutputStream.close();
                audioOutputStream = null;
            } catch (IOException e) {
                Log.e(TAG, "关闭音频流失败", e);
            }
        }
        
        // 更新 UI 状态
        updateUIState(RecognitionState.READY);
        tvVolume.setText("音量: --");
        pbVolume.setProgress(0);
        
        // 如果还没有识别结果，显示提示
        if (tvResult.getText().toString().contains("正在录音")) {
            tvResult.setText("录音已停止");
        }
        
        Toast.makeText(this, "录音已停止", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * ✅ 手动识别（录音后）- 修复：重置Recognizer状态
     */
    private void startManualRecognition() {
        if (!isModelLoaded) {
            Toast.makeText(this, "模型未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (recordingFile == null || !recordingFile.exists()) {
            Toast.makeText(this, "请先录音", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isRecognizing = true;
        updateUIState(RecognitionState.RECOGNIZING);
        tvResult.setText("正在识别...");
        tvConfidence.setText("置信度: --");
        
        executorService.execute(() -> {
            try {
                // 读取录音文件
                byte[] audioData = readAudioFile(recordingFile);
                
                if (recognizer != null) {
                    // ✅ 关键修复：重置识别器状态，避免状态残留
                    recognizer.reset();
                    
                    boolean accepted = recognizer.acceptWaveForm(audioData, audioData.length);
                    String result = accepted ? recognizer.getResult() : recognizer.getPartialResult();
                    
                    // 记录调试信息
                    Log.d(TAG, "手动识别结果JSON: " + result);
                    Log.d(TAG, "音频数据大小: " + audioData.length + " bytes");
                    
                    mainHandler.post(() -> {
                        parseAndDisplayResult(result, accepted);
                        stopManualRecognition();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "识别失败", e);
                mainHandler.post(() -> {
                    tvResult.setText("识别失败: " + e.getMessage());
                    stopManualRecognition();
                });
            }
        });
    }
    
    /**
     * 读取音频文件
     */
    private byte[] readAudioFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return data;
        }
    }
    
    /**
     * 停止手动识别
     */
    private void stopManualRecognition() {
        isRecognizing = false;
        updateUIState(RecognitionState.READY);
    }
    
    /**
     * ✅ 解析并显示识别结果 - 修复：保存原始JSON
     */
    /**
 * ✅ 解析并显示识别结果 - 修复：正确处理partial和final结果
 */
    private void parseAndDisplayResult(String json, boolean isFinal) {
    try {
        // ✅ 保存原始JSON
        lastRawJson = json;
        
        String text = "";
        double confidence = 0.0;
        
        Log.d(TAG, "原始JSON数据: " + json);
        
        try {
            JSONObject jsonObj = new JSONObject(json);
            
            // ✅ 修复：优先检查partial字段（中间结果）
            if (jsonObj.has("partial")) {
                text = jsonObj.getString("partial");
                Log.d(TAG, "获取到partial结果: " + text);
            } 
            // ✅ 再检查text字段（最终结果）
            else if (jsonObj.has("text")) {
                text = jsonObj.getString("text");
                Log.d(TAG, "获取到text结果: " + text);
            }
            
            // ✅ 获取置信度
            if (jsonObj.has("confidence")) {
                confidence = jsonObj.getDouble("confidence");
            } else if (jsonObj.has("conf")) {
                confidence = jsonObj.getDouble("conf");
            }
            
        } catch (JSONException e) {
            Log.w(TAG, "JSON解析失败，使用字符串解析: " + e.getMessage());
            
            // 回退到字符串解析
            if (json.contains("\"partial\":\"")) {
                int start = json.indexOf("\"partial\":\"") + 11;
                int end = json.indexOf("\"", start);
                if (end > start) {
                    text = json.substring(start, end);
                    Log.d(TAG, "字符串解析获取到partial: " + text);
                }
            } else if (json.contains("\"text\":\"")) {
                int start = json.indexOf("\"text\":\"") + 8;
                int end = json.indexOf("\"", start);
                if (end > start) {
                    text = json.substring(start, end);
                    Log.d(TAG, "字符串解析获取到text: " + text);
                }
            }
            
            // 解析置信度
            if (json.contains("\"confidence\":")) {
                int start = json.indexOf("\"confidence\":") + 12;
                int end = json.indexOf(",", start);
                if (end == -1) end = json.indexOf("}", start);
                if (end > start) {
                    try {
                        confidence = Double.parseDouble(json.substring(start, end).trim());
                    } catch (NumberFormatException ex) {
                        confidence = 0.0;
                    }
                }
            } else if (json.contains("\"conf\":")) {
                int start = json.indexOf("\"conf\":") + 7;
                int end = json.indexOf(",", start);
                if (end == -1) end = json.indexOf("}", start);
                if (end > start) {
                    try {
                        confidence = Double.parseDouble(json.substring(start, end).trim());
                    } catch (NumberFormatException ex) {
                        confidence = 0.0;
                    }
                }
            }
        }
        
        // ✅ 保存解析结果
        lastParsedText = text;
        lastConfidence = confidence;
        
        // 构建格式化结果
        SpannableStringBuilder builder = new SpannableStringBuilder();
        
        if (text.isEmpty()) {
            builder.append("未识别到有效语音");
            tvConfidence.setText("置信度: --");
            Log.w(TAG, "未识别到有效语音");
        } else {
            builder.append("识别结果:\n");
            builder.append(text);
            builder.append("\n\n");
            
            // 显示状态
            if (isFinal) {
                builder.append("状态: 最终结果\n");
            } else {
                builder.append("状态: 中间结果\n");
            }
            
            builder.append("置信度: ");
            String confText = confidence > 0 ? String.format("%.4f", confidence) : "未提供";
            builder.append(confText);
            builder.append("\n\n");
            
            builder.append("点击查看原始数据");
            builder.setSpan(new ForegroundColorSpan(Color.parseColor("#666666")), 
                    builder.length() - 8, builder.length(), 
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            tvConfidence.setText(String.format("置信度: %s", confText));
            Log.i(TAG, "识别成功 - 状态: " + (isFinal ? "最终" : "中间") + 
                  ", 文本: " + text + ", 置信度: " + confText);
        }
        
        tvResult.setText(builder);
        
        if (isFinal) {
            tvStat.setText("识别完成");
            tvStat.setTextColor(0xFF4CAF50);
            Toast.makeText(this, "识别完成: " + text, Toast.LENGTH_SHORT).show();
        }
        
    } catch (Exception e) {
        Log.e(TAG, "解析结果失败", e);
        tvResult.setText("解析失败: " + e.getMessage());
    }
    }
    /**
     * ✅ 显示原始数据对话框 - 修复：现在可以显示实际的原始JSON了
     */
    private void showRawDataDialog(String text, String rawJson, double confidence) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_raw_data, null);
        
        TextView tvRecognizedText = dialogView.findViewById(R.id.tv_recognized_text);
        TextView tvConfidenceValue = dialogView.findViewById(R.id.tv_confidence_value);
        TextView tvRawJson = dialogView.findViewById(R.id.tv_raw_json);
        
        tvRecognizedText.setText(text.isEmpty() ? "无识别结果" : text);
        tvConfidenceValue.setText(confidence > 0 ? String.format("%.4f", confidence) : "未提供");
        
        // ✅ 现在显示实际的原始JSON而不是空字符串
        String displayJson = rawJson.isEmpty() ? "{\"error\": \"未保存原始JSON数据\"}" : rawJson;
        tvRawJson.setText(formatJson(displayJson));
        
        // 添加调试信息
        Log.d(TAG, "显示原始数据对话框 - 文本长度: " + text.length() + 
                  ", JSON长度: " + rawJson.length() + 
                  ", 置信度: " + confidence);
        
        new AlertDialog.Builder(this)
                .setTitle("识别原始数据")
                .setView(dialogView)
                .setPositiveButton("复制JSON", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("识别结果JSON", displayJson);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .setCancelable(true)
                .show();
    }
    
    private String formatJson(String json) {
        try {
            int indent = 0;
            StringBuilder formatted = new StringBuilder();
            boolean inQuotes = false;
            
            for (char c : json.toCharArray()) {
                switch (c) {
                    case '{':
                    case '[':
                        formatted.append(c);
                        if (!inQuotes) {
                            formatted.append('\n');
                            indent++;
                            addIndent(formatted, indent);
                        }
                        break;
                    case '}':
                    case ']':
                        if (!inQuotes) {
                            formatted.append('\n');
                            indent--;
                            addIndent(formatted, indent);
                        }
                        formatted.append(c);
                        break;
                    case ',':
                        formatted.append(c);
                        if (!inQuotes) {
                            formatted.append('\n');
                            addIndent(formatted, indent);
                        }
                        break;
                    case ':':
                        formatted.append(c);
                        if (!inQuotes) {
                            formatted.append(' ');
                        }
                        break;
                    case '"':
                        formatted.append(c);
                        inQuotes = !inQuotes;
                        break;
                    default:
                        formatted.append(c);
                }
            }
            return formatted.toString();
        } catch (Exception e) {
            return json;
        }
    }
    
    private void addIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }
    
    private void playRecording() {
        if (recordingFile == null || !recordingFile.exists()) {
            Toast.makeText(this, "没有可播放的录音文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            File wavFile = new File(getExternalCacheDir(), recordingFile.getName().replace(".pcm", ".wav"));
            convertPcmToWav(recordingFile, wavFile);
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(wavFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                mainHandler.post(() -> updateUIState(RecognitionState.READY));
            });
            
            mediaPlayer.start();
            isPlaying = true;
            
            updateUIState(RecognitionState.PLAYING);
            
            Toast.makeText(this, "开始播放录音", Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Log.e(TAG, "播放录音失败", e);
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            resetPlaybackState();
        }
    }
    
    private void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
        if (wavFile.exists()) {
            return;
        }
        
        try (FileInputStream pcmStream = new FileInputStream(pcmFile);
             FileOutputStream wavStream = new FileOutputStream(wavFile)) {
            
            int totalAudioLen = (int) pcmFile.length();
            int totalDataLen = totalAudioLen + 36;
            
            byte[] header = new byte[44];
            
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            writeInt(header, 4, totalDataLen);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            writeInt(header, 16, 16);
            writeShort(header, 20, (short) 1);
            writeShort(header, 22, (short) 1);
            writeInt(header, 24, SAMPLE_RATE);
            writeInt(header, 28, SAMPLE_RATE * 2);
            writeShort(header, 32, (short) 2);
            writeShort(header, 34, (short) 16);
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            writeInt(header, 40, totalAudioLen);
            
            wavStream.write(header);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = pcmStream.read(buffer)) > 0) {
                wavStream.write(buffer, 0, length);
            }
        }
    }
    
    private void writeInt(byte[] array, int position, int value) {
        array[position] = (byte) (value & 0xFF);
        array[position + 1] = (byte) ((value >> 8) & 0xFF);
        array[position + 2] = (byte) ((value >> 16) & 0xFF);
        array[position + 3] = (byte) ((value >> 24) & 0xFF);
    }
    
    private void writeShort(byte[] array, int position, short value) {
        array[position] = (byte) (value & 0xFF);
        array[position + 1] = (byte) ((value >> 8) & 0xFF);
    }
    
    private void stopPlaying() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败", e);
            }
        }
        resetPlaybackState();
    }
    
    private void resetRecordingState() {
        isRecording = false;
        updateUIState(RecognitionState.READY);
    }
    
    private void resetPlaybackState() {
        isPlaying = false;
        updateUIState(RecognitionState.READY);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (isRecording) {
            stopRecording();
        }
        
        if (isPlaying) {
            stopPlaying();
        }
        
        if (isRecognizing) {
            stopManualRecognition();
        }
        
        if (recognizer != null) {
            recognizer.close();
        }
        
        if (model != null) {
            model.close();
        }
        
        if (audioRecord != null) {
            audioRecord.release();
        }
        
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        
        timerHandler.removeCallbacksAndMessages(null);
        volumeHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        
        executorService.shutdown();
        
        if (recordingFile != null && recordingFile.exists()) {
            File wavFile = new File(getExternalCacheDir(), recordingFile.getName().replace(".pcm", ".wav"));
            if (wavFile.exists()) {
                wavFile.delete();
            }
            recordingFile.delete();
        }
    }
}