package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音频可视化绘制视图。
 * 纵向：柱状（带 dB 纵轴与峰值保持）、示波（带零轴刻度）。
 * 环形：环形频谱、径向波形、粒子云、极坐标圆盘。
 * 支持加色辉光、节拍驱动、待机呼吸与彩虹配色。
 */
public class VisualizerView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final float[] hsv = new float[3];

    // ---------------- 双声道 ----------------
    /** 0=关闭 1=上下分屏 2=左右分屏 3=叠加 */
    private int stereoMode = 0;
    /** 绘制时的声道选择：0=按角度自动，1=左，2=右（叠加模式靠它连续画两遍） */
    private int drawCh = 0;
    /** 最近一帧最高柱子的百分比（0-100），给主界面当“调灵敏度”的参照物 */
    private volatile int lastBarPct = 0;
    /** 是否在柱状底部标注频率（低频在左、高频在右） */
    private boolean freqAxis = false;
    /** 标注的频率点（Hz）：60 / 250 / 1k / 4k / 16k */
    private static final float[] AXIS_HZ = {60f, 250f, 1000f, 4000f, 16000f};
    private final float[] stTargetL = new float[AudioEngine.BANDS];
    private final float[] stTargetR = new float[AudioEngine.BANDS];
    private final float[] stDrawL = new float[AudioEngine.BANDS];
    private final float[] stDrawR = new float[AudioEngine.BANDS];
    /** 差异增强后的显示值（只影响画面，不进入平滑状态，避免自我反馈放大） */
    private final float[] stViewL = new float[AudioEngine.BANDS];
    private final float[] stViewR = new float[AudioEngine.BANDS];
    /**
     * 声道差异视觉增益。手机/流媒体内容左右电平差常小于 1dB，
     * 直接画出来肉眼分不出，这里以中点为基准按倍数放大差异。
     * 1 = 完全真实；数值越大差异越夸张。
     */
    private static final float CH_DIFF_BOOST = 2.5f;
    private final float[] stWaveL = new float[AudioEngine.WAVE_POINTS];
    private final float[] stWaveR = new float[AudioEngine.WAVE_POINTS];
    private final float[] stSmoothL = new float[AudioEngine.WAVE_POINTS];
    private final float[] stSmoothR = new float[AudioEngine.WAVE_POINTS];
    private final float[] specA = new float[AudioEngine.BANDS];
    /** 引擎最新一帧（目标值） */
    private final float[] specTarget = new float[AudioEngine.BANDS];
    /** 屏幕上实际显示的值（按时间插值逼近 target，与帧率无关） */
    private final float[] specDraw = new float[AudioEngine.BANDS];
    private long lastDrawAtMs = 0L;
    /** 渲染帧率上限：0 = 跟随屏幕刷新 */
    private int renderHz = 0;
    private long lastInvalidateAt = 0L;
    /** 平滑时间常数（ms）：越大越软 */
    private float smoothTau = 26f;
    private final float[] waveA = new float[AudioEngine.WAVE_POINTS];
    private final float[] smoothWave = new float[AudioEngine.WAVE_POINTS];
    /** 峰值弹道：hold 500ms + fade 750ms（与帧率无关，时间为唯一基准） */
    private final float[] peakVal = new float[AudioEngine.BANDS];
    private final float[] peakRel = new float[AudioEngine.BANDS];
    private final long[] peakHoldUntil = new long[AudioEngine.BANDS];
    private static final long PEAK_HOLD_MS = 500L;
    private static final long PEAK_FADE_MS = 750L;
    /** 双声道各自的峰值轨迹 */
    private final float[] peakValL = new float[AudioEngine.BANDS];
    private final float[] peakValR = new float[AudioEngine.BANDS];
    private final float[] peakRelL = new float[AudioEngine.BANDS];
    private final float[] peakRelR = new float[AudioEngine.BANDS];
    private final long[] peakHoldL = new long[AudioEngine.BANDS];
    private final long[] peakHoldR = new long[AudioEngine.BANDS];
    /** 相邻两次绘制的间隔（ms），峰值按时间匀速下落，与帧率解耦 */
    private float peakDtMs = 16f;
    private long lastPeakAtMs = 0L;
    /** 累计绘制帧数，给"全屏实测帧率"用（只读计数，不影响绘制） */
    private long drawCount = 0L;

    public long drawCount() {
        return drawCount;
    }

    private Style style = Style.COLUMN;
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;
    private boolean rainbow = false;
    private boolean drawBackground = false;
    /** 自驱重绘开关：录制导出时用独立 View 手动逐帧绘制，需关掉自驱 */
    private boolean selfDrive = true;
    /** 上一帧是否有真实音频在跑（false=待机呼吸动画，会压到 30fps） */
    private boolean lastActive = true;
    private boolean showModeLabel = false;
    /** 右上角模式标签的基线高度（dp），由宿主按自己顶部占用的空间设置 */
    private float labelTopDp = 38f;
    /** 模式标签右侧内缩（dp），给宿主的 ✕ 按钮让位 */
    private float labelRightDp = 14f;
    /** 频率轴启用时标签的去向：0 = 右下（默认），1 = 右上，2 = 顶部居中 */
    private int labelAnchor = 0;
    /** 0 发光粒子 / 1 细线 / 2 关闭 —— 与界面 chip 顺序严格一致 */
    private int peakMode = 0;
    /** 待机时是否播呼吸动画 */
    private boolean idleAnim = true;
    private final long bornAt = System.nanoTime();

    public VisualizerView(Context context) {
        super(context);
    }

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 双声道模式：0=关闭 1=上下 2=左右 3=叠加 */
    public void setStereo(int mode) {
        if (mode < 0 || mode > 3) mode = 0;
        if (stereoMode == mode) return;
        stereoMode = mode;
        drawCh = 0;
        invalidate();
    }

    public int stereoMode() {
        return stereoMode;
    }

    public void setStyle(Style s) {
        if (s == null) return;
        if (style != s) {
            for (int i = 0; i < peakVal.length; i++) {
                peakVal[i] = 0f;
                peakRel[i] = 0f;
                peakHoldUntil[i] = 0L;
                peakValL[i] = 0f;
                peakValR[i] = 0f;
                peakRelL[i] = 0f;
                peakRelR[i] = 0f;
                peakHoldL[i] = 0L;
                peakHoldR[i] = 0L;
            }
        }
        style = s;
        invalidate();
    }

    public int lastBarPct() {
        return lastBarPct;
    }

    public void setFreqAxis(boolean on) {
        freqAxis = on;
        invalidate();
    }

    /** 频段按对数分布（40Hz→16kHz），把任意频率换算成柱子索引，标注位置才可信 */
    private static int bandOfHz(float hz) {
        float f = hz < 40f ? 40f : hz;
        int b = Math.round((float) (Math.log(f / 40f) / Math.log(400f) * AudioEngine.BANDS - 0.5f));
        if (b < 0) b = 0;
        if (b > AudioEngine.BANDS - 1) b = AudioEngine.BANDS - 1;
        return b;
    }

    /** 底部频率轴带：柱子下方单独留出的区域，低频在左、高频在右 */
    private void drawFreqAxis(Canvas canvas, float left, float right, float barBottom,
                              float boxBottom, float bw, float gap) {
        if (!freqAxis) return;
        textPaint.setTextSize(dp(7f));
        textPaint.setLetterSpacing(0f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 柱脚基线
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(0.6f));
        strokePaint.setColor(withAlpha(0xFFFFFFFF, 0.14f));
        canvas.drawLine(left, barBottom, right, barBottom, strokePaint);

        // 低频|中频（250Hz）与 中频|高频（2kHz）分隔：柱身内的一小段
        strokePaint.setColor(withAlpha(0xFFFFFFFF, 0.10f));
        float[] edges = {250f, 2000f};
        for (float hz : edges) {
            float ex = left + bandOfHz(hz) * (bw + gap) - gap * 0.5f;
            canvas.drawLine(ex, barBottom - dp(9f), ex, barBottom, strokePaint);
        }
        strokePaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(withAlpha(0xFFFFFFFF, 0.42f));
        final float labelY = boxBottom - dp(3f);
        for (float hz : AXIS_HZ) {
            int b = bandOfHz(hz);
            float x = left + b * (bw + gap) + bw / 2f;
            if (x < left + dp(4f)) x = left + dp(4f);
            if (x > right - dp(6f)) x = right - dp(6f);
            String t = hz >= 1000f
                    ? Math.round(hz / 1000f) + "k"
                    : String.valueOf(Math.round(hz));
            canvas.drawText(t, x, labelY, textPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0f);
    }

    public Style style() {
        return style;
    }

    public void setPalette(Palette p) {
        if (p == null) return;
        palette = p;
        int[] sc = Scheme.colors(getContext(), p);
        col0 = sc[0];
        col1 = sc[1];
        col2 = sc[2];
        glowC = sc[3];
        rainbow = Scheme.isRainbow(p);
        invalidate();
    }

    public void setDrawBackground(boolean enabled) {
        drawBackground = enabled;
        invalidate();
    }

    /** 录制导出时关闭自驱重绘（由录制线程手动逐帧 draw） */
    /** 可视化渲染帧率上限：0=跟随屏幕刷新，也可设 30/60/90/120 */
    public void setRenderHz(int hz) {
        renderHz = Math.max(0, hz);
        // Android 14 起官方给了 View 级的帧率申请接口：比自己改窗口属性更容易被系统采纳，
        // 0 表示"不表态"，交给系统按内容决定。横屏 / 全屏这种场景就是靠它把模式顶上去的。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                setRequestedFrameRate(renderHz > 0 ? renderHz : 0f);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 频谱跟随的平滑程度：时间常数 ms（8=紧跟，45=很软） */
    public void setSmoothTau(float tauMs) {
        smoothTau = Math.max(6f, Math.min(60f, tauMs));
    }

    public void setSelfDrive(boolean enabled) {
        selfDrive = enabled;
    }

    public void setLabelTopDp(float v) {
        labelTopDp = v;
    }

    public void setLabelRightDp(float v) {
        labelRightDp = v;
    }

    public void setModeLabelAnchor(int a) {
        labelAnchor = a;
    }

    public void setShowModeLabel(boolean enabled) {
        showModeLabel = enabled;
        invalidate();
    }

    public void setPeakMode(int mode) {
        peakMode = mode;
        invalidate();
    }

    public void setIdleAnim(boolean enabled) {
        idleAnim = enabled;
        invalidate();
    }

    float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawCount++;
        drawContent(canvas);
    }

    private void drawContent(Canvas canvas) {
        drawCh = 0;
        long peakNow = System.currentTimeMillis();
        peakDtMs = lastPeakAtMs == 0L ? 16f : (peakNow - lastPeakAtMs);
        lastPeakAtMs = peakNow;
        if (peakDtMs < 1f) peakDtMs = 1f;
        if (peakDtMs > 80f) peakDtMs = 80f;
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float tSec = (System.nanoTime() - bornAt) / 1_000_000_000f;
        final AudioEngine engine = AudioEngine.get();
        engine.copySpectrum(specTarget);
        engine.copyWave(waveA);
        if (stereoMode > 0) {
            engine.copySpectrumStereo(stTargetL, stTargetR);
            engine.copyWaveStereo(stWaveL, stWaveR);
        }
        // 按真实时间插值：43Hz 的数据在 60fps 下也能每帧都变化，不会出现“抽帧”
        final long nowMs = android.os.SystemClock.uptimeMillis();
        float dtMs = lastDrawAtMs == 0L ? 16f : (nowMs - lastDrawAtMs);
        lastDrawAtMs = nowMs;
        if (dtMs < 1f) dtMs = 1f;
        if (dtMs > 80f) dtMs = 80f;
        final float kSpec = 1f - (float) Math.exp(-dtMs / Math.max(6f, smoothTau));
        final float kWave = 1f - (float) Math.exp(-dtMs / 16f);
        for (int i = 0; i < specDraw.length; i++) {
            specDraw[i] += (specTarget[i] - specDraw[i]) * kSpec;
            specA[i] = specDraw[i];
        }
        float maxForPct = 0f;
        for (int i = 0; i < specA.length; i++) {
            if (specA[i] > maxForPct) maxForPct = specA[i];
        }
        lastBarPct = (int) (Math.pow(maxForPct, 0.80f) * 100f);
        if (stereoMode > 0) {
            for (int i = 0; i < stDrawL.length; i++) {
                stDrawL[i] += (stTargetL[i] - stDrawL[i]) * kSpec;
                stDrawR[i] += (stTargetR[i] - stDrawR[i]) * kSpec;
            }
            for (int i = 0; i < stSmoothL.length; i++) {
                stSmoothL[i] += (stWaveL[i] - stSmoothL[i]) * kWave;
                stSmoothR[i] += (stWaveR[i] - stSmoothR[i]) * kWave;
            }
        }

        boolean active = engine.isRunning()
                && System.currentTimeMillis() - engine.lastFrameAt() < 700;
        lastActive = active;
        float bass = active ? engine.bassLevel() : 0f;
        float beat = active ? engine.beat() : 0f;

        for (int i = 0; i < smoothWave.length; i++) {
            smoothWave[i] += (waveA[i] - smoothWave[i]) * kWave;
        }

        if (!active && !idleAnim) {
            java.util.Arrays.fill(specA, 0f);
            java.util.Arrays.fill(specDraw, 0f);
            java.util.Arrays.fill(smoothWave, 0f);
            bass = 0f;
            beat = 0f;
        } else if (!active) {
            // 待机呼吸动画
            bass = 0.22f + 0.12f * (float) Math.sin(tSec * 1.35f);
            beat = 0.45f + 0.45f * (float) Math.sin(tSec * 2.1f);
            for (int i = 0; i < specA.length; i++) {
                float p = (float) i / specA.length;
                float val = 0.32f
                        + 0.20f * (float) Math.sin(tSec * 0.95f + p * 11.3f)
                        + 0.12f * (float) Math.sin(tSec * 1.65f + p * 23.7f)
                        + 0.07f * (float) Math.sin(tSec * 2.80f + p * 37.1f)
                        + 0.04f * (float) Math.sin(tSec * 4.10f + p * 6.2f);
                specA[i] = val < 0.06f ? 0.06f : (val > 0.95f ? 0.95f : val);
            }
            for (int i = 0; i < smoothWave.length; i++) {
                float p = (float) i / smoothWave.length;
                smoothWave[i] = 0.34f * (float) Math.sin(tSec * 1.4f + p * 6.283f)
                        + 0.12f * (float) Math.sin(tSec * 2.7f + p * 12.56f);
            }
            // 数值钳制：负数/NaN 会让 pow() 产出 NaN，进而使 Shader 创建失败
            for (int i = 0; i < specA.length; i++) {
                if (!(specA[i] > 0f)) specA[i] = 0f;
                else if (specA[i] > 1f) specA[i] = 1f;
            }
            for (int i = 0; i < smoothWave.length; i++) {
                if (!(smoothWave[i] > -1f)) smoothWave[i] = -1f;
                else if (smoothWave[i] > 1f) smoothWave[i] = 1f;
            }
        }

        if (stereoMode > 0) {
            // 待机动画、或音源其实是单声道时，左右共用同一份数据
            boolean mirror = !active || !engine.hasStereo();
            if (mirror) {
                System.arraycopy(specA, 0, stDrawL, 0, stDrawL.length);
                System.arraycopy(specA, 0, stDrawR, 0, stDrawR.length);
                System.arraycopy(smoothWave, 0, stSmoothL, 0, stSmoothL.length);
                System.arraycopy(smoothWave, 0, stSmoothR, 0, stSmoothR.length);
            }
        }
        // 生成“显示用”频谱：单声道时左右同源（等于原值），双声道时以中点为基准放大差异。
        // 必须无条件执行——环形/径向/粒子/圆盘的取数函数在单声道下也要读这两个数组。
        if (stereoMode > 0) {
            for (int i = 0; i < stViewL.length; i++) {
                float a = stDrawL[i];
                float b = stDrawR[i];
                float mid = (a + b) * 0.5f;
                float vl = mid + (a - mid) * CH_DIFF_BOOST;
                float vr = mid + (b - mid) * CH_DIFF_BOOST;
                stViewL[i] = vl < 0f ? 0f : (vl > 1f ? 1f : vl);
                stViewR[i] = vr < 0f ? 0f : (vr > 1f ? 1f : vr);
            }
        } else {
            System.arraycopy(specA, 0, stViewL, 0, stViewL.length);
            System.arraycopy(specA, 0, stViewR, 0, stViewR.length);
        }
        if (drawBackground) drawGlass(canvas, w, h);

        float pad = drawBackground ? dp(14) : dp(6);
        float l = pad, t = pad, r = w - pad, b = h - pad;

        switch (style) {
            case SCOPE:
                if (stereoMode > 0) {
                    drawColumnWaveStereo(canvas, l, t, r, b, tSec, beat);
                } else {
                    drawColumnWave(canvas, l, t, r, b, tSec, beat);
                }
                break;
            case RING:
                drawRingSpectrum(canvas, l, t, r, b, tSec, bass, beat);
                break;
            case RADIAL:
                drawRingWave(canvas, l, t, r, b, tSec, beat);
                break;
            case PARTICLES:
                drawParticles(canvas, l, t, r, b, tSec, bass, beat);
                break;
            case DISC:
                drawDisc(canvas, l, t, r, b, tSec, bass, beat);
                break;
            case COLUMN:
            default:
                if (stereoMode > 0) {
                    drawColumnSpectrumStereo(canvas, l, t, r, b, beat, active);
                } else {
                    drawColumnSpectrum(canvas, l, t, r, b, beat, active);
                }
                break;
        }

        if (showModeLabel) drawModeLabel(canvas, w, h);
        if (selfDrive) {
            // 自驱重绘：renderHz>0 时限速，避免不必要的耗电
            // 待机（没有音频在跑、只是呼吸动画）时压到 30fps：
            // 慢速正弦在 30fps 下看不出差别，但少画 3/4 的帧，发热和耗电明显下降
            int effHz = renderHz;
            if (!lastActive && (effHz <= 0 || effHz > 30)) effHz = 30;
            if (effHz <= 0) {
                postInvalidateOnAnimation();
            } else {
                long now2 = android.os.SystemClock.uptimeMillis();
                long frameMs = Math.max(4, 1000 / effHz);
                long since = now2 - lastInvalidateAt;
                if (since >= frameMs - 2) {
                    lastInvalidateAt = now2;
                    postInvalidateOnAnimation();
                } else {
                    postInvalidateDelayed(frameMs - since);
                }
            }
        }
    
    }

    // ------------------------------------------------------------ 背景 / 标签 / 纵轴

    private void drawGlass(Canvas canvas, int w, int h) {
        float radius = dp(22);
        rectF.set(0, 0, w, h);
        fillPaint.setAlpha(255); fillPaint.setShader(new LinearGradient(0, 0, w, h,
                0xF0070A12, 0xE6040509, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, radius, radius, fillPaint);
        fillPaint.setShader(null);

        rectF.set(dp(0.8f), dp(0.8f), w - dp(0.8f), h - dp(0.8f));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.1f));
        strokePaint.setAlpha(255); strokePaint.setShader(new LinearGradient(0, 0, w, h,
                withAlpha(col1, 0.45f), withAlpha(col2, 0.28f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, radius, radius, strokePaint);
        strokePaint.setShader(null);
        strokePaint.setStyle(Paint.Style.FILL);
    }

    private void drawModeLabel(Canvas canvas, int w, int h) {
        String label = style.label + " · " + palette.label;
        boolean shifted = freqAxis && style == Style.COLUMN;
        // 锚点 2：顶部居中，无条件生效（悬浮窗空间小，右下总被柱子占着）
        if (labelAnchor == 2) {
            textPaint.setTextSize(dp(8f));
            textPaint.setLetterSpacing(0.16f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float tw = textPaint.measureText(label);
            float cx = w * 0.5f;
            float baseY = Math.max(dp(labelTopDp), dp(9f));
            rectF.set(cx - tw * 0.5f - dp(7), baseY - dp(9.5f),
                    cx + tw * 0.5f + dp(7), baseY + dp(3.5f));
            fillPaint.setShader(null);
            fillPaint.setColor(0x59000000);
            canvas.drawRoundRect(rectF, dp(4), dp(4), fillPaint);
            textPaint.setColor(withAlpha(mixWhite(col2, 0.3f), 0.9f));
            canvas.drawText(label, cx, baseY, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        // 锚点 1：开启频率轴时去右上（让开底部轴带），否则维持右下
        boolean topRight = labelAnchor == 1 && shifted;
        textPaint.setTextSize(dp(8.5f));
        textPaint.setLetterSpacing(0.14f);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        float tw = textPaint.measureText(label);
        float rx = w - dp(labelRightDp);
        float baseY = topRight ? Math.max(dp(labelTopDp), dp(17f)) : h - dp(10);
        rectF.set(rx - tw - dp(5), baseY - dp(9.5f), rx + dp(5), baseY + dp(3.5f));
        fillPaint.setShader(null);
        fillPaint.setColor(0x66000000);
        canvas.drawRoundRect(rectF, dp(4), dp(4), fillPaint);
        textPaint.setColor(withAlpha(mixWhite(col2, 0.25f), 0.92f));
        canvas.drawText(label, rx, baseY, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }
    /** 纵向坐标轴：4 条 dB 网格线 + 左侧刻度 */
    private void drawAxisGrid(Canvas canvas, float left, float top, float right, float bottom,
                              boolean centerAxis) {
        float hgt = bottom - top;
        int rows = hgt > dp(200) ? 4 : 2;
        if (!centerAxis && hgt > dp(120)) rows = 3;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(0.7f));
        textPaint.setTextSize(hgt > dp(120) ? dp(7.5f) : dp(7f));
        textPaint.setLetterSpacing(0f);
        textPaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i <= rows; i++) {
            float y = top + (bottom - top) * i / rows;
            boolean zero = centerAxis
                    ? Math.abs(y - (top + bottom) / 2f) < dp(0.8f)
                    : (i == rows);
            strokePaint.setColor(zero
                    ? withAlpha(0xFFFFFFFF, 0.20f)
                    : withAlpha(0xFFFFFFFF, 0.06f));
            canvas.drawLine(left, y, right, y, strokePaint);

            String label;
            if (centerAxis) {
                label = i == rows / 2 ? "0"
                        : (i < rows / 2 ? "+" + (rows / 2 - i) * 50
                        : "-" + (i - rows / 2) * 50);
            } else {
                label = i == 0 ? "0" : "-" + (int) (i * (64f / rows));
            }
            textPaint.setColor(withAlpha(0xFFFFFFFF, 0.28f));
            canvas.drawText(label, left - dp(18), y + dp(2.6f), textPaint);
        }
        // 左侧竖轴
        strokePaint.setColor(withAlpha(0xFFFFFFFF, 0.10f));
        canvas.drawLine(left - dp(1), top, left - dp(1), bottom, strokePaint);
        strokePaint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 1. 纵向柱状（+纵轴）

    private void drawColumnSpectrum(Canvas canvas, float l, float t, float r, float b,
                                    float beat, boolean active) {
        float left = l + dp(20);
        float right = r;
        float top = t + dp(2);
        final float boxBottom = b - dp(1);
        // 频率标注开启时，在柱子下方留出独立轴带，标签不再压在柱身上
        final float axisH = freqAxis ? dp(12f) : 0f;
        float bottom = boxBottom - axisH;
        if (bottom - top <= dp(3)) return;
        drawAxisGrid(canvas, left, top, right, bottom, false);

        final int n = AudioEngine.BANDS;
        final float gapRatio = 0.42f;
        float bw = (right - left) / (n + (n - 1) * gapRatio);
        float gap = bw * gapRatio;
        if (bw <= 0.3f) return;
        float maxH = bottom - top;

        final long nowMs = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            float v = specA[i];
            if (!(v > 0f)) v = 0f;
            if (v > 1f) v = 1f;
            // 统一高度映射：柱与峰值必须用同一条曲线，否则峰值点会掉进柱身
            final float beatK = 1f + beat * 0.06f;
            final float baseH = dp(1.6f);
            float bh = baseH + (float) Math.pow(v, 0.80f) * maxH * beatK;
            if (!(bh > dp(1f))) bh = dp(1f);
            if (bh > maxH) bh = maxH;
            float x = left + i * (bw + gap);
            int col = colorAt((float) i / (n - 1));

            shaderPaint.setAlpha(255); shaderPaint.setShader(new LinearGradient(0, bottom - bh, 0, bottom,
                    col, withAlpha(col, 0.12f), Shader.TileMode.CLAMP));
            rectF.set(x, bottom - bh, x + bw, bottom);
            float rad = Math.min(bw * 0.45f, dp(2.5f));
            canvas.drawRoundRect(rectF, rad, rad, shaderPaint);

            // ---- 峰值弹道：仅在真实监听时工作（待机动画不产生峰值）----
            // 参考 audioMotion-analyzer 默认值：hold 500ms + fade 750ms
            if (!active) {
                peakVal[i] = 0f;
                peakRel[i] = 0f;
                peakHoldUntil[i] = 0L;
                continue;
            }
            updatePeak(peakVal, peakRel, peakHoldUntil, i, v);

            drawPeakMark(canvas, x + bw / 2f, bw, bottom, top, baseH, maxH, beatK,
                    peakVal[i], bh, col);
        }
        shaderPaint.setShader(null);
        if (freqAxis) drawFreqAxis(canvas, left, right, bottom, boxBottom, bw, gap);
    }

    // ------------------------------------------------------------ 2. 纵向示波（+零轴）

    private void drawColumnWave(Canvas canvas, float l, float t, float r, float b,
                                float tSec, float beat) {
        float left = l + dp(20);
        float right = r;
        float top = t + dp(2);
        float bottom = b - dp(1);
        if (bottom - top <= dp(3)) return;
        drawAxisGrid(canvas, left, top, right, bottom, true);

        float cy = (top + bottom) / 2f;
        float amp = (bottom - top) * 0.46f;
        final int n = smoothWave.length;

        path.reset();
        path.moveTo(left, cy);
        path2.reset();
        path2.moveTo(left, cy);
        for (int i = 0; i < n; i++) {
            float p = (float) i / (n - 1);
            float x = left + (right - left) * p;
            float y = cy - smoothWave[i] * amp;
            path.lineTo(x, y);
            path2.lineTo(x, cy + (cy - y) * 0.75f);
        }
        path.lineTo(right, cy);
        path2.lineTo(right, cy);
        path.close();
        path2.close();

        int col = colorAt(0.62f);
        shaderPaint.setAlpha(255); shaderPaint.setShader(new LinearGradient(0, top, 0, bottom,
                withAlpha(col, 0.55f), withAlpha(col, 0.02f), Shader.TileMode.CLAMP));
        canvas.drawPath(path, shaderPaint);
        shaderPaint.setAlpha(255); shaderPaint.setShader(new LinearGradient(0, cy, 0, bottom,
                withAlpha(colorAt(0.25f), 0.22f), withAlpha(colorAt(0.25f), 0.0f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(path2, shaderPaint);
        shaderPaint.setShader(null);

        // 波形描边
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.9f));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(withAlpha(col, 0.95f));
        path.reset();
        for (int i = 0; i < n; i++) {
            float p = (float) i / (n - 1);
            float x = left + (right - left) * p;
            float y = cy - smoothWave[i] * amp;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, strokePaint);

        strokePaint.setStrokeWidth(dp(4.5f));
        strokePaint.setColor(withAlpha(col, 0.10f + beat * 0.06f));
        canvas.drawPath(path, strokePaint);
        strokePaint.setStyle(Paint.Style.FILL);
    }

        // ------------------------------------------------------------ 柱状/示波 双声道

    /** 柱状双声道：按 stereoMode 决定分屏方式 */
    private void drawColumnSpectrumStereo(Canvas canvas, float l, float t, float r, float b,
                                          float beat, boolean active) {
        final float left = l + dp(20);
        final float right = r;
        final float top = t + dp(2);
        final float boxBottom = b - dp(1);
        final float axisH = freqAxis ? dp(12f) : 0f;
        final float bottom = boxBottom - axisH;
        if (bottom - top <= dp(6) || right - left <= dp(12)) return;
        drawAxisGrid(canvas, left, top, right, bottom, false);
        if (stereoMode == 3) {
            drawBarsRange(canvas, left, top, right, bottom, stViewR, beat, 2, 0.55f, active,
                    peakValR, peakRelR, peakHoldR);
            drawBarsRange(canvas, left, top, right, bottom, stViewL, beat, 1, 1f, active,
                    peakValL, peakRelL, peakHoldL);
            drawStereoTag(canvas, right - dp(34), top + dp(10), "L+R");
        } else if (stereoMode == 2) {
            float half = (right - left - dp(8)) * 0.5f;
            drawBarsRange(canvas, left, top, left + half, bottom, stViewL, beat, 1, 1f, active,
                    peakValL, peakRelL, peakHoldL);
            drawBarsRange(canvas, left + half + dp(8), top, right, bottom, stViewR, beat, 2, 1f,
                    active, peakValR, peakRelR, peakHoldR);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(0.8f));
            strokePaint.setColor(0x33FFFFFF);
            float mx = left + half + dp(4);
            canvas.drawLine(mx, top, mx, bottom, strokePaint);
            strokePaint.setStyle(Paint.Style.FILL);
            drawStereoTag(canvas, left, top + dp(10), "L");
            drawStereoTag(canvas, left + half + dp(8), top + dp(10), "R");
        } else {
            float mid = (top + bottom) * 0.5f;
            float halfGap = dp(5);
            drawBarsRange(canvas, left, top, right, mid - halfGap, stViewL, beat, 1, 1f, active,
                    peakValL, peakRelL, peakHoldL);
            drawBarsRange(canvas, left, mid + halfGap, right, bottom, stViewR, beat, 2, 1f, active,
                    peakValR, peakRelR, peakHoldR);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(0.8f));
            strokePaint.setColor(0x33FFFFFF);
            canvas.drawLine(left, mid, right, mid, strokePaint);
            strokePaint.setStyle(Paint.Style.FILL);
            drawStereoTag(canvas, left, mid - dp(6), "L");
            drawStereoTag(canvas, left, mid + dp(14), "R");
        }
        if (freqAxis) {
            float g2 = ((right - left) / (AudioEngine.BANDS + (AudioEngine.BANDS - 1) * 0.42f)) * 0.42f;
            float bw2 = (right - left) / (AudioEngine.BANDS + (AudioEngine.BANDS - 1) * 0.42f);
            drawFreqAxis(canvas, left, right, bottom, boxBottom, bw2, g2);
        }
    }

    /** 在指定矩形内画一组柱子（48 个频段） */
    private void drawBarsRange(Canvas canvas, float left, float top, float right, float bottom,
                               float[] data, float beat, int ch, float alphaMul, boolean active,
                               float[] pvArr, float[] relArr, long[] phArr) {
        final int n = AudioEngine.BANDS;
        final float gapRatio = 0.42f;
        float bw = (right - left) / (n + (n - 1) * gapRatio);
        if (bw <= 0.3f) return;
        float gap = bw * gapRatio;
        float maxH = bottom - top;
        final float beatK = 1f + beat * 0.06f;
        final float baseH = dp(1.6f);
        int savedCh = drawCh;
        drawCh = stereoMode == 3 ? ch : 0;
        for (int i = 0; i < n; i++) {
            float v = data[i];
            if (!(v > 0f)) v = 0f;
            if (v > 1f) v = 1f;
            float bh = baseH + (float) Math.pow(v, 0.80f) * maxH * beatK;
            if (!(bh > dp(1f))) bh = dp(1f);
            if (bh > maxH) bh = maxH;
            float x = left + i * (bw + gap);
            int col = colorAt((float) i / (n - 1));
            if (alphaMul < 1f) col = withAlpha(col, alphaMul);
            shaderPaint.setAlpha(255);
            shaderPaint.setShader(new LinearGradient(0, bottom - bh, 0, bottom,
                    col, withAlpha(col, 0.12f), Shader.TileMode.CLAMP));
            rectF.set(x, bottom - bh, x + bw, bottom);
            float rad = Math.min(bw * 0.45f, dp(2.5f));
            canvas.drawRoundRect(rectF, rad, rad, shaderPaint);
            if (pvArr != null && phArr != null && alphaMul >= 1f) {
                if (!active) {
                    pvArr[i] = 0f;
                    if (relArr != null) relArr[i] = 0f;
                    phArr[i] = 0L;
                } else {
                    updatePeak(pvArr, relArr, phArr, i, v);
                    drawPeakMark(canvas, x + bw / 2f, bw, bottom, top, baseH, maxH, beatK,
                            pvArr[i], bh, col);
                }
            }
        }
        shaderPaint.setShader(null);
        drawCh = savedCh;
    }

    /** 示波双声道：按 stereoMode 决定分屏方式 */
    private void drawColumnWaveStereo(Canvas canvas, float l, float t, float r, float b,
                                      float tSec, float beat) {
        final float left = l + dp(20);
        final float right = r;
        final float top = t + dp(2);
        final float bottom = b - dp(1);
        if (bottom - top <= dp(6)) return;
        drawAxisGrid(canvas, left, top, right, bottom, true);
        if (stereoMode == 3) {
            drawWaveRange(canvas, left, top, right, bottom, stSmoothR, beat, 2, 0.55f);
            drawWaveRange(canvas, left, top, right, bottom, stSmoothL, beat, 1, 1f);
            drawStereoTag(canvas, right - dp(34), top + dp(10), "L+R");
        } else if (stereoMode == 2) {
            float half = (right - left - dp(8)) * 0.5f;
            drawWaveRange(canvas, left, top, left + half, bottom, stSmoothL, beat, 1, 1f);
            drawWaveRange(canvas, left + half + dp(8), top, right, bottom, stSmoothR, beat, 2, 1f);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(0.8f));
            strokePaint.setColor(0x33FFFFFF);
            float mx = left + half + dp(4);
            canvas.drawLine(mx, top, mx, bottom, strokePaint);
            strokePaint.setStyle(Paint.Style.FILL);
            drawStereoTag(canvas, left, top + dp(10), "L");
            drawStereoTag(canvas, left + half + dp(8), top + dp(10), "R");
        } else {
            float mid = (top + bottom) * 0.5f;
            float halfGap = dp(5);
            drawWaveRange(canvas, left, top, right, mid - halfGap, stSmoothL, beat, 1, 1f);
            drawWaveRange(canvas, left, mid + halfGap, right, bottom, stSmoothR, beat, 2, 1f);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(0.8f));
            strokePaint.setColor(0x33FFFFFF);
            canvas.drawLine(left, mid, right, mid, strokePaint);
            strokePaint.setStyle(Paint.Style.FILL);
            drawStereoTag(canvas, left, top + dp(10), "L");
            drawStereoTag(canvas, left, mid + dp(12), "R");
        }
    }

    /** 在指定矩形内画一条波形 */
    private void drawWaveRange(Canvas canvas, float left, float top, float right, float bottom,
                               float[] data, float beat, int ch, float alphaMul) {
        if (bottom - top <= dp(4) || right - left <= dp(8)) return;
        float cy = (top + bottom) / 2f;
        float amp = (bottom - top) * 0.46f;
        final int n = data.length;
        path.reset();
        path.moveTo(left, cy);
        path2.reset();
        path2.moveTo(left, cy);
        for (int i = 0; i < n; i++) {
            float p = (float) i / (n - 1);
            float x = left + (right - left) * p;
            float y = cy - data[i] * amp;
            path.lineTo(x, y);
            path2.lineTo(x, cy + (cy - y) * 0.75f);
        }
        path.lineTo(right, cy);
        path2.lineTo(right, cy);
        path.close();
        path2.close();
        int savedCh = drawCh;
        drawCh = stereoMode == 3 ? ch : 0;
        int col = colorAt(0.62f);
        if (alphaMul < 1f) col = withAlpha(col, alphaMul);
        shaderPaint.setAlpha(255);
        shaderPaint.setShader(new LinearGradient(0, top, 0, bottom,
                withAlpha(col, 0.55f), withAlpha(col, 0.02f), Shader.TileMode.CLAMP));
        canvas.drawPath(path, shaderPaint);
        shaderPaint.setAlpha(255);
        shaderPaint.setShader(new LinearGradient(0, cy, 0, bottom,
                withAlpha(colorAt(0.25f), 0.22f), withAlpha(colorAt(0.25f), 0f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(path2, shaderPaint);
        shaderPaint.setShader(null);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.9f));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(withAlpha(col, 0.95f * alphaMul));
        path.reset();
        for (int i = 0; i < n; i++) {
            float p = (float) i / (n - 1);
            float x = left + (right - left) * p;
            float y = cy - data[i] * amp;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, strokePaint);
        strokePaint.setStyle(Paint.Style.FILL);
        drawCh = savedCh;
    }

    /**
     * 原始峰值弹道（v6.2 及更早版本的行为）：
     * 刷新高点后保持 PEAK_HOLD_MS，再从该高点线性回落，PEAK_FADE_MS 内追平当前值。
     */
    private void updatePeak(float[] valArr, float[] relArr, long[] holdArr, int i, float v) {
        long now = System.currentTimeMillis();
        if (v >= valArr[i]) {
            valArr[i] = v;
            relArr[i] = v;
            holdArr[i] = now + PEAK_HOLD_MS;
        } else if (now > holdArr[i]) {
            float prog = (now - holdArr[i]) / (float) PEAK_FADE_MS;
            if (prog >= 1f) {
                valArr[i] = v;
            } else {
                valArr[i] = relArr[i] + (v - relArr[i]) * prog;
            }
        }
    }

    /** 峰值标记：与柱同宽的短线，或带光晕的发光点（原始观感） */
    private void drawPeakMark(Canvas canvas, float cx, float bw, float bottom, float top,
                              float baseH, float maxH, float beatK, float pvRaw, float bh,
                              int col) {
        if (peakMode == 2) return;
        float pv = pvRaw;
        if (!(pv > 0.02f)) return;
        if (pv > 1f) pv = 1f;
        // 峰值与柱子用同一条 pow 曲线换算成像素高度
        float peakH = baseH + (float) Math.pow(pv, 0.80f) * maxH * beatK;
        float gapPx = peakH - bh;   // 像素级间隙：与灵敏度解耦
        if (gapPx <= dp(2.2f)) return;
        float py = bottom - peakH;
        if (py < top + dp(2f)) py = top + dp(2f);
        if (peakMode == 1) {
            // 标准 peak line：与柱同宽的短线
            float half = dp(0.8f);
            float ext = bw * 0.42f * 0.22f;
            rectF.set(cx - bw / 2f - ext, py - half, cx + bw / 2f + ext, py + half);
            fillPaint.setShader(null);
            fillPaint.setColor(withAlpha(mixWhite(col, 0.45f), 0.92f));
            canvas.drawRoundRect(rectF, half, half, fillPaint);
        } else {
            // 发光粒子：光晕不超过柱宽太多
            float gr = Math.min(dp(4.6f), Math.max(dp(2.6f), bw * 0.85f));
            fillPaint.setAlpha(255);
            fillPaint.setShader(new RadialGradient(cx, py, gr,
                    withAlpha(col, 0.45f), withAlpha(col, 0f), Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, py, gr, fillPaint);
            fillPaint.setShader(null);
            fillPaint.setColor(withAlpha(mixWhite(col, 0.75f), 0.95f));
            canvas.drawCircle(cx, py, Math.min(dp(1.7f), Math.max(dp(0.9f), bw * 0.22f)), fillPaint);
        }
    }

    /** 声道小标签 */
    private void drawStereoTag(Canvas canvas, float x, float y, String text) {
        textPaint.setTextSize(dp(8.5f));
        textPaint.setLetterSpacing(0.12f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(withAlpha(mixWhite(col1, 0.55f), 0.80f));
        canvas.drawText(text, x + dp(2), y, textPaint);
    }

    // ------------------------------------------------------------ 3. 环形频谱

    private void drawRingSpectrum(Canvas canvas, float l, float t, float r, float b,
                                  float tSec, float bass, float beat) {
        if (stereoMode == 3 && drawCh == 0) {
            int saved = drawCh;
            drawCh = 2;
            drawRingSpectrum(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = 1;
            drawRingSpectrum(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = saved;
            return;
        }
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float R = Math.min(r - l, b - t) * 0.5f - dp(4);
        if (R < dp(18)) R = dp(18);

        final int n = 96;
        float inner = R * 0.60f + beat * dp(2.5f);
        float maxLen = R * 0.38f;
        float spacing = (float) (2 * Math.PI * inner / n);
        float sw = Math.max(dp(1.4f), spacing * 0.62f);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        strokePaint.setStrokeWidth(dp(1.0f) + beat * dp(2.4f));
        strokePaint.setColor(withAlpha(col1, 0.20f + bass * 0.35f + beat * 0.25f));
        canvas.drawCircle(cx, cy, inner, strokePaint);

        float rot = tSec * 0.06f;
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n - Math.PI / 2 + rot;
            float v = specAtAng((float) i / n, ang);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            float r0 = inner;
            float r1 = r0 + dp(2.2f) + (0.06f + v) * maxLen;
            int col = colorAt((float) i / n);

            float x0 = cx + cos * r0, y0 = cy + sin * r0;
            float x1 = cx + cos * r1, y1 = cy + sin * r1;

            strokePaint.setStrokeWidth(sw * 2.8f);
            strokePaint.setColor(withAlpha(col, 0.06f + v * 0.10f + beat * 0.05f));
            canvas.drawLine(x0, y0, x1, y1, strokePaint);

            strokePaint.setStrokeWidth(sw);
            strokePaint.setColor(withAlpha(col, 0.62f + v * 0.36f));
            canvas.drawLine(x0, y0, x1, y1, strokePaint);

            if (v > 0.22f) {
                dotPaint.setColor(withAlpha(0xFFFFFFFF, Math.min(0.85f, v * 0.9f)));
                canvas.drawCircle(x1, y1, Math.max(dp(0.9f), sw * 0.30f), dotPaint);
            }
        }
        strokePaint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 4. 径向波形

    private void drawRingWave(Canvas canvas, float l, float t, float r, float b,
                              float tSec, float beat) {
        if (stereoMode == 3 && drawCh == 0) {
            int saved = drawCh;
            drawCh = 2;
            drawRingWave(canvas, l, t, r, b, tSec, beat);
            drawCh = 1;
            drawRingWave(canvas, l, t, r, b, tSec, beat);
            drawCh = saved;
            return;
        }
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float R = Math.min(r - l, b - t) * 0.5f - dp(4);
        if (R < dp(18)) R = dp(18);
        final int n = smoothWave.length;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        strokePaint.setStrokeWidth(dp(0.8f));
        strokePaint.setColor(withAlpha(col1, 0.14f));
        canvas.drawCircle(cx, cy, R * 0.52f, strokePaint);

        for (int layer = 0; layer < 3; layer++) {
            float baseR = R * (0.80f - layer * 0.13f) + beat * dp(4);
            float dir = (layer == 1) ? -1f : 1f;
            path.reset();
            for (int i = 0; i <= n; i++) {
                int idx = i % n;
                float p = (float) i / n;
                double ang = Math.PI * 2 * p - Math.PI / 2 + tSec * 0.12f * dir;
                float amp = waveAtAng(p, ang) * R * 0.16f * (1f - layer * 0.20f);
                float rr = baseR + amp;
                float x = cx + (float) Math.cos(ang) * rr;
                float y = cy + (float) Math.sin(ang) * rr;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();

            int col = colorAt(layer == 0 ? 0.12f : layer == 1 ? 0.5f : 0.88f);
            strokePaint.setStrokeWidth(dp(8f - layer * 2f));
            strokePaint.setColor(withAlpha(col, 0.10f + beat * 0.06f));
            canvas.drawPath(path, strokePaint);

            strokePaint.setStrokeWidth(dp(2.6f - layer * 0.5f));
            strokePaint.setColor(withAlpha(col, 0.92f - layer * 0.24f));
            canvas.drawPath(path, strokePaint);
        }
        strokePaint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 5. 粒子云

    private void drawParticles(Canvas canvas, float l, float t, float r, float b,
                               float tSec, float bass, float beat) {
        if (stereoMode == 3 && drawCh == 0) {
            int saved = drawCh;
            drawCh = 2;
            drawParticles(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = 1;
            drawParticles(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = saved;
            return;
        }
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float R = Math.min(r - l, b - t) * 0.5f - dp(4);
        if (R < dp(18)) R = dp(18);
        final int n = 64;

        float coreR = R * (0.30f + bass * 0.20f + beat * 0.10f);
        fillPaint.setAlpha(255); fillPaint.setShader(new RadialGradient(cx, cy, Math.max(coreR, dp(1)),
                withAlpha(col1, 0.30f + beat * 0.16f),
                withAlpha(col0, 0f), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, fillPaint);
        fillPaint.setShader(null);

        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n + tSec * 0.22f;
            float v = specAtAng((float) i / n, ang);
            float dist = R * (0.58f + v * 0.38f) + beat * R * 0.08f;
            xs[i] = cx + (float) Math.cos(ang) * dist;
            ys[i] = cy + (float) Math.sin(ang) * dist;
        }

        path.reset();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);
        path.close();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(0.7f));
        strokePaint.setColor(withAlpha(col2, 0.12f + beat * 0.14f));
        canvas.drawPath(path, strokePaint);
        strokePaint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < n; i++) {
            double ang2 = Math.PI * 2 * i / n + tSec * 0.22f;
            float v = specAtAng((float) i / n, ang2);
            int col = colorAt((float) i / n);
            float rad = dp(1.3f) + v * dp(4.4f) + beat * dp(1.2f);
            dotPaint.setColor(withAlpha(col, 0.12f + beat * 0.18f));
            canvas.drawCircle(xs[i], ys[i], rad * 3.0f, dotPaint);
            dotPaint.setColor(withAlpha(col, 0.95f));
            canvas.drawCircle(xs[i], ys[i], rad, dotPaint);
        }
        dotPaint.setColor(withAlpha(0xFFFFFFFF, 0.6f + beat * 0.35f));
        canvas.drawCircle(cx, cy, Math.max(dp(1.2f), R * 0.035f), dotPaint);
    }

    // ------------------------------------------------------------ 6. 极坐标圆盘

    private void drawDisc(Canvas canvas, float l, float t, float r, float b,
                          float tSec, float bass, float beat) {
        if (stereoMode == 3 && drawCh == 0) {
            int saved = drawCh;
            drawCh = 2;
            drawDisc(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = 1;
            drawDisc(canvas, l, t, r, b, tSec, bass, beat);
            drawCh = saved;
            return;
        }
        float cx = (l + r) / 2f;
        float cy = (t + b) / 2f;
        float R = Math.min(r - l, b - t) * 0.5f - dp(4);
        if (R < dp(18)) R = dp(18);
        final int n = 56;
        float innerR = R * 0.28f;
        float rot = tSec * 0.10f;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.SQUARE);

        for (int i = 0; i < n; i++) {
            double half = Math.PI / n * 0.78;
            double ang = Math.PI * 2 * i / n - Math.PI / 2 + rot;
            float v = specAtAng((float) i / n, ang);
            float r1 = innerR + R * (0.10f + v * 0.58f) + beat * dp(4);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            int col = colorAt((float) i / n);

            float width = (float) (2 * Math.sin(half) * (innerR + r1) * 0.5f);
            if (width < dp(1.2f)) width = dp(1.2f);

            strokePaint.setStrokeWidth(width * 2.1f);
            strokePaint.setColor(withAlpha(col, 0.07f + v * 0.12f));
            canvas.drawLine(cx + cos * innerR, cy + sin * innerR,
                    cx + cos * r1, cy + sin * r1, strokePaint);

            strokePaint.setStrokeWidth(width);
            strokePaint.setColor(withAlpha(col, 0.55f + v * 0.42f));
            canvas.drawLine(cx + cos * innerR, cy + sin * innerR,
                    cx + cos * r1, cy + sin * r1, strokePaint);
        }
        strokePaint.setStyle(Paint.Style.FILL);

        float hubR = R * (0.22f + bass * 0.10f + beat * 0.06f);
        fillPaint.setAlpha(255); fillPaint.setShader(new RadialGradient(cx, cy, Math.max(hubR, dp(1)),
                withAlpha(col1, 0.80f), withAlpha(col0, 0.05f),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, hubR, fillPaint);
        fillPaint.setShader(null);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.1f));
        strokePaint.setColor(withAlpha(0xFFFFFFFF, 0.40f));
        canvas.drawCircle(cx, cy, hubR * 1.22f, strokePaint);

        if (beat > 0.02f) {
            strokePaint.setStrokeWidth(dp(1.5f));
            strokePaint.setColor(withAlpha(col2, beat * 0.40f));
            canvas.drawCircle(cx, cy, R * (0.35f + (1f - beat) * 0.60f), strokePaint);
        }
        strokePaint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 工具

    private float sampleSpectrum(float t) {
        return sampleSpectrumOf(specA, t);
    }

    private float sampleSpectrumOf(float[] arr, float t) {
        if (t < 0f) t = 0f;
        if (t > 0.999f) t = 0.999f;
        float f = t * (arr.length - 1);
        int i0 = (int) f;
        int i1 = Math.min(arr.length - 1, i0 + 1);
        float frac = f - i0;
        float val = arr[i0] * (1f - frac) + arr[i1] * frac;
        if (!(val > 0f)) return 0f;
        if (val > 1f) return 1f;
        return val;
    }

    private float sampleWaveOf(float[] arr, float t) {
        if (t < 0f) t = 0f;
        if (t > 0.999f) t = 0.999f;
        float f = t * (arr.length - 1);
        int i0 = (int) f;
        int i1 = Math.min(arr.length - 1, i0 + 1);
        float frac = f - i0;
        float v = arr[i0] * (1f - frac) + arr[i1] * frac;
        if (!(v > -1f)) return -1f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 按角度决定用哪个声道：上下分屏看纵坐标，左右分屏看横坐标 */
    private int chAt(double ang) {
        if (drawCh != 0) return drawCh;
        if (stereoMode == 2) return Math.cos(ang) < 0f ? 1 : 2;
        return Math.sin(ang) < 0f ? 1 : 2;
    }

    private float specAtAng(float t, double ang) {
        if (stereoMode == 0) return sampleSpectrum(t);
        return sampleSpectrumOf(chAt(ang) == 1 ? stViewL : stViewR, t);
    }

    private float waveAtAng(float t, double ang) {
        if (stereoMode == 0) return sampleWaveOf(smoothWave, t);
        return sampleWaveOf(chAt(ang) == 1 ? stSmoothL : stSmoothR, t);
    }

    private int colorAt(float f) {
        int c = colorAtRaw(f);
        if (stereoMode == 3 && drawCh == 2) {
            Color.colorToHSV(c, hsv);
            hsv[0] = (hsv[0] + 42f) % 360f;
            hsv[1] = Math.min(1f, hsv[1] * 0.90f);
            return Color.HSVToColor(hsv);
        }
        return c;
    }

    private int colorAtRaw(float f) {
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        if (rainbow) {
            hsv[0] = (f * 300f + 195f) % 360f;
            hsv[1] = 0.72f;
            hsv[2] = 1f;
            return Color.HSVToColor(hsv);
        }
        if (f < 0.5f) return blend(col0, col1, f * 2f);
        return blend(col1, col2, (f - 0.5f) * 2f);
    }

    /** 把颜色向白色混合：电平表峰值亮线的标准做法 */
    private static int mixWhite(int color, float f) {
        return blend(color, 0xFFFFFF, f);
    }

    private static int blend(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * f);
        int gg = (int) (ag + (bg - ag) * f);
        int bl = (int) (ab + (bb - ab) * f);
        return Color.rgb(rr, gg, bl);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}