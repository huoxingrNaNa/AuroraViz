package com.operit.audioviz;

/** 八种可视化样式：柱状 / 示波 / 环形 / 径向 / 粒子 / 圆盘 / 瀑布 / 表盘 */
public enum Style {
    COLUMN("柱状", false),
    SCOPE("示波", false),
    RING("环形", true),
    RADIAL("径向", true),
    PARTICLES("粒子", true),
    DISC("圆盘", true),
    SPECTROGRAM("瀑布", false),
    VU("表盘", false);

    public final String label;
    /** 是否为环形布局 */
    public final boolean ring;

    Style(String label, boolean ring) {
        this.label = label;
        this.ring = ring;
    }

    public static Style byIndex(int i) {
        Style[] v = values();
        if (i < 0 || i >= v.length) i = 0;
        return v[i];
    }

    public static int count() {
        return values().length;
    }
}