package com.operit.audioviz;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import java.util.Arrays;

/**
 * 音频采集 + FFT 分析引擎。
 * 输出：48 段对数频谱、192 点波形、总能量、低频节拍脉冲。
 * 支持麦克风（API 26+）与系统音频回放捕获（API 29+，需要 MediaProjection）。
 */
public final class AudioEngine {

    public static final int BANDS = 48;
    public static final int WAVE_POINTS = 192;
    public static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 2048;
    private static final float MIN_FREQ = 40f;
    private static final float MAX_FREQ = 16000f;

    private static final AudioEngine INSTANCE = new AudioEngine();

    public static AudioEngine get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final float[] hann = new float[FFT_SIZE];
    private final float[] spectrum = new float[BANDS];
    private final float[] wave = new float[WAVE_POINTS];
    /** 采集线程独占的频谱临时缓冲 */
    private final float[] tmpSpec = new float[BANDS];

    private volatile boolean running;
    private volatile boolean alive;
    private volatile float level;
    private volatile float bass;
    private volatile float beatEnergy;
    private volatile long lastFrameAt;
    private volatile long lastLoudAt;
    private volatile long frames;
    private volatile float lastRmsDb = -120f;
    private volatile float sensitivity = 1.0f;

    private float bassAvg = 0.05f;
    private long lastBeatAt = 0L;

    private AudioRecord record;
    private Thread worker;

    private AudioEngine() {
        for (int i = 0; i < FFT_SIZE; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAlive() {
        return alive;
    }

    public long lastFrameAt() {
        return lastFrameAt;
    }

    public void setSensitivity(float s) {
        sensitivity = Math.max(0.3f, Math.min(3f, s));
    }

    public float sensitivity() {
        return sensitivity;
    }

    /** 低频节拍脉冲，0~1，随每帧衰减 */
    public float beat() {
        return beatEnergy;
    }

    public float bassLevel() {
        return bass;
    }

    // ---------------------------------------------------------------- 启动

    public synchronized boolean startMicrophone(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        stop();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;
        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .build();
        } catch (Exception e) {
            record = null;
            return false;
        }
        return begin();
    }

    /** 系统音频回放捕获（API 29+），需要已授权的 MediaProjection */
    public synchronized boolean startPlaybackCapture(Context ctx, MediaProjection projection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) return false;
        stop();
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;
        try {
            record = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
        } catch (Exception e) {
            record = null;
            return false;
        }
        return begin();
    }

    private boolean begin() {
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            releaseRecord();
            return false;
        }
        try {
            record.startRecording();
        } catch (Exception e) {
            releaseRecord();
            return false;
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            releaseRecord();
            return false;
        }
        frames = 0;
        lastRmsDb = -120f;
        synchronized (lock) {
            Arrays.fill(tmpSpec, 0f);
            Arrays.fill(spectrum, 0f);
        }
        running = true;
        alive = true;
        worker = new Thread(this::loop, "aurora-audio");
        worker.start();
        return true;
    }

    // ---------------------------------------------------------------- 采集循环

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buf = new short[FFT_SIZE];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, FFT_SIZE);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) {
                if (n < 0) break;
                try {
                    Thread.sleep(4);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            try {
                analyze(buf, n, re, im);
            } catch (Exception ignored) {
            }
        }
        alive = false;
    }

    private void analyze(short[] buf, int n, float[] re, float[] im) {
        final int count = Math.min(n, FFT_SIZE);
        double sum = 0;
        for (int i = 0; i < FFT_SIZE; i++) {
            float s = i < count ? buf[i] / 32768f : 0f;
            sum += s * s;
            re[i] = s * hann[i];
            im[i] = 0f;
        }
        float rms = (float) Math.sqrt(sum / Math.max(1, count));
        float target = Math.min(1f, rms * 9f * sensitivity);
        float cur = level;
        level = target > cur ? cur + (target - cur) * 0.55f : cur * 0.88f;
        if (rms > 0.0045f) lastLoudAt = System.currentTimeMillis();
        frames++;
        lastRmsDb = 20f * (float) Math.log10(rms + 1e-6f);

        FFT.transform(re, im);

        final float norm = 2f / FFT_SIZE;
        final float ratio = MAX_FREQ / MIN_FREQ;
        float bassSum = 0f;
        int bassCount = 0;

        for (int b = 0; b < BANDS; b++) {
            double f0 = MIN_FREQ * Math.pow(ratio, (double) b / BANDS);
            double f1 = MIN_FREQ * Math.pow(ratio, (double) (b + 1) / BANDS);
            int k0 = Math.max(1, (int) (f0 * FFT_SIZE / SAMPLE_RATE));
            int k1 = Math.min(FFT_SIZE / 2, Math.max(k0 + 1, (int) (f1 * FFT_SIZE / SAMPLE_RATE)));
            float mag = 0f;
            for (int k = k0; k < k1; k++) {
                float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * norm;
                if (m > mag) mag = m;
            }
            float db = 20f * (float) Math.log10(mag + 1e-7f);
            float v = (db + 56f) / 56f;
            if (v < 0) v = 0;
            if (v > 1) v = 1;
            v = (float) Math.pow(v, 0.78) * sensitivity;
            if (v > 1f) v = 1f;
            if (b < 8) {
                bassSum += v;
                bassCount++;
            }
            float prev = tmpSpec[b];
            tmpSpec[b] = v > prev ? prev + (v - prev) * 0.75f : prev * 0.87f;
        }

        synchronized (lock) {
            System.arraycopy(tmpSpec, 0, spectrum, 0, BANDS);
        }

        synchronized (lock) {
            for (int i = 0; i < WAVE_POINTS; i++) {
                int idx = (int) ((long) i * count / WAVE_POINTS);
                if (idx >= count) idx = count - 1;
                wave[i] = buf[idx] / 32768f;
            }
        }

        // 低频节拍检测：短时能量显著高于滑动均值即判定为一次 kick
        float bassNow = bassCount > 0 ? bassSum / bassCount : 0f;
        bass = bass * 0.6f + bassNow * 0.4f;
        bassAvg = bassAvg * 0.94f + bassNow * 0.06f;
        long now = System.currentTimeMillis();
        if (bassNow > bassAvg * 1.25f && bassNow > 0.06f && now - lastBeatAt > 180) {
            lastBeatAt = now;
            beatEnergy = 1f;
        } else {
            beatEnergy *= 0.90f;
            if (beatEnergy < 0.01f) beatEnergy = 0f;
        }

        lastFrameAt = now;
    }

    // ---------------------------------------------------------------- 读取

    public void copySpectrum(float[] out) {
        synchronized (lock) {
            System.arraycopy(spectrum, 0, out, 0, Math.min(out.length, BANDS));
        }
    }

    public void copyWave(float[] out) {
        synchronized (lock) {
            System.arraycopy(wave, 0, out, 0, Math.min(out.length, WAVE_POINTS));
        }
    }

    public float level() {
        return level;
    }

    /** 已处理的分析帧数（判断“有没有音频帧进来”） */
    public long frames() {
        return frames;
    }

    /** 最近一帧的 RMS 电平（dBFS） */
    public float lastRmsDb() {
        return lastRmsDb;
    }

    /** 距上次有声音的时间；从未检测到声音时返回一个很大的值 */
    public long silenceMs() {
        long now = System.currentTimeMillis();
        long t = lastLoudAt;
        if (t == 0L) {
            // 一直没声音：从开始有数据算起，避免误判为"刚刚有声音"
            if (lastFrameAt == 0L) return Long.MAX_VALUE;
            return Math.max(0L, now - lastFrameAt) + 60_000L;
        }
        return now - t;
    }

    // ---------------------------------------------------------------- 停止

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        releaseRecord();
        synchronized (lock) {
            Arrays.fill(spectrum, 0f);
            Arrays.fill(wave, 0f);
        }
        level = 0f;
        bass = 0f;
        beatEnergy = 0f;
    }

    private void releaseRecord() {
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                record.release();
            } catch (Exception ignored) {
            }
            record = null;
        }
    }
}