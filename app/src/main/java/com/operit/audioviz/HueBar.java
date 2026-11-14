package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** 色相条 0~360° */
public class HueBar extends View {

    public interface OnHue {
        void onHue(float hue);
    }

    private static final int[] HUES = {
            0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF00E5FF, 0xFF2C7BFF, 0xFF9B5DE5, 0xFFFF3B30
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();

    private float hue = 200f;
    private OnHue listener;

    public HueBar(Context context) {
        super(context);
        marker.setStyle(Paint.Style.STROKE);
    }

    public void setListener(OnHue l) {
        listener = l;
    }

    public void setHue(float h) {
        hue = h;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        rectF.set(0, dp(4), w, h - dp(4));

        paint.setAlpha(255); paint.setShader(new LinearGradient(0, 0, w, 0, HUES, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, r, r, paint);
        paint.setShader(null);

        float x = Math.max(dp(3), Math.min(w - dp(3), hue / 360f * w));
        marker.setStrokeWidth(dp(2.4f));
        marker.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(new RectF(x - dp(2.4f), rectF.top - dp(2f),
                        x + dp(2.4f), rectF.bottom + dp(2f)),
                dp(2), dp(2), marker);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                hue = Math.max(0f, Math.min(360f, e.getX() / Math.max(1f, getWidth()) * 360f));
                if (listener != null) listener.onHue(hue);
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }
}