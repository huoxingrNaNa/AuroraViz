package com.operit.audioviz;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 主操作按钮：渐变填充 + 文字居中 + 按压反馈 */
public class GradientButton extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();

    private String text = "";
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;


    public GradientButton(Context context, AttributeSet attrs) {
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
        float pressed = isPressed() ? 0.82f : 1f;
        rectF.set(0, 0, w, h);

        // 外发光
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(dp(6));
        bgPaint.setColor(withAlpha(glowC, 0.10f * pressed));
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);

        // 主体渐变
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setAlpha(255); // 清除上一步 setColor 残留的低 alpha，否则渐变会被乘暗
        bgPaint.setShader(new LinearGradient(0, 0, w, h,
                withAlpha(col0, 0.95f * pressed),
                withAlpha(col2, 0.95f * pressed), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);
        bgPaint.setShader(null);

        // 顶部高光
        shinePaint.setAlpha(255);
        shinePaint.setShader(new LinearGradient(0, 0, 0, h * 0.55f,
                withAlpha(0xFFFFFFFF, 0.22f * pressed),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        RectF shine = new RectF(rectF);
        canvas.drawRoundRect(shine, radius, radius, shinePaint);
        shinePaint.setShader(null);

        // 白色内描边
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(dp(1.1f));
        bgPaint.setColor(withAlpha(0xFFFFFFFF, 0.28f * pressed));
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);

        textPaint.setTextSize(dp(15.5f));
        textPaint.setLetterSpacing(0.06f);
        textPaint.setColor(withAlpha(0xFFFFFFFF, pressed));
        float baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, w / 2f, baseline, textPaint);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}