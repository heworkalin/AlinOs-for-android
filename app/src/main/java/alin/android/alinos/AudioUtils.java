package alin.android.alinos;

import android.util.Log;

/**
 * 音频工具类 - 计算音量和分贝
 */
public class AudioUtils {
    private static final String TAG = "AudioUtils";
    private static final int MAX_AMPLITUDE = 32767; // 16位PCM最大振幅
    private static final double REFERENCE_DB = 0.1; // 参考分贝（最小可闻声压）
    
    /**
     * 计算分贝值（dB）
     */
    public static double calculateDecibels(short[] audioData) {
        if (audioData == null || audioData.length == 0) return 0;
        
        // 计算均方根（RMS）
        double sum = 0;
        for (short s : audioData) {
            sum += s * s;
        }
        double rms = Math.sqrt(sum / audioData.length);
        
        // 防止除以0
        if (rms < 0.0001) return 0;
        
        // 计算分贝：20 * log10(rms / 参考值)
        double db = 20 * Math.log10(rms / REFERENCE_DB);
        return Math.max(0, Math.min(100, db)); // 限制在0-100dB
    }
    
    /**
     * 计算音量进度（0-100）
     */
    public static int calculateVolumeLevel(short[] audioData) {
        if (audioData == null || audioData.length == 0) return 0;
        
        // 计算最大振幅
        int max = 0;
        for (short s : audioData) {
            int abs = Math.abs(s);
            if (abs > max) max = abs;
        }
        
        // 转换为进度（0-100）
        return (int) ((float) max / MAX_AMPLITUDE * 100);
    }
}