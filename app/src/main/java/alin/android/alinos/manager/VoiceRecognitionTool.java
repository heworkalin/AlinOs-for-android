package alin.android.alinos.manager;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关键修复：
 * 1. 新增 resetAllStates() 方法，支持切换界面时强制重置所有状态
 * 2. 强化状态同步，确保所有分支都能正确重置 isRecognizing/isThreadRunning
 * 3. 优化线程终止逻辑，避免线程残留导致的状态锁死
 * 4. 完善资源释放流程，确保切换时无资源残留
 */
public class VoiceRecognitionTool {
    private static final String TAG = "VoiceRecognitionTool";
    private static VoiceRecognitionTool instance;
    
    // 严格匹配 Vosk 要求：16kHz 采样率 + 16bit PCM + 单声道（小端）
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private String currentModelName = "vosk-model-small-cn-0.22";
    
    // 核心组件（volatile 保证多线程可见性）
    private volatile Model model;
    private volatile Recognizer recognizer;
    private AudioRecord audioRecord;
    private MediaPlayer mediaPlayer;
    
    // 状态管理：用 AtomicBoolean 替代 volatile，确保多线程原子操作
    private final AtomicBoolean isModelLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isRecognizing = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isThreadRunning = new AtomicBoolean(false); // 线程运行状态
    
    // 文件管理
    private File recordingFile;
    private FileOutputStream audioOutputStream;
    
    // 线程管理
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread recognitionThread; // 录音识别线程
    
    // 录音参数
    private int bufferSize;
    private long recordingStartTime = 0;
    
    // 回调接口
    private RecognitionCallback recognitionCallback;
    
    // 上下文
    private Context context;
    
    /**
     * 识别结果回调接口
     */
    public interface RecognitionCallback {
        void onModelInitialized();
        void onModelError(String errorMessage);
        void onRecordingStarted();
        void onRecordingStopped(long duration, String filePath);
        void onVolumeUpdated(double decibels, int progress);
        void onPartialResult(String text, String jsonRaw);
        void onFinalResult(String text, String jsonRaw);
        void onRecognitionError(String errorMessage);
        void onRecordingTimeUpdate(int seconds);
        void onPlaybackState(boolean isPlaying, String filePath);
    }
    
    /**
     * 单例获取
     */
    public static synchronized VoiceRecognitionTool getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceRecognitionTool(context.getApplicationContext());
        }
        return instance;
    }
    
    private VoiceRecognitionTool(Context context) {
        this.context = context;
    }
    
    /**
     * 设置回调
     */
    public void setRecognitionCallback(RecognitionCallback callback) {
        this.recognitionCallback = callback;
    }
    
    /**
     * 初始化模型（确保单线程初始化）
     */
    public void initializeModel(String modelName, RecognitionCallback callback) {
        if (callback != null) {
            this.recognitionCallback = callback;
        }
        
        if (isModelLoaded.get() && currentModelName.equals(modelName)) {
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onModelInitialized();
                }
            });
            return;
        }
        
        currentModelName = modelName;
        
        executorService.execute(() -> {
            try {
                // 复制模型文件（确保模型文件完整）
                ModelManager.copyModelToInternalStorage(context, currentModelName);
                File modelDir = ModelManager.getModelDir(context, currentModelName);
                
                // 确保之前的资源已释放
                releaseResources();
                
                // 重新初始化模型和识别器
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, SAMPLE_RATE);
                recognizer.setWords(true); // 输出分词结果
                
                isModelLoaded.set(true);
                Log.i(TAG, "模型初始化成功: " + currentModelName + "，采样率: " + SAMPLE_RATE);
                
                notifyCallback(() -> {
                    if (recognitionCallback != null) {
                        recognitionCallback.onModelInitialized();
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "模型初始化失败", e);
                notifyCallback(() -> {
                    if (recognitionCallback != null) {
                        recognitionCallback.onModelError("模型加载失败: " + e.getMessage());
                    }
                });
            }
        });
    }
    
    /**
     * 开始录音和识别
     */
    public boolean startRecording() {
        // 三重检查：防止重复启动（增加状态日志便于调试）
        Log.d(TAG, "startRecording() 状态检查：isModelLoaded=" + isModelLoaded.get() 
                + ", isRecording=" + isRecording.get() 
                + ", isThreadRunning=" + isThreadRunning.get());
        
        if (!isModelLoaded.get()) {
            Log.e(TAG, "无法启动录音：模型未加载");
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("模型未加载，请稍后");
                }
            });
            return false;
        }
        if (isRecording.get() || isThreadRunning.get()) {
            Log.e(TAG, "无法启动录音：当前正在录音或线程未终止");
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("当前正在操作，请稍后");
                }
            });
            return false;
        }
        
        try {
            // 计算 buffer 大小
            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                bufferSize = SAMPLE_RATE * 2; // fallback：2秒缓冲区
            }
            bufferSize = (bufferSize + 1023) & ~1023; // 向上取整到 1024 的倍数
            Log.d(TAG, "AudioRecord 缓冲区大小: " + bufferSize);
            
            // 初始化 AudioRecord（非阻塞模式）
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2 // 双缓冲
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("录音设备初始化失败（权限不足或设备不支持）");
            }
            
            // 创建录音文件
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            recordingFile = AudioFileUtils.createPcmFile(context, timestamp);
            audioOutputStream = new FileOutputStream(recordingFile);
            Log.d(TAG, "录音文件路径: " + recordingFile.getAbsolutePath());
            
            // 启动录音
            audioRecord.startRecording();
            isRecording.set(true);
            isThreadRunning.set(true);
            isRecognizing.set(true); // 标记开始识别
            recordingStartTime = System.currentTimeMillis();
            
            // 重置识别器
            if (recognizer != null) {
                recognizer.reset();
            }
            
            // 启动识别线程
            recognitionThread = new Thread(new RecognitionRunnable(), "VoiceRecognitionThread");
            recognitionThread.start();
            
            // 启动录音计时
            startRecordingTimer();
            
            Log.i(TAG, "录音启动成功");
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecordingStarted();
                }
            });
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            resetAllStates(); // 异常时强制重置所有状态
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("录音启动失败: " + e.getMessage());
                }
            });
            return false;
        }
    }
    
    /**
     * 核心修复：识别线程逻辑（非阻塞读取 + 主动终止 + 状态同步）
     */
    private class RecognitionRunnable implements Runnable {
        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize];
            short[] shortBuffer = new short[bufferSize / 2];
            
            Log.d(TAG, "识别线程启动，循环条件：" + isRecording.get() + " | " + isThreadRunning.get());
            
            // 循环条件：增加 Thread.interrupted() 检查，响应线程中断
            while (isRecording.get() && isThreadRunning.get() && recognizer != null && !Thread.interrupted()) {
                try {
                    // 非阻塞读取音频数据
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
                    
                    switch (bytesRead) {
                        case AudioRecord.ERROR_INVALID_OPERATION:
                        case AudioRecord.ERROR_BAD_VALUE:
                        case AudioRecord.ERROR:
                            Log.e(TAG, "音频读取错误，bytesRead=" + bytesRead);
                            break;
                        case AudioRecord.ERROR_DEAD_OBJECT:
                            Log.e(TAG, "录音设备已销毁，退出循环");
                            isRecording.set(false);
                            break;
                        case 0:
                            // 无数据时短暂休眠，避免 CPU 空转
                            Thread.sleep(10);
                            break;
                        default:
                            // 写入文件（可选）
                            if (audioOutputStream != null) {
                                audioOutputStream.write(buffer, 0, bytesRead);
                            }
                            
                            // 调用识别方法
                            try {
                                boolean accepted = recognizer.acceptWaveForm(buffer, bytesRead);
                                if (accepted) {
                                    // 识别到完整结果：主动停止录音
                                    String finalResult = recognizer.getResult();
                                    Log.d(TAG, "识别到完整语句，主动终止录音：" + finalResult);
                                    notifyCallback(() -> {
                                        if (recognitionCallback != null) {
                                            recognitionCallback.onFinalResult(parseTextFromJson(finalResult), finalResult);
                                        }
                                    });
                                    // 主动停止录音和线程
                                    stopRecording(); 
                                    break; // 跳出循环，终止线程
                                } else {
                                    // 中间结果，正常回调
                                    String partialResult = recognizer.getPartialResult();
                                    Log.d(TAG, "中间识别结果：" + partialResult);
                                    notifyCallback(() -> {
                                        if (recognitionCallback != null) {
                                            recognitionCallback.onPartialResult(parseTextFromJson(partialResult), partialResult);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "识别过程异常", e);
                            }
                            
                            // 更新音量
                            updateVolumeLevel(buffer, bytesRead, shortBuffer);
                            break;
                    }
                    
                } catch (InterruptedException e) {
                    // 线程被中断，正常退出
                    Log.d(TAG, "识别线程被中断，退出循环");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "识别线程异常", e);
                    break;
                }
            }
            
            Log.d(TAG, "识别线程循环退出，开始收尾");
            
            // 收尾：如果是用户取消（未识别到完整结果），获取最终结果
            try {
                if (recognizer != null && isThreadRunning.get()) {
                    String finalResult = recognizer.getFinalResult();
                    Log.d(TAG, "最终识别结果（用户取消/超时）：" + finalResult);
                    notifyCallback(() -> {
                        if (recognitionCallback != null) {
                            recognitionCallback.onFinalResult(parseTextFromJson(finalResult), finalResult);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "获取最终结果失败", e);
                notifyCallback(() -> {
                    if (recognitionCallback != null) {
                        recognitionCallback.onRecognitionError("获取识别结果失败: " + e.getMessage());
                    }
                });
            } finally {
                // 强制重置所有状态，确保无残留（关键修复）
                resetAllStates();
                Log.d(TAG, "识别线程终止，所有状态已重置");
            }
        }
    }
    
    /**
     * 停止录音（核心修复：确保所有状态都被重置）
     */
    public void stopRecording() {
        Log.d(TAG, "调用 stopRecording()，当前状态：" + isRecording.get() + " | " + isThreadRunning.get());
        
        // 1. 立即标记状态为停止（原子操作）
        isRecording.set(false);
        isRecognizing.set(false); // 确保识别状态重置
        
        // 2. 强制停止 AudioRecord
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                    Log.d(TAG, "AudioRecord 已停止");
                }
            } catch (Exception e) {
                Log.e(TAG, "停止 AudioRecord 失败", e);
            }
        }
        
        // 3. 强制中断线程（如果线程还在运行）
        if (recognitionThread != null && recognitionThread.isAlive()) {
            try {
                Log.d(TAG, "强制中断识别线程");
                recognitionThread.interrupt(); // 中断线程
                recognitionThread.join(1000); // 等待 1 秒，避免卡死
                if (recognitionThread.isAlive()) {
                    Log.w(TAG, "线程未正常终止，强制标记为停止");
                    isThreadRunning.set(false);
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "等待线程终止失败", e);
                Thread.currentThread().interrupt();
            }
        }
        
        // 4. 停止识别器
        if (recognizer != null) {
            recognizer.reset(); // 重置识别器，清空缓存
        }
        
        // 5. 回调录音停止事件
        long duration = System.currentTimeMillis() - recordingStartTime;
        Log.d(TAG, "录音停止，时长: " + (duration / 1000) + "秒");
        notifyCallback(() -> {
            if (recognitionCallback != null) {
                recognitionCallback.onRecordingStopped(duration, recordingFile != null ? recordingFile.getAbsolutePath() : "");
            }
        });
    }
    
    /**
     * 取消识别（强制终止所有操作，不等待结果）
     */
    public void cancelRecognition() {
        Log.d(TAG, "调用 cancelRecognition()，强制终止");
        
        // 1. 标记所有状态为停止
        resetAllStates(); // 直接调用全局重置
        
        // 2. 强制停止 AudioRecord
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "取消时停止 AudioRecord 失败", e);
            }
        }
        
        // 3. 强制中断线程
        if (recognitionThread != null && recognitionThread.isAlive()) {
            try {
                recognitionThread.interrupt();
                recognitionThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "取消时中断线程失败", e);
                Thread.currentThread().interrupt();
            }
        }
        
        // 4. 释放识别器资源
        if (recognizer != null) {
            recognizer.reset();
        }
        
        Log.d(TAG, "取消识别完成");
        
        // 回调空结果（避免用户等待）
        notifyCallback(() -> {
            if (recognitionCallback != null) {
                recognitionCallback.onFinalResult("", "{}");
            }
        });
    }
    
    /**
     * 【新增核心方法】切换输入界面时调用，强制重置所有状态和资源
     * 确保切换后可以立即启动下一轮识别
     */
    public void resetAllStates() {
        Log.d(TAG, "执行 resetAllStates()，强制重置所有状态");
        
        // 1. 重置所有状态标记（原子操作）
        isRecording.set(false);
        isRecognizing.set(false);
        isThreadRunning.set(false);
        recordingStartTime = 0;
        
        // 2. 释放 AudioRecord
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 AudioRecord 失败", e);
            }
            audioRecord = null;
        }
        
        // 3. 关闭文件流
        if (audioOutputStream != null) {
            try {
                audioOutputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭录音文件失败", e);
            }
            audioOutputStream = null;
        }
        
        // 4. 释放 MediaPlayer（如果正在播放）
        if (mediaPlayer != null && isPlaying.get()) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 MediaPlayer 失败", e);
            }
            mediaPlayer = null;
            isPlaying.set(false);
        }
        
        // 5. 重置识别器（清空缓存）
        if (recognizer != null) {
            recognizer.reset();
        }
        
        // 6. 清空录音文件引用
        recordingFile = null;
        
        Log.d(TAG, "所有状态重置完成，当前状态：" 
                + "isRecording=" + isRecording.get() 
                + ", isRecognizing=" + isRecognizing.get() 
                + ", isThreadRunning=" + isThreadRunning.get());
    }
    
    /**
     * 解析识别结果
     */
    private String parseTextFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONObject jsonObj = new JSONObject(json);
            if (jsonObj.has("text")) {
                return jsonObj.getString("text").trim();
            } else if (jsonObj.has("partial")) {
                return jsonObj.getString("partial").trim();
            } else {
                Log.w(TAG, "识别结果无文本字段：" + json);
                return "";
            }
        } catch (JSONException e) {
            Log.e(TAG, "解析识别结果失败", e);
            return "";
        }
    }
    
    /**
     * 重置录音状态（内部使用，已被 resetAllStates() 替代）
     * 保留用于兼容旧逻辑，实际调用会转发到 resetAllStates()
     */
    private void resetRecordingState() {
        resetAllStates(); // 直接调用全局重置
    }
    
    /**
     * 释放所有资源（退出应用时调用）
     */
    private void releaseResources() {
        Log.d(TAG, "执行 releaseResources()");
        
        resetAllStates(); // 先重置所有状态
        
        // 释放识别器
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception e) {
                Log.e(TAG, "释放 Recognizer 失败", e);
            }
            recognizer = null;
        }
        
        // 释放模型
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                Log.e(TAG, "释放 Model 失败", e);
            }
            model = null;
        }
        
        isModelLoaded.set(false);
        Log.d(TAG, "所有资源释放完成");
    }
    
    // ---------------------- 以下方法保持不变 ----------------------
    public void releaseRecognitionResources() {
        resetAllStates();
    }
    
    public void playRecording(String filePath) {
        if (isPlaying.get()) {
            stopPlayback();
            return;
        }
        
        try {
            File fileToPlay = (filePath != null) ? new File(filePath) : recordingFile;
            if (fileToPlay == null || !fileToPlay.exists()) {
                throw new IOException("录音文件不存在");
            }
            
            File wavFile = AudioFileUtils.convertPcmToWavIfNeeded(context, fileToPlay);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(wavFile.getAbsolutePath());
            mediaPlayer.prepare();
            
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying.set(false);
                notifyCallback(() -> {
                    if (recognitionCallback != null) {
                        recognitionCallback.onPlaybackState(false, wavFile.getAbsolutePath());
                    }
                });
            });
            
            mediaPlayer.start();
            isPlaying.set(true);
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onPlaybackState(true, wavFile.getAbsolutePath());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "播放失败", e);
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("播放失败: " + e.getMessage());
                }
            });
        }
    }
    
    public void stopPlayback() {
        if (mediaPlayer != null && isPlaying.get()) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败", e);
            }
            isPlaying.set(false);
        }
    }
    
    public void recognizeAudioFile(File audioFile) {
        if (!isModelLoaded.get()) {
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("模型未加载");
                }
            });
            return;
        }
        
        if (audioFile == null || !audioFile.exists()) {
            notifyCallback(() -> {
                if (recognitionCallback != null) {
                    recognitionCallback.onRecognitionError("音频文件不存在");
                }
            });
            return;
        }
        
        isRecognizing.set(true);
        
        executorService.execute(() -> {
            try {
                byte[] audioData = AudioFileUtils.readAudioFile(audioFile);
                if (recognizer == null) {
                    throw new RuntimeException("识别器未初始化");
                }
                
                recognizer.reset();
                boolean accepted = recognizer.acceptWaveForm(audioData, audioData.length);
                String resultJson = accepted ? recognizer.getResult() : recognizer.getPartialResult();
                String resultText = parseTextFromJson(resultJson);
                parseAndNotifyResult(resultText, resultJson, accepted);
                
            } catch (Exception e) {
                Log.e(TAG, "文件识别失败", e);
                notifyCallback(() -> {
                    if (recognitionCallback != null) {
                        recognitionCallback.onRecognitionError("识别失败: " + e.getMessage());
                    }
                });
            } finally {
                isRecognizing.set(false); // 确保识别状态重置
            }
        });
    }
    
    /**
     * 音量更新
     */
    private void updateVolumeLevel(byte[] buffer, int bytesRead, short[] shortBuffer) {
        int shortLen = bytesRead / 2;
        for (int i = 0; i < shortLen; i++) {
            shortBuffer[i] = (short) ((buffer[2 * i] & 0xFF) | (buffer[2 * i + 1] << 8));
        }
        
        double decibels = calculateDecibels(shortBuffer, shortLen);
        int progress = (int) Math.min(100, (decibels + 100) / 1.5);
        
        notifyCallback(() -> {
            if (recognitionCallback != null) {
                recognitionCallback.onVolumeUpdated(decibels, progress);
            }
        });
    }
    
    /**
     * 分贝计算
     */
    private double calculateDecibels(short[] samples, int length) {
        if (samples == null || length == 0) {
            return -100;
        }
        
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += samples[i] * samples[i];
        }
        double rms = Math.sqrt(sum / length);
        if (rms < 0.0001) {
            return -100;
        }
        return 20 * Math.log10(rms / 32768.0);
    }
    
    /**
     * 录音计时
     */
    private void startRecordingTimer() {
        Handler timerHandler = new Handler();
        Runnable timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording.get()) {
                    int seconds = (int) ((System.currentTimeMillis() - recordingStartTime) / 1000);
                    notifyCallback(() -> {
                        if (recognitionCallback != null) {
                            recognitionCallback.onRecordingTimeUpdate(seconds);
                        }
                    });
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }
    
    private void parseAndNotifyResult(String text, String json, boolean isFinal) {
        Log.d(TAG, "解析结果：文本=" + text + ", JSON=" + json);
        
        notifyCallback(() -> {
            if (recognitionCallback != null) {
                if (isFinal) {
                    recognitionCallback.onFinalResult(text, json);
                } else {
                    recognitionCallback.onPartialResult(text, json);
                }
            }
        });
    }
    
    private void notifyCallback(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
    
    // Getter/Setter
    public boolean isModelLoaded() { return isModelLoaded.get(); }
    public boolean isRecording() { return isRecording.get(); }
    public boolean isRecognizing() { return isRecognizing.get(); }
    public boolean isPlaying() { return isPlaying.get(); }
    public boolean isThreadRunning() { return isThreadRunning.get(); }
    public File getLastRecordingFile() { return recordingFile; }
    public String getCurrentModelName() { return currentModelName; }
    public void setCurrentModelName(String modelName) { this.currentModelName = modelName; }
    
    /**
     * 释放所有资源（退出应用时调用）
     */
    public void release() {
        Log.d(TAG, "执行全局 release()");
        releaseResources();
        executorService.shutdown();
        Log.i(TAG, "全局资源释放完成");
    }
    
    // 内部工具类（保持不变）
    public static class ModelManager {
        private static final String TAG = "ModelManager";
        
        public static void copyModelToInternalStorage(Context context, String modelName) throws IOException {
            File modelDir = getModelDir(context, modelName);
            if (modelDir.exists() && modelDir.listFiles() != null && modelDir.listFiles().length > 0) {
                Log.d(TAG, "模型已存在，跳过复制");
                return;
            }
            if (!modelDir.mkdirs()) {
                throw new IOException("创建模型目录失败: " + modelDir.getAbsolutePath());
            }
            copyAssetsRecursive(context, modelName, modelDir);
        }
        
        public static File getModelDir(Context context, String modelName) {
            return new File(context.getFilesDir(), "models/" + modelName);
        }
        
        private static void copyAssetsRecursive(Context context, String assetPath, File targetDir) throws IOException {
            String[] assetList = context.getAssets().list(assetPath);
            if (assetList == null || assetList.length == 0) return;
            
            for (String assetName : assetList) {
                String currentAssetPath = assetPath + "/" + assetName;
                File targetFile = new File(targetDir, assetName);
                
                try {
                    InputStream is = context.getAssets().open(currentAssetPath);
                    copyFile(is, targetFile);
                    is.close();
                } catch (IOException e) {
                    if (!targetFile.mkdir()) {
                        throw new IOException("创建目录失败: " + targetFile.getAbsolutePath());
                    }
                    copyAssetsRecursive(context, currentAssetPath, targetFile);
                }
            }
        }
        
        private static void copyFile(InputStream is, File targetFile) throws IOException {
            try (OutputStream os = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        }
    }
    
    public static class AudioFileUtils {
        private static final String TAG = "AudioFileUtils";
        private static final int SAMPLE_RATE = 16000;
        
        public static File createPcmFile(Context context, String timestamp) {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = context.getCacheDir();
                Log.w(TAG, "外部存储不可用，使用内部缓存目录");
            }
            return new File(cacheDir, "recording_" + timestamp + ".pcm");
        }
        
        public static File convertPcmToWavIfNeeded(Context context, File pcmFile) throws IOException {
            if (pcmFile.getName().endsWith(".wav")) return pcmFile;
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) cacheDir = context.getCacheDir();
            File wavFile = new File(cacheDir, pcmFile.getName().replace(".pcm", ".wav"));
            if (!wavFile.exists()) {
                convertPcmToWav(pcmFile, wavFile);
            }
            return wavFile;
        }
        
        public static void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
            convertPcmToWav(pcmFile, wavFile, SAMPLE_RATE);
        }
        
        public static void convertPcmToWav(File pcmFile, File wavFile, int sampleRate) throws IOException {
            if (wavFile.exists()) return;
            
            try (FileInputStream fis = new FileInputStream(pcmFile);
                 FileOutputStream fos = new FileOutputStream(wavFile)) {
                
                int totalAudioLen = (int) pcmFile.length();
                int totalDataLen = totalAudioLen + 36;
                byte[] header = new byte[44];
                
                header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
                writeInt(header, 4, totalDataLen);
                header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
                header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
                writeInt(header, 16, 16);
                writeShort(header, 20, (short) 1);
                writeInt(header, 24, sampleRate);
                writeInt(header, 28, sampleRate * 2);
                writeShort(header, 32, (short) 2);
                writeShort(header, 34, (short) 16);
                header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
                writeInt(header, 40, totalAudioLen);
                
                fos.write(header);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }
        }
        
        public static byte[] readAudioFile(File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                return data;
            }
        }
        
        private static void writeInt(byte[] array, int pos, int value) {
            array[pos] = (byte) (value & 0xFF);
            array[pos+1] = (byte) ((value >> 8) & 0xFF);
            array[pos+2] = (byte) ((value >> 16) & 0xFF);
            array[pos+3] = (byte) ((value >> 24) & 0xFF);
        }
        
        private static void writeShort(byte[] array, int pos, short value) {
            array[pos] = (byte) (value & 0xFF);
            array[pos+1] = (byte) ((value >> 8) & 0xFF);
        }
    }
}