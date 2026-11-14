package com.operit.audioviz;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 自定义调色面板：三个渐变节点（起始 / 中间 / 结束）+ 色域板 + 色相条 */
public class ColorPickerDialog extends Dialog {

    public interface OnPicked {
        void onPicked(int c0, int c1, int c2);
    }

    private static final String[] SLOT_NAMES = {"起始", "中间", "结束"};

    private final float[][] slots = new float[3][3];
    private final View[] dots = new View[3];
    private final TextView[] labels = new TextView[3];
    private final Palette palette;

    private SatValView sv;
    private HueBar hueBar;
    private View previewBar;
    private int selected = 0;

    public ColorPickerDialog(Context ctx, Palette palette, int[] initial, OnPicked listener) {
        super(ctx);
        this.palette = palette;
        for (int i = 0; i < 3; i++) {
            Color.colorToHSV(initial[Math.min(i, initial.length - 1)], slots[i]);
        }
        build(ctx, listener);
        select(0);
    }

    private float dp(float v) {
        return v * getContext().getResources().getDisplayMetrics().density;
    }

    private void build(Context ctx, OnPicked listener) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) dp(18), (int) dp(18), (int) dp(18), (int) dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1018);
        bg.setCornerRadius(dp(24));
        bg.setStroke((int) dp(1), 0x22FFFFFF);
        root.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("自定义配色");
        title.setTextColor(0xFFF2F5FF);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("渐变由三个节点组成，点下方圆点切换要调整的节点");
        sub.setTextColor(0xFF7E88A8);
        sub.setTextSize(9.5f);
        sub.setPadding(0, (int) dp(4), 0, (int) dp(12));
        root.addView(sub);

        // 三个节点
        LinearLayout slotRow = new LinearLayout(ctx);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(clp);

            View dot = new View(ctx);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    (int) dp(40), (int) dp(40));
            dot.setLayoutParams(dlp);
            dots[i] = dot;
            col.addView(dot);

            TextView label = new TextView(ctx);
            label.setText(SLOT_NAMES[i]);
            label.setTextColor(0xFF8A93B2);
            label.setTextSize(9.5f);
            label.setPadding(0, (int) dp(6), 0, 0);
            labels[i] = label;
            col.addView(label);

            col.setOnClickListener(v -> select(idx));
            slotRow.addView(col);
        }
        root.addView(slotRow);

        // 色域板
        sv = new SatValView(ctx);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(180));
        svp.topMargin = (int) dp(14);
        sv.setLayoutParams(svp);
        sv.setListener((s, v) -> {
            slots[selected][1] = s;
            slots[selected][2] = v;
            refreshSlots();
            refreshPreview();
        });
        root.addView(sv);

        // 色相条
        hueBar = new HueBar(ctx);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(30));
        hlp.topMargin = (int) dp(12);
        hueBar.setLayoutParams(hlp);
        hueBar.setListener(h -> {
            slots[selected][0] = h;
            sv.setHue(h);
            refreshSlots();
            refreshPreview();
        });
        root.addView(hueBar);

        // 预览
        previewBar = new View(ctx);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(36));
        plp.topMargin = (int) dp(14);
        previewBar.setLayoutParams(plp);
        root.addView(previewBar);

        // 按钮
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) dp(16);
        btnRow.setLayoutParams(blp);

        GhostButton cancel = new GhostButton(ctx, null);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(0, (int) dp(46), 1f));
        cancel.setLabel("取消");
        cancel.setPalette(palette);
        cancel.setOnClickListener(v -> dismiss());
        btnRow.addView(cancel);

        GradientButton apply = new GradientButton(ctx, null);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, (int) dp(46), 1f);
        alp.leftMargin = (int) dp(10);
        apply.setLayoutParams(alp);
        apply.setLabel("应用");
        apply.setPalette(palette);
        apply.setOnClickListener(v -> {
            if (listener != null) {
                int[] c = currentColors();
                listener.onPicked(c[0], c[1], c[2]);
            }
            dismiss();
        });
        btnRow.addView(apply);
        root.addView(btnRow);

        setContentView(root);
        setCanceledOnTouchOutside(true);
        refreshSlots();
        refreshPreview();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = (int) (getContext().getResources()
                .getDisplayMetrics().widthPixels * 0.90f);
        w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void select(int i) {
        selected = i;
        sv.setHue(slots[i][0]);
        sv.setSatVal(slots[i][1], slots[i][2]);
        hueBar.setHue(slots[i][0]);
        refreshSlots();
    }

    private int[] currentColors() {
        int[] c = new int[3];
        float[] tmp = new float[3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(slots[i], 0, tmp, 0, 3);
            c[i] = Color.HSVToColor(tmp);
        }
        return c;
    }

    private void refreshSlots() {
        int[] c = currentColors();
        for (int i = 0; i < 3; i++) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(c[i]);
            gd.setStroke((int) dp(i == selected ? 2.4f : 1f),
                    i == selected ? 0xFFFFFFFF : 0x33FFFFFF);
            dots[i].setBackground(gd);
            labels[i].setTextColor(i == selected ? 0xFFFFFFFF : 0xFF8A93B2);
            labels[i].setTypeface(i == selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void refreshPreview() {
        int[] c = currentColors();
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, c);
        gd.setCornerRadius(dp(10));
        gd.setStroke((int) dp(1), 0x22FFFFFF);
        previewBar.setBackground(gd);
    }
}