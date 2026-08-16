package alin.android.alinos.voice.core;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 音频公共工具（ASR / TTS / KWS 三模块复用）。
 *  - 录音封装（AudioRecord 启停）
 *  - PCM 字节流 → float 数组（归一化）
 *  - RMS 能量计算（静音判断）
 *  - PCM → WAV 文件写入
 */
public class AudioUtil {

    public static final int SAMPLE_RATE = 16000;

    private AudioUtil() {}

    /** 创建 16kHz/16bit/单声道 AudioRecord */
    public static AudioRecord createRecorder() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        return rec.getState() == AudioRecord.STATE_INITIALIZED ? rec : null;
    }

    /** 16bit little-endian PCM byte[] → float[]，范围 [-1.0, 1.0] */
    public static float[] bytesToFloat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new float[0];
        int sampleCount = bytes.length / 2;
        float[] out = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short val = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
            out[i] = val / 32768.0f;
        }
        return out;
    }

    /** RMS 能量（0.02 以下通常视为静音） */
    public static float computeRms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0f;
        double sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) {
            short v = (short) (((pcm[i * 2 + 1] & 0xFF) << 8) | (pcm[i * 2] & 0xFF));
            double s = v / 32768.0;
            sum += s * s;
        }
        return (float) Math.sqrt(sum / n);
    }

    /** PCM(16kHz/16bit/mono) 写入 WAV 文件 */
    public static void writeWav(File f, byte[] pcm) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write("RIFF".getBytes("US-ASCII"));
        writeLE(fos, 36 + pcm.length);
        fos.write("WAVE".getBytes("US-ASCII"));
        fos.write("fmt ".getBytes("US-ASCII"));
        writeLE(fos, 16);
        writeLE(fos, 1);          // PCM
        writeLE(fos, 1);          // mono
        writeLE(fos, SAMPLE_RATE);
        writeLE(fos, SAMPLE_RATE * 2); // byte rate
        writeLE(fos, 2);          // block align
        writeLE(fos, 16);         // bits
        fos.write("data".getBytes("US-ASCII"));
        writeLE(fos, pcm.length);
        fos.write(pcm);
        fos.close();
    }

    private static void writeLE(FileOutputStream fos, int v) throws Exception {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
        fos.write((v >> 16) & 0xff);
        fos.write((v >> 24) & 0xff);
    }
}
