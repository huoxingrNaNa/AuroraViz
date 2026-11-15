package com.operit.audioviz;

/** 六种可视化样式：纵向柱状 / 纵向示波 / 环形频谱 / 径向波形 / 粒子云 / 极坐标圆盘 */
public enum Style {
    COLUMN("柱状", false),
    SCOPE("示波", false),
    RING("环形", true),
    RADIAL("径向", true),
    PARTICLES("粒子", true),
    DISC("圆盘", true);

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