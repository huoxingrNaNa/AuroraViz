package com.operit.audioviz;

/**
 * 音频分析内核：响度（RMS / 峰值 / 短时 LUFS）、节拍 BPM、音高（音名 + 音分）。
 * 全部为时域算法，纯 Java 无依赖，所有缓冲在构造时一次分配。
 */
public final class AudioAnalysis {

    private static final String[] NOTES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    // ------------------------------------------------------------ 响度
    private volatile float rmsDb = -120f;      // 瞬时帧均方根
    private volatile float peakDb = -120f;     // 运行峰值
    private volatile float lufs = -70f;        // 短时(3s)积分，未加权近似
    private float msSmooth = 0f;
    private float peakLin = 0f;
    private float peakFast = 0f;   // 约 2s 衰减的近期峰值，用于削波提示
    private long lastLevelAt = 0L;

    // ------------------------------------------------------------ 节拍包络
    private static final int HOP = 256;        // 包络步长（个采样）
    private static final int ENV_N = 1024;     // 包络环长
    private static final int AC_WIN = 688;     // 参与自相关的最近格数（约 4s）
    private static final int LAG_MIN = 57;     // ≈180 BPM
    private static final int LAG_MAX = 172;    // ≈60 BPM
    private final float[] envRing = new float[ENV_N];
    private final float[] envLin = new float[AC_WIN];
    private final float[] onset = new float[AC_WIN];
    private final float[] beatCorr = new float[LAG_MAX + 2];
    private int envPos = 0;
    private int envCount = 0;
    private double hopSum = 0;
    private int hopFill = 0;
    private int sinceBpm = 0;
    private volatile float bpm = 0f;
    private volatile float bpmAlt = 0f;   // 另一层拍的候选值（半速/倍速歧义），0 表示无
    private float fsEnv = 172.3f;

    // ------------------------------------------------------------ 音高检测
    private static final int DS = 4;           // 44.1k → 11.025k
    private static final int PITCH_N = 2048;
    private static final int LAG_P_MIN = 14;   // ≈790 Hz，避开高频泛音
    private static final int LAG_P_MAX = 158;  // ≈70 Hz
    private final float[] pitchRing = new float[PITCH_N];
    private final float[] pitchLin = new float[PITCH_N];
    private final float[] corr = new float[256];
    private int pitchPos = 0;
    private int pitchCount = 0;
    private double dsSum = 0;
    private int dsFill = 0;
    private long lastPitchAt = 0L;
    private float fsPitch = 11025f;
    private volatile float pitchHz = 0f;
    private volatile String noteName = "";
    private volatile int noteCents = 0;
    private volatile boolean hasPitch = false;
    private final float[] recentHz = new float[4];
    private int hzPos = 0;
    private int hzCount = 0;
    private volatile boolean clipWarn = false;
    private long clipAt = 0L;
    private static final float SILENT_MS = 3.2e-6f;   // ≈ -55 dBFS
    private long quietAt = 0L;
    private volatile long lastFeedAt = 0L;
    private long anaSumUs = 0L;
    private long anaMaxUs = 0L;
    private long anaCount = 0L;
    private volatile boolean logEnabled = false;
    private long lastLogAt = 0L;

    // ------------------------------------------------------------ 输入

    /** 喂入单声道 16bit PCM（可以是任意长度，内部自行拼接） */
    public void feed(short[] buf, int n, int sampleRate) {
        if (buf == null || n <= 0 || sampleRate <= 0) return;
        final int count = Math.min(n, buf.length);
        final long now = System.currentTimeMillis();
        final long t0 = System.nanoTime();
        feedLevel(buf, count, now);
        feedBeat(buf, count, sampleRate);
        feedPitch(buf, count, sampleRate, now);
        long durUs = (System.nanoTime() - t0) / 1000L;
        anaSumUs += durUs;
        anaCount++;
        if (durUs > anaMaxUs) anaMaxUs = durUs;
        lastFeedAt = now;
        if (logEnabled && now - lastLogAt >= 1000L) {
            lastLogAt = now;
            String anaLine = String.format(java.util.Locale.US,
                    "ana db=%.1f peak=%.1f lufs=%.1f bpm=%.0f alt=%.0f note=%s cents=%d hz=%.1f clip=%s us=%.0f/%d",
                    shortDb(), peakDb, lufs, bpm, bpmAlt, noteName, noteCents, pitchHz,
                    clipWarn ? "YES" : "no",
                    anaCount > 0 ? (double) anaSumUs / anaCount : 0.0, anaMaxUs);
            android.util.Log.i("AuroraViz", anaLine);
            DiagLog.add(anaLine);
            anaSumUs = 0L;
            anaCount = 0L;
            anaMaxUs = 0L;
        }
    }

    /** 诊断日志开关（跟随「实时诊断」，由采集方设置） */
    public void setLogEnabled(boolean on) {
        logEnabled = on;
        lastLogAt = 0L;
    }

    public void reset() {
        rmsDb = -120f;
        peakDb = -120f;
        lufs = -70f;
        peakLin = 0f;
        peakFast = 0f;
        msSmooth = 0f;
        lastLevelAt = 0L;
        envPos = 0;
        envCount = 0;
        hopSum = 0;
        hopFill = 0;
        sinceBpm = 0;
        bpm = 0f;
        bpmAlt = 0f;
        pitchPos = 0;
        pitchCount = 0;
        dsSum = 0;
        dsFill = 0;
        pitchHz = 0f;
        hzPos = 0;
        hzCount = 0;
        clipAt = 0L;
                clipWarn = false;
        quietAt = 0L;
        lastFeedAt = 0L;
        noteName = "";
        noteCents = 0;
        hasPitch = false;
        for (int i = 0; i < ENV_N; i++) envRing[i] = 0f;
        for (int i = 0; i < PITCH_N; i++) pitchRing[i] = 0f;
    }

    // ------------------------------------------------------------ 响度

    private void feedLevel(short[] buf, int n, long now) {
        double sum = 0;
        float mx = 0f;
        int run = 0;
        for (int i = 0; i < n; i++) {
            float s = buf[i] / 32768f;
            sum += s * s;
            float a = s < 0 ? -s : s;
            if (a > mx) mx = a;
            // 严格满刻度：只有采样值恰为 32767 / -32768 才算（a >= 32767/32768 ≈ 0.999969）。
            // 连续 32 个（44.1kHz 下约 0.7ms）这样的采样才判为平顶削波，
            // 避免把"母带压得很响但没削"的曲目全标红。
            if (a >= 0.99996f) {
                if (++run >= 32) clipAt = now;
            } else {
                run = 0;
            }
        }
        float ms = (float) (sum / Math.max(1, n));
        rmsDb = ms > 1e-10f ? 10f * (float) Math.log10(ms) : -120f;
        if (mx > peakLin) {
            peakLin = mx;
            peakDb = 20f * (float) Math.log10(mx + 1e-6f);
        }
        // 每帧衰减 0.5%，2s 左右回落到 80%
        if (mx > peakFast) peakFast = mx;
        else peakFast *= 0.995f;
        if (lastLevelAt == 0L) {
            msSmooth = ms;
            lastLevelAt = now;
        } else {
            long dt = now - lastLevelAt;
            if (dt > 0) {
                float alpha = (float) (1.0 - Math.exp(-dt / 3000.0));
                msSmooth += (ms - msSmooth) * alpha;
                lastLevelAt = now;
            }
        }
        lufs = (float) (-0.691 + 10.0 * Math.log10(msSmooth + 1e-10));
        clipWarn = now - clipAt < 1000L;
        // 持续静音 1.5s 后清空瞬时读数，否则停歌后 BPM 和音高会一直挂在界面上
        if (ms < SILENT_MS) {
            if (quietAt == 0L) quietAt = now;
        } else {
            quietAt = 0L;
        }
        if (quietAt != 0L && now - quietAt > 1500L) clearTransient();
    }

    /** 清空瞬时读数（BPM / 音高），保留电平历史 */
    private void clearTransient() {
        bpm = 0f;
        bpmAlt = 0f;
        pitchHz = 0f;
        noteName = "";
        noteCents = 0;
        hasPitch = false;
        hzCount = 0;
    }

    /**
     * 由看门狗线程周期性调用。系统音频采集在无播放时会阻塞在 record.read()，
     * feed() 根本不会被调用，静音清空无法生效，所以需要外部按时间戳判断。
     */
    public void idle() {
        final long now = System.currentTimeMillis();
        if (lastFeedAt == 0L) return;
        if (now - lastFeedAt < 1500L) return;
        clearTransient();
        rmsDb = -120f;
        lufs = -70f;
        clipWarn = false;
        if (logEnabled && now - lastLogAt >= 1000L) {
            lastLogAt = now;
            String idleLine = String.format(java.util.Locale.US,
                    "ana idle db=%.1f bpm=%.0f alt=%.0f note=%s clip=no",
                    rmsDb, bpm, bpmAlt, noteName);
            android.util.Log.i("AuroraViz", idleLine);
            DiagLog.add(idleLine);
        }
    }

    // ------------------------------------------------------------ 节拍

    private void feedBeat(short[] buf, int n, int sampleRate) {
        fsEnv = sampleRate / (float) HOP;
        for (int i = 0; i < n; i++) {
            float s = buf[i] / 32768f;
            hopSum += s * s;
            if (++hopFill >= HOP) {
                pushEnv((float) (hopSum / HOP));
                hopSum = 0;
                hopFill = 0;
            }
        }
    }

    private void pushEnv(float e) {
        envRing[envPos] = e;
        envPos = (envPos + 1) % ENV_N;
        if (envCount < ENV_N) envCount++;
        if (++sinceBpm >= 86 && envCount >= AC_WIN) {
            sinceBpm = 0;
            computeBpm();
        }
    }

    private void computeBpm() {
        final int w = Math.min(envCount, AC_WIN);
        if (w < LAG_MAX + 8) return;
        for (int i = 0; i < w; i++) {
            int idx = (envPos - w + i + ENV_N * 2) % ENV_N;
            envLin[i] = envRing[idx];
        }
        // 一阶差分取正部（起音），再减去均值抑制噪声底
        float sum = 0f;
        onset[0] = 0f;
        for (int i = 1; i < w; i++) {
            float d = envLin[i] - envLin[i - 1];
            if (d > 0f) {
                onset[i] = d;
                sum += d;
            } else {
                onset[i] = 0f;
            }
        }
        float thr = sum / w * 0.8f;
        for (int i = 0; i < w; i++) {
            float v = onset[i] - thr;
            onset[i] = v > 0f ? v : 0f;
        }

        java.util.Arrays.fill(beatCorr, 0f);
        float maxR = 0f;
        int maxLag = 0;
        for (int lag = LAG_MIN; lag <= LAG_MAX && lag < w - 8; lag++) {
            double num = 0, e1 = 0, e2 = 0;
            final int m = w - lag;
            for (int i = 0; i < m; i++) {
                float a = onset[i];
                float b = onset[i + lag];
                num += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            // 两侧窗口各自归一化，避免长 lag 因重叠点数变少而被系统性放大
            float r = (e1 < 1e-12 || e2 < 1e-12) ? 0f
                    : (float) (num / Math.sqrt(e1 * e2));
            beatCorr[lag] = r;
            if (r > maxR) {
                maxR = r;
                maxLag = lag;
            }
        }
        if (maxLag == 0 || maxR < 0.12f) return;

        // 自相关在 2 倍、3 倍拍周期处同样成立，取「最快」的强峰，
        // 否则 160 BPM 会被读成 80 BPM；搜索下限 LAG_MIN 同时挡住半拍误判。
        int pick = maxLag;
        for (int lag = LAG_MIN + 1; lag < LAG_MAX; lag++) {
            if (beatCorr[lag] >= beatCorr[lag - 1] && beatCorr[lag] >= beatCorr[lag + 1]
                    && beatCorr[lag] > maxR * 0.65f) {
                pick = lag;
                break;
            }
        }
        float raw = 60f * fsEnv / pick;
        if (raw < 55f || raw > 190f) return;
        bpm = bpm <= 0f ? raw : bpm * 0.7f + raw * 0.3f;
        // 半速 / 倍速歧义无法从信号层面消除：同一段音乐 84 和 168 在数学上都成立，
        // 取决于把哪一层当拍。这里检测「一拍长度加倍」处是否同样成立，成立就记备选值。
        int lag2 = pick * 2;
        bpmAlt = (lag2 <= LAG_MAX && lag2 < w - 8 && beatCorr[lag2] >= maxR * 0.75f)
                ? bpm * 0.5f : 0f;
    }

    // ------------------------------------------------------------ 音高

    private void feedPitch(short[] buf, int n, int sampleRate, long now) {
        fsPitch = sampleRate / (float) DS;
        for (int i = 0; i < n; i++) {
            dsSum += buf[i];
            if (++dsFill >= DS) {
                pitchRing[pitchPos] = (float) (dsSum / (DS * 32768.0));
                pitchPos = (pitchPos + 1) % PITCH_N;
                if (pitchCount < PITCH_N) pitchCount++;
                dsSum = 0;
                dsFill = 0;
            }
        }
        if (now - lastPitchAt < 100L) return;
        lastPitchAt = now;
        computePitch();
    }

    private void computePitch() {
        final int w = Math.min(pitchCount, PITCH_N);
        if (w < 512) return;
        for (int i = 0; i < w; i++) {
            int idx = (pitchPos - w + i + PITCH_N * 2) % PITCH_N;
            pitchLin[i] = pitchRing[idx];
        }
        double e0 = 0;
        for (int i = 0; i < w; i++) e0 += pitchLin[i] * pitchLin[i];
        float rms = (float) Math.sqrt(e0 / w);
        if (rms < 0.004f) {
            hasPitch = false;
            return;
        }
        final int maxLag = Math.min(LAG_P_MAX, w / 2);
        float best = 0f;
        int bestLag = 0;
        for (int lag = LAG_P_MIN; lag <= maxLag; lag++) {
            double num = 0, e1 = 0, e2 = 0;
            final int m = w - lag;
            for (int i = 0; i < m; i++) {
                float a = pitchLin[i];
                float b = pitchLin[i + lag];
                num += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            float r = (e1 < 1e-12 || e2 < 1e-12) ? 0f
                    : (float) (num / Math.sqrt(e1 * e2));
            corr[lag] = r;
            if (r > best) {
                best = r;
                bestLag = lag;
            }
        }
        if (bestLag == 0 || best < 0.72f) {
            hasPitch = false;
            return;
        }
        // 八度纠正：取第一个达到峰值 80% 的局部峰，避免落在两倍周期上
        int pick = bestLag;
        for (int lag = LAG_P_MIN + 1; lag < bestLag; lag++) {
            if (corr[lag] > corr[lag - 1] && corr[lag] >= corr[lag + 1]
                    && corr[lag] > best * 0.8f) {
                pick = lag;
                break;
            }
        }
        // 峰落在搜索区间边缘时不可信：真实周期已超出 70–1000 Hz 范围，
        // 这种情况在混音里很常见（高频泛音），直接判为无音高
        if (pick <= LAG_P_MIN + 1 || pick >= maxLag - 1) {
            hasPitch = false;
            return;
        }
        // 抛物线插值细化周期
        float period = pick;
        if (pick > LAG_P_MIN && pick < maxLag) {
            float y0 = corr[pick - 1], y1 = corr[pick], y2 = corr[pick + 1];
            float den = y0 - 2f * y1 + y2;
            if (Math.abs(den) > 1e-9f) {
                float delta = 0.5f * (y0 - y2) / den;
                if (delta > -1f && delta < 1f) period = pick + delta;
            }
        }
        float f = fsPitch / Math.max(1f, period);
        if (f < 60f || f > 1100f) {
            hasPitch = false;
            return;
        }
        // 稳定性：最近 4 次（约 0.4s）波动须在 2% 以内，混音里乱跳的值不显示
        recentHz[hzPos] = f;
        hzPos = (hzPos + 1) % recentHz.length;
        if (hzCount < recentHz.length) hzCount++;
        if (hzCount < recentHz.length) {
            hasPitch = false;
            return;
        }
        float mn = f, mx = f;
        for (int i = 0; i < recentHz.length; i++) {
            float v = recentHz[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        if (mx - mn > mx * 0.02f) {
            hasPitch = false;
            return;
        }
        pitchHz = pitchHz <= 0f ? f : pitchHz * 0.7f + f * 0.3f;
        updateNote();
        hasPitch = true;
    }

    private void updateNote() {
        float f = pitchHz;
        if (f < 20f) {
            noteName = "";
            noteCents = 0;
            return;
        }
        double midi = 69.0 + 12.0 * Math.log(f / 440.0) / Math.log(2.0);
        int m = (int) Math.round(midi);
        int idx = ((m % 12) + 12) % 12;
        int oct = m / 12 - 1;   // m 恒 ≥ 35（f ≥ 60 Hz），无需处理负数
        noteName = NOTES[idx] + oct;
        noteCents = Math.round((float) ((midi - m) * 100.0));
    }

    // ------------------------------------------------------------ 读取

    /** 瞬时帧均方根（dBFS，抖动较大） */
    public float rmsDb() {
        return rmsDb;
    }

    /** 短时(3s)积分电平（dBFS，显示用） */
    public float shortDb() {
        return (float) (10.0 * Math.log10(msSmooth + 1e-10));
    }

    /** 运行峰值（dBFS，自开始采集起） */
    public float peakDb() {
        return peakDb;
    }

    /** 近 1.5s 内是否出现过真正的平顶削波 */
    public boolean clipWarn() {
        return clipWarn;
    }

    /** 近 2 秒内的峰值（dBFS），用于削波提示，会自动回落 */
    public float recentPeakDb() {
        return (float) (20.0 * Math.log10(peakFast + 1e-6));
    }

    /** 短时积分响度（未加权近似，单位为 LUFS） */
    public float lufsApprox() {
        return lufs;
    }

    /** 节拍估计，0 表示尚未测出 */
    public float bpm() {
        return bpm;
    }

    /** 另一层拍的候选值，0 表示不存在歧义 */
    public float bpmAlt() {
        return bpmAlt;
    }

    /** 平滑后的基频 */
    public float pitchHz() {
        return pitchHz;
    }

    public String noteName() {
        return noteName;
    }

    public int noteCents() {
        return noteCents;
    }

    public boolean hasPitch() {
        return hasPitch;
    }
}