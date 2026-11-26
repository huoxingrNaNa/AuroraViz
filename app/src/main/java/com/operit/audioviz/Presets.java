package com.operit.audioviz;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化预设：把「模式 + 配色 + 灵敏度 + 平滑 + 双声道 + 峰值 + 帧率」打成一包存起来，
 * 之后一键切回某个状态。
 *
 * 存储沿用 {@link Prefs} 的同一个 SharedPreferences 文件，用一条 JSON 数组装全部预设，
 * 不引入额外文件也不引入 Gson（项目零第三方依赖）。
 */
public final class Presets {
    private static final String FILE = "aurora_prefs";
    private static final String K_LIST = "viz_presets";
    /** 上限：够用且不会把界面拉成一条无限长的横滑条 */
    public static final int MAX = 20;

    private Presets() {
    }

    /** 一条预设：字段都是"可显示"的最终状态，不含音源（音源属于监听，不属于外观） */
    public static final class Item {
        public String name = "";
        public int style = 0;
        public int palette = 0;
        public int c0 = 0xFF00F5FF, c1 = 0xFF7B5CFF, c2 = 0xFFFF2ED1;
        public float sens = 1.0f;
        public boolean autoSens = true;
        public int smooth = 1;
        public boolean stereoOn = false;
        public int stereoMode = 0;
        public int peak = 0;
        public boolean freqAxis = false;
        public boolean idle = true;
        public int vizHz = 0;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 读写

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        String raw;
        try {
            raw = sp(c).getString(K_LIST, "");
        } catch (Throwable t) {
            return out;
        }
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                // 缺字段一律回落到默认值，老版本存下的 JSON 也能读
                it.name = o.optString("name", "");
                if (it.name.isEmpty()) continue;
                it.style = o.optInt("style", 0);
                it.palette = o.optInt("palette", 0);
                it.c0 = o.optInt("c0", it.c0);
                it.c1 = o.optInt("c1", it.c1);
                it.c2 = o.optInt("c2", it.c2);
                it.sens = (float) o.optDouble("sens", 1.0);
                it.autoSens = o.optBoolean("autoSens", true);
                it.smooth = o.optInt("smooth", 1);
                it.stereoOn = o.optBoolean("stereoOn", false);
                it.stereoMode = o.optInt("stereoMode", 0);
                it.peak = o.optInt("peak", 0);
                it.freqAxis = o.optBoolean("freqAxis", false);
                it.idle = o.optBoolean("idle", true);
                it.vizHz = o.optInt("vizHz", 0);
                out.add(it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", it.name);
                o.put("style", it.style);
                o.put("palette", it.palette);
                o.put("c0", it.c0);
                o.put("c1", it.c1);
                o.put("c2", it.c2);
                o.put("sens", (double) it.sens);
                o.put("autoSens", it.autoSens);
                o.put("smooth", it.smooth);
                o.put("stereoOn", it.stereoOn);
                o.put("stereoMode", it.stereoMode);
                o.put("peak", it.peak);
                o.put("freqAxis", it.freqAxis);
                o.put("idle", it.idle);
                o.put("vizHz", it.vizHz);
            } catch (Throwable ignored) {
            }
            arr.put(o);
        }
        sp(c).edit().putString(K_LIST, arr.toString()).apply();
    }

    // ------------------------------------------------------------ 采集 / 套用

    /** 把当前设置抓成一条预设（名字按"模式·配色"自动生成） */
    public static Item capture(Context c) {
        Item it = new Item();
        it.style = Prefs.style(c);
        it.palette = Prefs.palette(c);
        int[] cc = Prefs.customColors(c);
        it.c0 = cc[0];
        it.c1 = cc[1];
        it.c2 = cc[2];
        it.sens = Prefs.sens(c);
        it.autoSens = Prefs.autoSens(c);
        it.smooth = Prefs.vizSmooth(c);
        it.stereoOn = Prefs.stereoOn(c);
        it.stereoMode = Prefs.stereoMode(c);
        it.peak = Prefs.peakGlow(c);
        it.freqAxis = Prefs.freqAxis(c);
        it.idle = Prefs.idleAnim(c);
        it.vizHz = Prefs.vizHz(c);
        it.name = nameOf(it);
        return it;
    }

    public static String nameOf(Item it) {
        return Style.byIndex(it.style).label + " · " + Palette.byIndex(it.palette).label;
    }

    /** 把预设写进 Prefs（视图与控件由调用方同步） */
    public static void apply(Context c, Item it) {
        Prefs.setStyle(c, it.style);
        Prefs.setPalette(c, it.palette);
        Prefs.setCustomColors(c, it.c0, it.c1, it.c2);
        Prefs.setSens(c, it.sens);
        Prefs.setAutoSens(c, it.autoSens);
        Prefs.setVizSmooth(c, it.smooth);
        Prefs.setStereoOn(c, it.stereoOn);
        Prefs.setStereoMode(c, it.stereoMode);
        Prefs.setPeakGlow(c, it.peak);
        Prefs.setFreqAxis(c, it.freqAxis);
        Prefs.setIdleAnim(c, it.idle);
        Prefs.setVizHz(c, it.vizHz);
    }

    /**
     * 保存一条预设。同名视为"更新"（覆盖），不产生 "柱状 · 霓虹 2" 这种副本。
     *
     * @return 实际保存的名字；已满时返回 null
     */
    public static String save(Context c, Item it) {
        List<Item> items = list(c);
        String name = it.name == null ? "" : it.name.trim();
        if (name.isEmpty()) name = nameOf(it);
        it.name = name;
        int hit = -1;
        for (int i = 0; i < items.size(); i++) {
            if (name.equals(items.get(i).name)) {
                hit = i;
                break;
            }
        }
        if (hit >= 0) {
            items.set(hit, it);
        } else {
            if (items.size() >= MAX) return null;
            items.add(it);
        }
        write(c, items);
        return name;
    }

    public static void delete(Context c, String name) {
        List<Item> items = list(c);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name.equals(name)) {
                items.remove(i);
                break;
            }
        }
        write(c, items);
    }
}
