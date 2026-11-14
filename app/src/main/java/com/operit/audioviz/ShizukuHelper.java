package com.operit.audioviz;

import android.content.Context;
import android.content.pm.PackageManager;

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
            Process p = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", cmd}, null, null);
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

    /** 一键授权：悬浮窗 + 录音 + 通知 */
    public static String grantAll(Context ctx) {
        String pkg = ctx.getPackageName();
        StringBuilder log = new StringBuilder();
        log.append(shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow")).append('\n');
        log.append(shell("pm grant " + pkg + " android.permission.RECORD_AUDIO")).append('\n');
        log.append(shell("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS")).append('\n');
        return log.toString().trim();
    }

    /** 撤销悬浮窗权限（调试用） */
    public static String revokeOverlay(Context ctx) {
        return shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
    }
}