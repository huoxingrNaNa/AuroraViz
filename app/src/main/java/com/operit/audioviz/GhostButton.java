package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 次要按钮：细描边胶囊，用于「一键授权」等辅助操作 */
public class GhostButton extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();

    private String text = "";
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;


    public GhostButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        setClickable(true);
        setFocusable(true);
    }

    public void setLabel(String t) {
        text = t == null ? "" : t;
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        int[] s = Scheme.colors(getContext(), p);
        col0 = s[0]; col1 = s[1]; col2 = s[2]; glowC = s[3];
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;
        float pressed = isPressed() ? 0.85f : 1f;
        rectF.set(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f));

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(withAlpha(col1, 0.10f * pressed));
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);

        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(dp(1.2f));
        bgPaint.setColor(withAlpha(col1, 0.55f * pressed));
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);

        textPaint.setTextSize(dp(12.5f));
        textPaint.setLetterSpacing(0.04f);
        textPaint.setColor(withAlpha(0xFFFFFFFF, 0.92f * pressed));
        float baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, w / 2f, baseline, textPaint);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}