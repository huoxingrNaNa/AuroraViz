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

    // ---- 立体声：左右声道各自独立做一次频谱分析 ----
    private final float[] specL = new float[BANDS];
    private final float[] specR = new float[BANDS];
    private final float[] tmpSpecL = new float[BANDS];
    private final float[] tmpSpecR = new float[BANDS];
    private final float[] waveL = new float[WAVE_POINTS];
    private final float[] waveR = new float[WAVE_POINTS];
    /** 本次采集是否真的拿到两个声道的信号（单声道来源时为 false，左右共用一份数据） */
    private volatile boolean stereoCapture;
    // ---- 临时诊断：每秒输出一次左右声道电平，用于判断"双声道为什么看不出差别" ----
    /** 声道诊断日志开关（默认关，排查双声道问题时才打开，避免一直刷 logcat） */
    private volatile boolean stereoLog = false;
    private long lastStereoLogMs = 0L;
    private double accL = 0, accR = 0, accN = 0;
    /** 采集线程独占的频谱临时缓冲 */
    private final float[] tmpSpec = new float[BANDS];

    private volatile boolean running;
    private volatile boolean fileMode;
    private volatile PcmSink pcmSink;
    private volatile int captureChannels = 1;
    private volatile boolean alive;
    private volatile float level;
    private volatile float bass;
    private volatile float beatEnergy;
    private volatile long lastFrameAt;
    private volatile long lastLoudAt;
    private volatile long frames;
    private volatile float lastRmsDb = -120f;
    private volatile float sensitivity = 1.0f;
    /** 自动灵敏度：把最强频段拉到目标的固定高度，用户滑块变成整体偏好 */
    private volatile boolean autoSensOn = true;
    private volatile float autoGain = 1f;
    private long lastAgcMs = 0L;
    private static final float AGC_TARGET = 0.85f;
    private static final float AGC_MIN = 0.40f;
    private static final float AGC_MAX = 2.50f;

    private float bassAvg = 0.05f;
    private long lastBeatAt = 0L;

    private AudioRecord record;
    private Thread worker;

    private AudioEngine() {
        for (int i = 0; i < FFT_SIZE; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
    }

    /**
     * 自动增益：跟踪“乘用户灵敏度之前”的原始峰值，算出把峰值拉到 AGC_TARGET 所需增益。
     * 每 150ms 更新一次并做一阶平滑，避免画面呼吸；信号太弱时不调整，避免把底噪放大。
     */
    private void updateAgc(float rawMax) {
        if (!autoSensOn) {
            autoGain = 1f;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAgcMs < 150L) return;
        lastAgcMs = now;
        if (!(rawMax > 0.05f)) return;
        float target = AGC_TARGET / rawMax;
        if (target < AGC_MIN) target = AGC_MIN;
        if (target > AGC_MAX) target = AGC_MAX;
        autoGain += (target - autoGain) * 0.35f;
    }

    public void setStereoLog(boolean on) {
        stereoLog = on;
        lastStereoLogMs = 0L;
        accL = 0;
        accR = 0;
        accN = 0;
    }

    public boolean isStereoLog() {
        return stereoLog;
    }

    public void setAutoSens(boolean on) {
        autoSensOn = on;
        if (!on) autoGain = 1f;
    }

    public boolean isAutoSens() {
        return autoSensOn;
    }

    public float autoGainValue() {
        return autoGain;
    }

    public boolean isRunning() {
        return running || fileMode;
    }

    // ---------------------------------------------------------------- 文件音源
    // 导入音频渲染/导出时，由 FileAudioPlayer 解码出 PCM 后直接喂给这里分析，
    // 这样 VisualizerView 无需改动（它只认 AudioEngine 的单例数据）。

    private final float[] fileRe = new float[FFT_SIZE];
    private final float[] fileIm = new float[FFT_SIZE];

    /** 采集 PCM 出口：录制当前监听画面时用来编码音轨 */
    public interface PcmSink {
        void onPcm(short[] pcm, int length, int sampleRate, int channels);
    }

    public void setPcmSink(PcmSink sink) {
        pcmSink = sink;
    }

    /** 进入文件播放模式（让 visualize 认为有实时数据） */
    public void enterFileMode() {
        fileMode = true;
        lastFrameAt = System.currentTimeMillis();
    }

    public void exitFileMode() {
        fileMode = false;
        synchronized (lock) {
            Arrays.fill(spectrum, 0f);
            Arrays.fill(wave, 0f);
        }
    }

    /** 外部 PCM（16bit 单声道）喂入分析，sampleRate 为源采样率 */
    public void feedExternal(short[] buf, int n, int sampleRate) {
        try {
            analyze(buf, n, fileRe, fileIm, sampleRate);
        } catch (Exception ignored) {
        }
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
        record = createRecord(MediaRecorder.AudioSource.MIC, null);
        if (record == null) return false;
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
        record = createRecord(0, config);
        if (record == null) return false;
        return begin();
    }

    /**
     * 优先以立体声采集（音轨更接近原声），设备/来源不支持时自动回退单声道。
     */
    private AudioRecord createRecord(int audioSource, AudioPlaybackCaptureConfiguration config) {
        int[] masks = new int[]{AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};
        for (int mask : masks) {
            int ch = mask == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) continue;
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(mask)
                    .build();
            try {
                AudioRecord.Builder b = new AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(minBuf * 2);
                if (config != null) {
                    b.setAudioPlaybackCaptureConfig(config);
                } else {
                    b.setAudioSource(audioSource);
                }
                AudioRecord r = b.build();
                if (r != null && r.getState() == AudioRecord.STATE_INITIALIZED) {
                    captureChannels = ch;
                    if (stereoLog) {
                        android.util.Log.i("AuroraViz", "record ok ch=" + ch
                                + (config != null ? " playback" : " src=" + audioSource));
                    }
                    return r;
                }
                if (r != null) r.release();
            } catch (Exception ignored) {
            }
        }
        return null;
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
            Arrays.fill(tmpSpecL, 0f);
            Arrays.fill(tmpSpecR, 0f);
            Arrays.fill(specL, 0f);
            Arrays.fill(specR, 0f);
            Arrays.fill(waveL, 0f);
            Arrays.fill(waveR, 0f);
        }
        stereoCapture = false;
        running = true;
        alive = true;
        worker = new Thread(this::loop, "aurora-audio");
        worker.start();
        return true;
    }

    // ---------------------------------------------------------------- 采集循环

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final int ch = Math.max(1, captureChannels);
        short[] buf = new short[FFT_SIZE * ch];
        short[] mono = ch > 1 ? new short[FFT_SIZE] : null;
        short[] scratch = ch > 1 ? new short[FFT_SIZE] : null;
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
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
            int framesRead = n / ch;
            if (framesRead <= 0) continue;
            short[] forFft = buf;
            if (ch > 1) {
                for (int i = 0; i < framesRead; i++) {
                    int sum = 0;
                    for (int c = 0; c < ch; c++) sum += buf[i * ch + c];
                    mono[i] = (short) (sum / ch);
                }
                forFft = mono;
            }
            try {
                analyze(forFft, framesRead, re, im, SAMPLE_RATE);
            } catch (Exception ignored) {
            }
            if (ch > 1 && scratch != null) {
                try {
                    analyzeStereo(buf, framesRead, ch, re, im, scratch);
                } catch (Exception e) {
                    mirrorMonoToStereo();
                }
            } else {
                mirrorMonoToStereo();
            }
            PcmSink sink = pcmSink;
            if (sink != null) {
                try {
                    sink.onPcm(buf, framesRead * ch, SAMPLE_RATE, ch);
                } catch (Throwable ignored) {
                }
            }
        }
        alive = false;
    }

    private void analyze(short[] buf, int n, float[] re, float[] im, int sampleRate) {
        final int count = Math.min(n, FFT_SIZE);
        float rawMax = 0f;
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
            int k0 = Math.max(1, (int) (f0 * FFT_SIZE / sampleRate));
            int k1 = Math.min(FFT_SIZE / 2, Math.max(k0 + 1, (int) (f1 * FFT_SIZE / sampleRate)));
            float mag = 0f;
            for (int k = k0; k < k1; k++) {
                float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * norm;
                if (m > mag) mag = m;
            }
            // 低频：多个 band 会挤进同一个 bin（bin 宽度 21.5Hz @2048），直接取最大值会让
            // 相邻柱完全等高。这里再按“该 band 的中心频率”在相邻两个 bin 间做线性插值，
            // 保证每个 band 都有自己的值。
            double fc = (f0 + f1) * 0.5;
            double pos = fc * FFT_SIZE / sampleRate;
            if (pos < 1) pos = 1;
            if (pos > FFT_SIZE / 2 - 2) pos = FFT_SIZE / 2 - 2;
            int pi = (int) pos;
            float frac = (float) (pos - pi);
            float m0 = (float) Math.sqrt(re[pi] * re[pi] + im[pi] * im[pi]) * norm;
            float m1 = (float) Math.sqrt(re[pi + 1] * re[pi + 1] + im[pi + 1] * im[pi + 1]) * norm;
            float interp = m0 + (m1 - m0) * frac;
            if (k1 - k0 < 2) {
                // 窄带（低于 ~1 个 bin）：直接用插值结果
                mag = interp;
            } else {
                // 宽带：峰值与插值取大者，保留冲击感
                mag = Math.max(mag, interp);
            }
            float db = 20f * (float) Math.log10(mag + 1e-7f);
            float v = (db + 56f) / 56f;
            if (v < 0) v = 0;
            if (v > 1) v = 1;
            float vRaw = (float) Math.pow(v, 0.78);
            if (vRaw > rawMax) rawMax = vRaw;
            v = vRaw * sensitivity;
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
        updateAgc(rawMax);
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
        final float g = autoSensOn ? autoGain : 1f;
        synchronized (lock) {
            int n = Math.min(out.length, BANDS);
            for (int i = 0; i < n; i++) {
                float v = spectrum[i] * g;
                out[i] = v > 1f ? 1f : v;
            }
        }
    }

    public void copyWave(float[] out) {
        synchronized (lock) {
            System.arraycopy(wave, 0, out, 0, Math.min(out.length, WAVE_POINTS));
        }
    }

    /** 当前音源是否真的为双声道（麦克风在部分机型上会被系统降成单声道） */
    public boolean hasStereo() {
        return stereoCapture;
    }

    /** 取左右声道各自的频谱 */
    public void copySpectrumStereo(float[] outL, float[] outR) {
        final float g = autoSensOn ? autoGain : 1f;
        synchronized (lock) {
            int n = Math.min(Math.min(outL.length, outR.length), BANDS);
            for (int i = 0; i < n; i++) {
                float a = specL[i] * g;
                float b = specR[i] * g;
                outL[i] = a > 1f ? 1f : a;
                outR[i] = b > 1f ? 1f : b;
            }
        }
    }

    /** 取左右声道各自的波形 */
    public void copyWaveStereo(float[] outL, float[] outR) {
        synchronized (lock) {
            System.arraycopy(waveL, 0, outL, 0, Math.min(outL.length, WAVE_POINTS));
            System.arraycopy(waveR, 0, outR, 0, Math.min(outR.length, WAVE_POINTS));
        }
    }

    /** 单声道来源：左右共用同一份数据，避免画面一侧全空 */
    private void mirrorMonoToStereo() {
        synchronized (lock) {
            System.arraycopy(spectrum, 0, specL, 0, BANDS);
            System.arraycopy(spectrum, 0, specR, 0, BANDS);
            System.arraycopy(wave, 0, waveL, 0, WAVE_POINTS);
            System.arraycopy(wave, 0, waveR, 0, WAVE_POINTS);
        }
        stereoCapture = false;
    }

    /** 拆开交错的双声道 PCM，各自做一次 FFT（不参与电平/节拍统计，那些仍按降混信号算） */
    private void analyzeStereo(short[] interleaved, int frames, int ch,
                               float[] re, float[] im, short[] scratch) {
        final int count = Math.min(frames, FFT_SIZE);
        if (count <= 0) return;
        for (int c = 0; c < 2; c++) {
            double sq = 0;
            for (int i = 0; i < count; i++) {
                scratch[i] = interleaved[i * ch + c];
                sq += (double) scratch[i] * scratch[i];
            }
            if (c == 0) accL += sq; else accR += sq;
            analyzeChannel(scratch, count, re, im,
                    c == 0 ? specL : specR,
                    c == 0 ? tmpSpecL : tmpSpecR,
                    c == 0 ? waveL : waveR);
        }
        stereoCapture = true;
        accN += count;
        long tnow = System.currentTimeMillis();
        if (stereoLog && accN > 0 && tnow - lastStereoLogMs >= 1000L) {
            lastStereoLogMs = tnow;
            double lDb = 10.0 * Math.log10(accL / accN / (32768.0 * 32768.0) + 1e-12);
            double rDb = 10.0 * Math.log10(accR / accN / (32768.0 * 32768.0) + 1e-12);
            android.util.Log.i("AuroraViz", String.format(
                    "stereo ch=%d L=%.1fdB R=%.1fdB diff=%.2fdB", ch, lDb, rDb, Math.abs(lDb - rDb)));
            accL = 0; accR = 0; accN = 0;
        }
    }

    /**
     * 与 analyze() 完全相同的 FFT 与频段映射，但只产出频谱/波形，
     * 不触碰 level / frames / beat / bass（这些是全局单声道指标）。
     */
    private void analyzeChannel(short[] buf, int count, float[] re, float[] im,
                                float[] out, float[] tmpSm, float[] outWave) {
        for (int i = 0; i < FFT_SIZE; i++) {
            float v = i < count ? buf[i] / 32768f : 0f;
            re[i] = v * hann[i];
            im[i] = 0f;
        }
        FFT.transform(re, im);
        final float norm = 2f / FFT_SIZE;
        final float ratio = MAX_FREQ / MIN_FREQ;
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
            double fc = (f0 + f1) * 0.5;
            double pos = fc * FFT_SIZE / SAMPLE_RATE;
            if (pos < 1) pos = 1;
            if (pos > FFT_SIZE / 2 - 2) pos = FFT_SIZE / 2 - 2;
            int pi = (int) pos;
            float frac = (float) (pos - pi);
            float m0 = (float) Math.sqrt(re[pi] * re[pi] + im[pi] * im[pi]) * norm;
            float m1 = (float) Math.sqrt(re[pi + 1] * re[pi + 1] + im[pi + 1] * im[pi + 1]) * norm;
            float interp = m0 + (m1 - m0) * frac;
            mag = (k1 - k0 < 2) ? interp : Math.max(mag, interp);
            float db = 20f * (float) Math.log10(mag + 1e-7f);
            float v = (db + 56f) / 56f;
            if (v < 0) v = 0;
            if (v > 1) v = 1;
            v = (float) Math.pow(v, 0.78) * sensitivity;
            if (v > 1f) v = 1f;
            float prev = tmpSm[b];
            tmpSm[b] = v > prev ? prev + (v - prev) * 0.75f : prev * 0.87f;
        }
        for (int i = 0; i < WAVE_POINTS; i++) {
            int idx = (int) ((long) i * count / WAVE_POINTS);
            if (idx >= count) idx = count - 1;
            outWave[i] = buf[idx] / 32768f;
        }
        synchronized (lock) {
            System.arraycopy(tmpSm, 0, out, 0, BANDS);
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