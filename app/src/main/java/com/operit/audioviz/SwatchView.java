package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 配色色块：渐变圆盘，选中时显示外环 */
public class SwatchView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;

    private boolean checked = false;

    public SwatchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
    }

    public void setPalette(Palette p) {
        palette = p;
        int[] s = Scheme.colors(getContext(), p);
        col0 = s[0]; col1 = s[1]; col2 = s[2]; glowC = s[3];
        invalidate();
    }

    public void setChecked(boolean c) {
        if (checked != c) {
            checked = c;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float w = getWidth();
        float h = getHeight();
        float pad = Math.min(w, h) * 0.13f;
        rectF.set(pad, pad, w - pad, h - pad);

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255); // 清除上一帧 setColor(0xCCFFFFFF) 残留的 alpha
        paint.setShader(new LinearGradient(0, 0, w, h,
                col0, col2, Shader.TileMode.CLAMP));
        canvas.drawOval(rectF, paint);
        paint.setShader(null);

        if (checked) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.min(w, h) * 0.055f);
            paint.setColor(0xCCFFFFFF);
            canvas.drawOval(rectF, paint);

            paint.setStrokeWidth(Math.min(w, h) * 0.028f);
            paint.setColor(0x33FFFFFF);
            RectF outer = new RectF(1f, 1f, w - 1f, h - 1f);
            canvas.drawOval(outer, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.min(w, h) * 0.03f);
            paint.setColor(0x22FFFFFF);
            canvas.drawOval(rectF, paint);
        }
    }
}