package com.operit.audioviz;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置存储（主界面与悬浮窗服务共享） */
public final class Prefs {

    private static final String FILE = "aurora_prefs";
    private static final String K_STYLE = "style";
    private static final String K_PALETTE = "palette";
    private static final String K_SOURCE = "source";
    private static final String K_SIZE = "size";
    private static final String K_SENS = "sens";
    private static final String K_OVERLAY = "overlay_on";
    private static final String K_UI_STYLE = "ui_style";
    private static final String K_PEAK = "peak_glow";
    private static final String K_C0 = "custom_c0";
    private static final String K_C1 = "custom_c1";
    private static final String K_C2 = "custom_c2";
    private static final String K_IDLE = "idle_anim";
    private static final String K_ALPHA = "overlay_alpha";
    private static final String K_BARE = "overlay_bare";
    private static final String K_POSX = "overlay_x";
    private static final String K_POSY = "overlay_y";

    public static final int SOURCE_MIC = 0;
    public static final int SOURCE_SYSTEM = 1;

    private static final String K_REC_RES = "rec_res";
    private static final String K_REC_FPS = "rec_fps";
    private static final String K_REC_BR = "rec_br";
    private static final String K_SMOOTH = "viz_smooth";
    private static final String K_VIZ_HZ = "viz_hz";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int style(Context c) {
        return sp(c).getInt(K_STYLE, 0);
    }

    public static void setStyle(Context c, int v) {
        sp(c).edit().putInt(K_STYLE, v).apply();
    }

    public static int palette(Context c) {
        return sp(c).getInt(K_PALETTE, 0);
    }

    public static void setPalette(Context c, int v) {
        sp(c).edit().putInt(K_PALETTE, v).apply();
    }

    public static int source(Context c) {
        return sp(c).getInt(K_SOURCE, SOURCE_MIC);
    }

    public static void setSource(Context c, int v) {
        sp(c).edit().putInt(K_SOURCE, v).apply();
    }

    /** 悬浮窗尺寸比例 0.55f ~ 1.6f */
    public static float size(Context c) {
        return sp(c).getFloat(K_SIZE, 1.0f);
    }

    public static void setSize(Context c, float v) {
        sp(c).edit().putFloat(K_SIZE, v).apply();
    }

    /** 灵敏度 0.4f ~ 2.6f */
    public static float sens(Context c) {
        return sp(c).getFloat(K_SENS, 1.0f);
    }

    public static void setSens(Context c, float v) {
        sp(c).edit().putFloat(K_SENS, v).apply();
    }

    /** 是否显示悬浮窗（关闭时只在应用内可视化） */
    public static boolean overlayEnabled(Context c) {
        return sp(c).getBoolean(K_OVERLAY, true);
    }

    public static void setOverlayEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_OVERLAY, v).apply();
    }

    /** 界面风格：0 深空 / 1 纯黑 / 2 跟随系统 */
    public static int uiStyle(Context c) {
        return sp(c).getInt(K_UI_STYLE, 0);
    }

    public static void setUiStyle(Context c, int v) {
        sp(c).edit().putInt(K_UI_STYLE, v).apply();
    }

    /** 柱状模式峰值标记：0 发光粒子 / 1 细线 / 2 关闭 */
    public static int peakGlow(Context c) {
        return sp(c).getInt(K_PEAK, 0);
    }

    public static void setPeakGlow(Context c, int v) {
        sp(c).edit().putInt(K_PEAK, v).apply();
    }

    /** 自定义配色：{起始, 中间, 结束} */
    public static int[] customColors(Context c) {
        return new int[]{
                sp(c).getInt(K_C0, 0xFF00F5FF),
                sp(c).getInt(K_C1, 0xFF7B5CFF),
                sp(c).getInt(K_C2, 0xFFFF2ED1)
        };
    }

    public static boolean idleAnim(Context c) {
        return sp(c).getBoolean(K_IDLE, true);
    }

    public static void setIdleAnim(Context c, boolean v) {
        sp(c).edit().putBoolean(K_IDLE, v).apply();
    }

    /** 悬浮窗不透明度 0.25f ~ 1.0f */
    public static float alpha(Context c) {
        return sp(c).getFloat(K_ALPHA, 1.0f);
    }

    public static void setAlpha(Context c, float v) {
        sp(c).edit().putFloat(K_ALPHA, v).apply();
    }

    /** 透明背景：不画卡片底色，只留波形 */
    public static boolean bare(Context c) {
        return sp(c).getBoolean(K_BARE, false);
    }

    public static void setBare(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BARE, v).apply();
    }

    /** 悬浮窗记住的位置（MIN_VALUE 表示未设置） */
    public static int posX(Context c) {
        return sp(c).getInt(K_POSX, Integer.MIN_VALUE);
    }

    public static int posY(Context c) {
        return sp(c).getInt(K_POSY, Integer.MIN_VALUE);
    }

    public static void setPos(Context c, int x, int y) {
        sp(c).edit().putInt(K_POSX, x).putInt(K_POSY, y).apply();
    }

    public static void setCustomColors(Context c, int c0, int c1, int c2) {
        sp(c).edit().putInt(K_C0, c0).putInt(K_C1, c1).putInt(K_C2, c2).apply();
    }

    public static int recRes(Context c) {
        return sp(c).getInt(K_REC_RES, 0);
    }

    public static void setRecRes(Context c, int v) {
        sp(c).edit().putInt(K_REC_RES, v).apply();
    }

    public static int recFps(Context c) {
        return sp(c).getInt(K_REC_FPS, 1);
    }

    public static void setRecFps(Context c, int v) {
        sp(c).edit().putInt(K_REC_FPS, v).apply();
    }

    public static int recBr(Context c) {
        return sp(c).getInt(K_REC_BR, 1);
    }

    public static void setRecBr(Context c, int v) {
        sp(c).edit().putInt(K_REC_BR, v).apply();
    }

    public static int vizSmooth(Context c) {
        return sp(c).getInt(K_SMOOTH, 1);
    }

    public static void setVizSmooth(Context c, int v) {
        sp(c).edit().putInt(K_SMOOTH, v).apply();
    }

    /** 平滑档位 → 时间常数(ms) */
    public static float vizSmoothTau(Context c) {
        return smoothTauOf(vizSmooth(c));
    }

    public static float smoothTauOf(int idx) {
        return idx <= 0 ? 16f : idx == 1 ? 30f : 52f;
    }

    public static int vizHz(Context c) {
        return sp(c).getInt(K_VIZ_HZ, 0);
    }

    public static void setVizHz(Context c, int v) {
        sp(c).edit().putInt(K_VIZ_HZ, v).apply();
    }

    /** 档位 → 实际帧率（0 表示跟随屏幕刷新） */
    public static int vizHzValue(int idx) {
        return idx <= 0 ? 0 : idx == 1 ? 30 : idx == 2 ? 60 : 120;
    }


}
