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

    private final float[] specA = new float[AudioEngine.BANDS];
    private final float[] waveA = new float[AudioEngine.WAVE_POINTS];
    private final float[] smoothWave = new float[AudioEngine.WAVE_POINTS];
    /** 峰值弹道：hold 500ms + fade 750ms（与帧率无关，时间为唯一基准） */
    private final float[] peakVal = new float[AudioEngine.BANDS];
    private final float[] peakRel = new float[AudioEngine.BANDS];
    private final long[] peakHoldUntil = new long[AudioEngine.BANDS];
    private static final long PEAK_HOLD_MS = 500L;
    private static final long PEAK_FADE_MS = 750L;

    private Style style = Style.COLUMN;
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;
    private boolean rainbow = false;
    private boolean drawBackground = false;
    /** 自驱重绘开关：录制导出时用独立 View 手动逐帧绘制，需关掉自驱 */
    private boolean selfDrive = true;
    private boolean showModeLabel = false;
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

    public void setStyle(Style s) {
        if (s == null) return;
        if (style != s) {
            for (int i = 0; i < peakVal.length; i++) {
                peakVal[i] = 0f;
                peakRel[i] = 0f;
                peakHoldUntil[i] = 0L;
            }
        }
        style = s;
        invalidate();
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
    public void setSelfDrive(boolean enabled) {
        selfDrive = enabled;
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
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float tSec = (System.nanoTime() - bornAt) / 1_000_000_000f;
        final AudioEngine engine = AudioEngine.get();
        engine.copySpectrum(specA);
        engine.copyWave(waveA);

        boolean active = engine.isRunning()
                && System.currentTimeMillis() - engine.lastFrameAt() < 700;
        float bass = active ? engine.bassLevel() : 0f;
        float beat = active ? engine.beat() : 0f;

        for (int i = 0; i < smoothWave.length; i++) {
            smoothWave[i] += (waveA[i] - smoothWave[i]) * 0.42f;
        }

        if (!active && !idleAnim) {
            java.util.Arrays.fill(specA, 0f);
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

        if (drawBackground) drawGlass(canvas, w, h);

        float pad = drawBackground ? dp(14) : dp(6);
        float l = pad, t = pad, r = w - pad, b = h - pad;

        switch (style) {
            case SCOPE:
                drawColumnWave(canvas, l, t, r, b, tSec, beat);
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
                drawColumnSpectrum(canvas, l, t, r, b, beat, active);
                break;
        }

        if (showModeLabel) drawModeLabel(canvas, w, h);
        if (selfDrive) postInvalidateOnAnimation();
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
        textPaint.setTextSize(dp(8.5f));
        textPaint.setLetterSpacing(0.14f);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        String label = style.label + " · " + palette.label;
        float tw = textPaint.measureText(label);
        float baseY = h - dp(10);
        rectF.set(w - dp(14) - tw - dp(5), baseY - dp(9.5f),
                w - dp(14) + dp(5), baseY + dp(3.5f));
        fillPaint.setShader(null);
        fillPaint.setColor(0x66000000);
        canvas.drawRoundRect(rectF, dp(4), dp(4), fillPaint);
        textPaint.setColor(withAlpha(mixWhite(col2, 0.25f), 0.92f));
        canvas.drawText(label, w - dp(14), baseY, textPaint);
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
        float bottom = b - dp(1);
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
            if (v >= peakVal[i]) {
                peakVal[i] = v;
                peakRel[i] = v;
                peakHoldUntil[i] = nowMs + PEAK_HOLD_MS;
            } else if (nowMs > peakHoldUntil[i]) {
                float prog = (nowMs - peakHoldUntil[i]) / (float) PEAK_FADE_MS;
                if (prog >= 1f) {
                    peakVal[i] = v;
                } else {
                    peakVal[i] = peakRel[i] + (v - peakRel[i]) * prog;
                }
            }

            // 峰值也用同一条 pow 曲线换算成像素高度
            float pv = peakVal[i];
            if (pv > 1f) pv = 1f;
            float peakH = baseH + (float) Math.pow(pv, 0.80f) * maxH * beatK;
            float gapPx = peakH - bh;   // 像素级间隙：与灵敏度解耦
            if (peakMode != 2 && gapPx > dp(2.2f) && pv > 0.02f) {
                float py = bottom - peakH;
                if (py < top + dp(2f)) py = top + dp(2f);
                float cx = x + bw / 2f;
                if (peakMode == 1) {
                    // 标准 peak line：与柱同宽的 2dp 亮线
                    float half = dp(0.8f);
                    float ext = gap * 0.22f;
                    rectF.set(x - ext, py - half, x + bw + ext, py + half);
                    fillPaint.setColor(withAlpha(mixWhite(col, 0.45f), 0.92f));
                    canvas.drawRoundRect(rectF, half, half, fillPaint);
                } else {
                    // 发光粒子（默认）：光晕不超过柱宽，避免跨到相邻柱间
                    float gr = Math.min(dp(4.6f), Math.max(dp(2.6f), bw * 0.85f));
                    fillPaint.setAlpha(255); fillPaint.setShader(new RadialGradient(cx, py, gr,
                            withAlpha(col, 0.45f), withAlpha(col, 0f), Shader.TileMode.CLAMP));
                    canvas.drawCircle(cx, py, gr, fillPaint);
                    fillPaint.setShader(null);
                    fillPaint.setColor(withAlpha(mixWhite(col, 0.75f), 0.95f));
                    canvas.drawCircle(cx, py, Math.min(dp(1.7f), Math.max(dp(0.9f), bw * 0.22f)), fillPaint);
                }
            }
        }
        shaderPaint.setShader(null);
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

    // ------------------------------------------------------------ 3. 环形频谱

    private void drawRingSpectrum(Canvas canvas, float l, float t, float r, float b,
                                  float tSec, float bass, float beat) {
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
            float v = sampleSpectrum((float) i / n);
            double ang = Math.PI * 2 * i / n - Math.PI / 2 + rot;
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
                float amp = smoothWave[idx] * R * 0.16f * (1f - layer * 0.20f);
                double ang = Math.PI * 2 * p - Math.PI / 2 + tSec * 0.12f * dir;
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
            float v = sampleSpectrum((float) i / n);
            double ang = Math.PI * 2 * i / n + tSec * 0.22f;
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
            float v = sampleSpectrum((float) i / n);
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
            float v = sampleSpectrum((float) i / n);
            double half = Math.PI / n * 0.78;
            double ang = Math.PI * 2 * i / n - Math.PI / 2 + rot;
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
        if (t < 0f) t = 0f;
        if (t > 0.999f) t = 0.999f;
        float f = t * (AudioEngine.BANDS - 1);
        int i0 = (int) f;
        int i1 = Math.min(AudioEngine.BANDS - 1, i0 + 1);
        float frac = f - i0;
        float val = specA[i0] * (1f - frac) + specA[i1] * frac;
        if (!(val > 0f)) return 0f;
        if (val > 1f) return 1f;
        return val;
    }

    private int colorAt(float f) {
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