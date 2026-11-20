package com.operit.audioviz;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * 向系统申请窗口刷新率。
 *
 * 背景：应用内的"刷新率"设置只限制了自身重绘节奏（VisualizerView 的
 * postInvalidateDelayed），并不会让系统把这块屏幕跑在高刷上。很多 ROM
 * （例如华为）默认把普通应用放在 60Hz，于是"自动"也永远是 60。
 *
 * 这里用官方文档里的 WindowManager.LayoutParams.preferredRefreshRate
 * 提示系统：这是"偏好值"，系统可以忽略，但多数设备会据此切模式。
 * 自动（0）时取屏幕支持的最高刷新率，避免手动写死 120 在 90Hz 屏上无效。
 */
final class Refresh {

    private static final String TAG = "AuroraViz";
    private static final int CAP_HZ = 240;   // 仅防异常值；"自动"就是屏幕最高

    private Refresh() {
    }

    /** 屏幕支持的最高刷新率（取不到返回 60） */
    static int maxHz(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return 60;
            Display.Mode[] modes = d.getSupportedModes();
            if (modes == null || modes.length == 0) return Math.round(d.getRefreshRate());
            float best = 0f;
            for (Display.Mode m : modes) {
                if (m.getRefreshRate() > best) best = m.getRefreshRate();
            }
            return best <= 0f ? 60 : Math.min(CAP_HZ, Math.round(best));
        } catch (Throwable t) {
            return 60;
        }
    }

    /**
     * 目标刷新率：0（自动）→ 屏幕最高；否则用用户选的档位（30/60/120）。
     * 选中值高于屏幕能力时回落到屏幕最高，避免无效值。
     */
    static int targetHz(Context c) {
        int want = Prefs.vizHzValue(Prefs.vizHz(c));
        int max = maxHz(c);
        if (want <= 0) return max;
        return Math.min(want, Math.max(60, max));
    }

    /** 给 Activity 窗口申请刷新率（在 setContentView 之后调用即可） */
    static void apply(Activity a) {
        try {
            Window w = a.getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            int hz = targetHz(a);
            if (lp.preferredRefreshRate == hz) return;
            lp.preferredRefreshRate = hz;
            w.setAttributes(lp);
            Log.i(TAG, "viz hz apply window=" + hz + " max=" + maxHz(a));
        } catch (Throwable t) {
            Log.i(TAG, "viz hz apply fail " + t);
        }
    }

    /** 给悬浮窗（WindowManager 直接 addView）申请刷新率 */
    static void apply(WindowManager.LayoutParams lp, Context c) {
        try {
            int hz = targetHz(c);
            lp.preferredRefreshRate = hz;
            Log.i(TAG, "viz hz apply overlay=" + hz + " max=" + maxHz(c));
        } catch (Throwable t) {
            Log.i(TAG, "viz hz apply overlay fail " + t);
        }
    }
}
