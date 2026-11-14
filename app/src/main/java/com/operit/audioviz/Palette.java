package com.operit.audioviz;

/**
 * 可视化配色：霓虹 / 银色 / 彩虹 / 海洋 / 日落 / 极简。
 * 彩虹主题走 HSV 循环，其余为三段渐变。
 */
public enum Palette {
    NEON("霓虹", 0xFF00F5FF, 0xFF7B5CFF, 0xFFFF2ED1, 0xFF39D9FF, false),
    SILVER("银色", 0xFFFFFFFF, 0xFFCBD5E8, 0xFF808BA5, 0xFFE6ECFA, false),
    RAINBOW("彩虹", 0xFFFF3B30, 0xFFFFCC00, 0xFF00C2FF, 0xFFFF7AD9, true),
    OCEAN("海洋", 0xFF2CF0FF, 0xFF2C7BFF, 0xFF6A3DFF, 0xFF37B6FF, false),
    SUNSET("日落", 0xFFFFD166, 0xFFFF8A3D, 0xFFFF3D6E, 0xFFFF7A45, false),
    MINIMAL("极简", 0xFFFFFFFF, 0xFFC8D0E0, 0xFF8A93B2, 0xFFDCE3F5, false),
    /** 自定义：实际颜色由 Prefs.customColors 提供 */
    CUSTOM("自定义", 0xFF00F5FF, 0xFF7B5CFF, 0xFFFF2ED1, 0xFF39D9FF, false);

    public final String label;
    public final int c0, c1, c2, glow;
    public final boolean rainbow;

    Palette(String label, int a, int b, int c, int g, boolean rainbow) {
        this.label = label;
        this.c0 = a;
        this.c1 = b;
        this.c2 = c;
        this.glow = g;
        this.rainbow = rainbow;
    }

    public static Palette byIndex(int i) {
        Palette[] v = values();
        if (i < 0 || i >= v.length) i = 0;
        return v[i];
    }

    public static int count() {
        return values().length;
    }
}