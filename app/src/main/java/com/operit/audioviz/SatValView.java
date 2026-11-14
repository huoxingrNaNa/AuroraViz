package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** 饱和度 / 亮度二维取色面板 */
public class SatValView extends View {

    public interface OnPick {
        void onPick(float sat, float val);
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private final float[] hsv = new float[3];

    private float hue = 200f, sat = 1f, val = 1f;
    private OnPick listener;

    public SatValView(Context context) {
        super(context);
        markerPaint.setStyle(Paint.Style.STROKE);
    }

    public void setListener(OnPick l) {
        listener = l;
    }

    public void setHue(float h) {
        hue = h;
        invalidate();
    }

    public void setSatVal(float s, float v) {
        sat = s;
        val = v;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = dp(14);
        rectF.set(0, 0, w, h);

        hsv[0] = hue;
        hsv[1] = 1f;
        hsv[2] = 1f;
        basePaint.setShader(null);
        basePaint.setColor(Color.HSVToColor(hsv));
        canvas.drawRoundRect(rectF, r, r, basePaint);

        // 左白（低饱和）
        overPaint.setAlpha(255); overPaint.setShader(new LinearGradient(0, 0, w, 0,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, r, r, overPaint);

        // 下黑（低亮度）
        overPaint.setAlpha(255); overPaint.setShader(new LinearGradient(0, 0, 0, h,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, r, r, overPaint);
        overPaint.setShader(null);

        // 当前位置指示
        float px = sat * w;
        float py = (1f - val) * h;
        markerPaint.setStrokeWidth(dp(2f));
        markerPaint.setColor(val > 0.55f ? 0xCC000000 : 0xFFFFFFFF);
        canvas.drawCircle(px, py, dp(8), markerPaint);
        markerPaint.setStrokeWidth(dp(1.2f));
        markerPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(px, py, dp(10), markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                sat = Math.max(0f, Math.min(1f, e.getX() / Math.max(1f, getWidth())));
                val = 1f - Math.max(0f, Math.min(1f, e.getY() / Math.max(1f, getHeight())));
                if (listener != null) listener.onPick(sat, val);
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }
}