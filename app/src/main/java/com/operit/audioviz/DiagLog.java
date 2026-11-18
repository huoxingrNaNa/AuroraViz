package com.operit.audioviz;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用内诊断日志：常开、环形缓冲、可导出。
 *
 * 与 logcat 的区别：logcat 只有连电脑或 adb 才能看，对用户等于不存在；
 * 这个缓冲区让用户自己也能把最近几分钟的记录导出来、发出去。
 * 每秒约 2 条，容量 600 条 ≈ 5 分钟。
 */
public final class DiagLog {

    private static final int CAP = 600;
    private static final String[] RING = new String[CAP];
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat HUMAN =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static int pos = 0;
    private static int size = 0;

    private DiagLog() {
    }

    public static synchronized void add(String msg) {
        if (msg == null) return;
        RING[pos] = TS.format(new Date()) + " " + msg;
        pos = (pos + 1) % CAP;
        if (size < CAP) size++;
    }

    public static synchronized int count() {
        return size;
    }

    public static synchronized List<String> snapshot() {
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(RING[(pos - size + i + CAP * 2) % CAP]);
        }
        return out;
    }

    /** 组装可读文本：环境头 + 当前读数 + 环形缓冲 */
    public static String buildReport(Context c) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("AuroraViz 诊断日志\n");
        sb.append("导出时间: ").append(HUMAN.format(new Date())).append('\n');
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            sb.append("版本: ").append(pi.versionName).append(" (").append(vc).append(")\n");
        } catch (Exception ignored) {
        }
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            AudioAnalysis a = AudioEngine.get().analysis();
            sb.append("当前读数: db=").append(String.format(Locale.US, "%.1f", a.shortDb()))
                    .append(" peak=").append(String.format(Locale.US, "%.1f", a.peakDb()))
                    .append(" lufs=").append(String.format(Locale.US, "%.1f", a.lufsApprox()))
                    .append(" bpm=").append(String.format(Locale.US, "%.0f", a.bpm()))
                    .append(" alt=").append(String.format(Locale.US, "%.0f", a.bpmAlt()))
                    .append(" note=").append(a.hasPitch() ? a.noteName() : "")
                    .append(" clip=").append(a.clipWarn() ? "YES" : "no")
                    .append('\n');
        } catch (Throwable ignored) {
        }
        sb.append("\n--- 最近 ").append(count()).append(" 条 ---\n");
        for (String line : snapshot()) sb.append(line).append('\n');
        return sb.toString();
    }

    /** 写到 cache/diag/，只保留最近 5 份。失败返回 null */
    public static File writeReport(Context c) {
        try {
            File dir = new File(c.getCacheDir(), "diag");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File[] old = dir.listFiles();
            if (old != null && old.length >= 5) {
                Arrays.sort(old, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i <= old.length - 5; i++) old[i].delete();
            }
            File f = new File(dir, "aurora-diag-" + System.currentTimeMillis() + ".txt");
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(buildReport(c));
            } finally {
                w.close();
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
