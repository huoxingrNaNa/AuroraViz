package com.operit.audioviz;

import android.content.Context;

/** 把调色板解析为实际颜色（自定义配色从 Prefs 读取） */
public final class Scheme {

    private Scheme() {
    }

    /** 返回 {c0, c1, c2, glow} */
    public static int[] colors(Context ctx, Palette p) {
        if (ctx != null && p == Palette.CUSTOM) {
            int[] c = Prefs.customColors(ctx);
            int glow = c[1];
            return new int[]{c[0], c[1], c[2], glow};
        }
        return new int[]{p.c0, p.c1, p.c2, p.glow};
    }

    public static boolean isRainbow(Palette p) {
        return p == Palette.RAINBOW;
    }
}
