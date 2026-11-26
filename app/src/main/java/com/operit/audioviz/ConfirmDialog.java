package com.operit.audioviz;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 与主题一致的小确认框：深色圆角卡 +「取消 / 确认」两枚项目自带按钮。
 * 用它替代系统 AlertDialog——后者是白底 Material 风格，和深空界面完全不搭。
 */
public class ConfirmDialog extends Dialog {

    public interface OnConfirm {
        void onConfirm();
    }

    private final Palette palette;
    /** 只有「知道了」这类单按钮说明框时为 false */
    private final boolean showCancel;

    public ConfirmDialog(Context ctx, Palette palette, String title, String message,
                         String confirmLabel, OnConfirm listener) {
        this(ctx, palette, title, message, confirmLabel, listener, true);
    }

    public ConfirmDialog(Context ctx, Palette palette, String title, String message,
                         String confirmLabel, OnConfirm listener, boolean showCancel) {
        super(ctx);
        this.palette = palette == null ? Palette.NEON : palette;
        this.showCancel = showCancel;
        build(ctx, title, message, confirmLabel, listener);
    }

    private float dp(float v) {
        return v * getContext().getResources().getDisplayMetrics().density;
    }

    private void build(Context ctx, String title, String message,
                       String confirmLabel, OnConfirm listener) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) dp(20), (int) dp(20), (int) dp(20), (int) dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1018);
        bg.setCornerRadius(dp(24));
        bg.setStroke((int) dp(1), 0x22FFFFFF);
        root.setBackground(bg);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title == null ? "" : title);
        tvTitle.setTextColor(0xFFF2F5FF);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvMsg = new TextView(ctx);
        tvMsg.setText(message == null ? "" : message);
        tvMsg.setTextColor(0xFF8A93B2);
        tvMsg.setTextSize(11f);
        tvMsg.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = (int) dp(9);
        tvMsg.setLayoutParams(mlp);
        root.addView(tvMsg);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) dp(18);
        btnRow.setLayoutParams(blp);

        GhostButton cancel = new GhostButton(ctx, null);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(0, (int) dp(44), 1f));
        cancel.setLabel("取消");
        cancel.setPalette(palette);
        cancel.setOnClickListener(v -> dismiss());
        if (showCancel) btnRow.addView(cancel);

        GradientButton ok = new GradientButton(ctx, null);
        LinearLayout.LayoutParams olp = showCancel
                ? new LinearLayout.LayoutParams(0, (int) dp(44), 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(44));
        if (showCancel) olp.leftMargin = (int) dp(10);
        ok.setLayoutParams(olp);
        ok.setLabel(confirmLabel == null ? "确定" : confirmLabel);
        ok.setPalette(palette);
        ok.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onConfirm();
        });
        btnRow.addView(ok);
        root.addView(btnRow);

        setContentView(root);
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = (int) (getContext().getResources()
                .getDisplayMetrics().widthPixels * 0.86f);
        w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }
}