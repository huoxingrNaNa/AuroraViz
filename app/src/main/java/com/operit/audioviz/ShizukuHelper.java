package com.operit.audioviz;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 辅助：一键授予悬浮窗/录音/通知权限（省去手动点设置），
 * 通过 Shizuku 的 shell 身份执行 appops / pm 命令。
 */
public final class ShizukuHelper {

    private static Method newProcess;

    private ShizukuHelper() {
    }

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder() && Shizuku.getVersion() >= 11;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return isAvailable()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldShowRequestPermissionRationale() {
        try {
            return isAvailable() && Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
        }
    }

    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.addRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.removeRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    /** 以 shell 身份执行命令，返回输出（失败返回 ERR: 开头的信息） */
    public static String shell(String cmd) {
        try {
            if (newProcess == null) {
                Method m = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                newProcess = m;
            }
            // exec 2>&1：把 stderr 也并进 stdout，否则命令失败时我们读不到原因
            Process p = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", "exec 2>&1; " + cmd}, null, null);
            if (p == null) return "ERR:null process";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    /** 本应用的通知监听组件（cmd notification 需要 pkg/class 形式） */
    private static String nlsComponent(Context ctx) {
        return ctx.getPackageName() + "/" + MediaWatcherService.class.getName();
    }

    /** 通知使用权是否已包含本应用（读 Secure 设置，普通应用即可读取） */
    public static boolean hasNotificationListener(Context ctx) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (v == null || v.isEmpty()) return false;
            String me = nlsComponent(ctx);
            for (String s : v.split(":")) {
                if (s.equalsIgnoreCase(me)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 一键授权：悬浮窗 + 录音 + 通知 + 通知使用权（上岛依赖） */
    public static String grantAll(Context ctx) {
        String pkg = ctx.getPackageName();
        StringBuilder log = new StringBuilder();
        log.append(shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow")).append('\n');
        log.append(shell("pm grant " + pkg + " android.permission.RECORD_AUDIO")).append('\n');
        log.append(shell("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS")).append('\n');
        // 通知使用权：先解绑再绑定。这样同时修掉"设置里显示已授权、
        // 但监听服务实际没连上"的僵尸绑定状态
        String c = nlsComponent(ctx);
        log.append(shell("cmd notification disallow_listener " + c
                + "; sleep 1; cmd notification allow_listener " + c)).append('\n');
        // 命令返回不代表真的写进去了，回查系统设置确认（最多等 5 秒）
        String chk = shell("ok=0; for i in 1 2 3 4 5; do "
                + "if settings get secure enabled_notification_listeners | grep -q '" + c + "'; "
                + "then ok=1; break; fi; sleep 1; done; "
                + "if [ \"$ok\" = 1 ]; then echo NLS_OK; "
                + "else echo NLS_FAIL; fi");
        log.append(chk);
        return log.toString().trim();
    }

    /** 撤销悬浮窗权限（调试用） */
    public static String revokeOverlay(Context ctx) {
        return shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
    }
}