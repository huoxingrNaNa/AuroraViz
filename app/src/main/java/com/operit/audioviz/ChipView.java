package com.operit.audioviz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 胶囊选择按钮：选中态为渐变填充 + 外发光，未选中为细描边 */
public class ChipView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();

    private String text = "";
    private boolean checked = false;
    private Palette palette = Palette.NEON;
    private int col0 = 0xFF00F5FF, col1 = 0xFF7B5CFF, col2 = 0xFFFF2ED1, glowC = 0xFF39D9FF;


    public ChipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        setClickable(true);
    }

    public void setLabel(String t) {
        text = t == null ? "" : t;
        requestLayout();
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        int[] s = Scheme.colors(getContext(), p);
        col0 = s[0]; col1 = s[1]; col2 = s[2]; glowC = s[3];
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean c) {
        if (checked != c) {
            checked = c;
            invalidate();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        textPaint.setTextSize(dp(13));
        float need = textPaint.measureText(text) + dp(28);
        int h = (int) dp(46);
        int wMode = MeasureSpec.getMode(widthSpec);
        int wSize = MeasureSpec.getSize(widthSpec);
        int width;
        if (wMode == MeasureSpec.EXACTLY) {
            width = wSize;
        } else if (wMode == MeasureSpec.AT_MOST) {
            width = (int) Math.min(need, wSize);
        } else {
            width = (int) need;
        }
        setMeasuredDimension(width, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;
        rectF.set(0, 0, w, h);

        // 填充 Paint：每次重新设定状态，避免 shader 残留污染颜色
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setShader(null);
        // 关键：Paint.setColor() 会同时改 Paint 的 alpha，且 setShader 不会重置它。
        // 若先走过未选中分支(0x14FFFFFF -> alpha=20)，渐变就会被乘上 7.8% 而变得漆黑。
        bgPaint.setAlpha(255);
        if (checked) {
            // 注意：本机 Skia 会把「带 alpha 的 LinearGradient」渲染成极暗（实测约 6% 亮度），
            // 因此这里使用不透明渐变；按压反馈改由描边/整体缩放体现。
            bgPaint.setShader(new LinearGradient(0, 0, w, h, col0, col2, Shader.TileMode.CLAMP));
        } else {
            bgPaint.setColor(0x14FFFFFF);
        }
        canvas.drawRoundRect(rectF, radius, radius, bgPaint);
        bgPaint.setShader(null);

        // 描边 Paint：始终纯色，不参与渐变
        strokePaint.setStyle(Paint.Style.STROKE);
        if (checked) {
            strokePaint.setStrokeWidth(dp(2.2f));
            // 按压反馈：描边在按下时变亮（纯色 alpha 在本机渲染正常）
            strokePaint.setColor(withAlpha(glowC, isPressed() ? 0.85f : 0.45f));
        } else {
            strokePaint.setStrokeWidth(dp(1.1f));
            strokePaint.setColor(0x33FFFFFF);
        }
        canvas.drawRoundRect(rectF, radius, radius, strokePaint);

        textPaint.setColor(checked ? 0xFFFFFFFF : 0xFF9AA5C4);
        float baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, w / 2f, baseline, textPaint);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}