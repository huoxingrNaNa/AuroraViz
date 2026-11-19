package com.operit.audioviz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

/**
 * 只做一件事：把"歌名 + 实时分析数据"发成一条**纯文本常驻通知**。
 *
 * 刻意不做的事：
 * - 不创建 MediaSession（不抢"当前媒体"的位置，酷狗的媒体卡片照常存在）
 * - 不放专辑封面、不放播放按钮、不注册任何转发回调
 * - 不做悬浮窗、不做拖动
 *
 * 通知 ID 固定 2001，因为系统设置项
 * `settings get system fluid_seeding_notification` 里登记的就是这个 ID：
 *   {"pkg":"com.operit.audioviz","serviceId":"<id>","notificationIds":[2001]}
 * 那条注册表是 ColorOS 判定"哪条通知算流体云条目"的凭据。
 */
public final class TextCard {

    private static final String TAG = "AuroraViz";
    // 注意：channel 的 importance 一旦创建就不能被代码修改，
// 所以这里必须用新 ID，否则会沿用旧的 DEFAULT，永远不会浮成卡片。
    private static final String CH_ID = "aurora_card_v2";
    private static final int NOTIF_ID = 2001;
    private static final long TICK_MS = 1000L;

    private static final TextCard INSTANCE = new TextCard();

    public static TextCard get() {
        return INSTANCE;
    }

    private Context ctx;
    private Handler handler;
    private boolean running;
    private boolean posted;
    private String lastLine = "";

    private TextCard() {
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // 通知使用权若没真正连上，主动请求重绑（限频 8s，内部判断）
            try {
                MediaWatcher.get().ensureConnected(ctx);
            } catch (Throwable ignored) {
            }
            try {
                post();
            } catch (Throwable t) {
                Log.i(TAG, "tc tick err " + t);
            }
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    public void start(Context c) {
        if (running) return;
        ctx = c.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        try {
            ensureChannel();
            running = true;
            post();
            handler.postDelayed(tick, TICK_MS);
            Log.i(TAG, "tc start");
        } catch (Throwable t) {
            Log.i(TAG, "tc start fail " + t);
            stop();
        }
    }

    public void stop() {
        running = false;
        posted = false;
        if (handler != null) handler.removeCallbacks(tick);
        if (ctx != null) {
            Object o = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (o instanceof NotificationManager) {
                try {
                    ((NotificationManager) o).cancel(NOTIF_ID);
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i(TAG, "tc stop");
    }

    public boolean isRunning() {
        return running;
    }

    /** 立即用当前设置重建一次通知（不改 running，不影响监听） */
    public void refresh() {
        if (ctx == null) return;
        try {
            post();
        } catch (Throwable t) {
            Log.i(TAG, "tc refresh err " + t);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Object o = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!(o instanceof NotificationManager)) return;
        // HIGH：ColorOS 会把它渲染成浮在应用之上的大卡片（就是"欢太工具箱"那种形态）
        NotificationChannel ch = new NotificationChannel(CH_ID, "实时分析",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("在播放音乐时显示歌名与实时音频分析");
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) o).createNotificationChannel(ch);
    }

    /** 全角/宽字符按 2 计宽，半角按 1 计 */
    private static int chW(char c) {
        return (c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6))) ? 2 : 1;
    }

    /** 中间省略：保留首尾，中间用 … 代替，总宽度不超过 maxW */
    private static String midEllipsis(String s, int maxW) {
        if (s == null || s.length() == 0) return s;
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += chW(s.charAt(i));
        if (w <= maxW) return s;
        int budget = maxW - 1;              // 留 1 给省略号
        int headBudget = budget / 2;
        int tailBudget = budget - headBudget;
        int i = 0, wa = 0;
        while (i < s.length() && wa + chW(s.charAt(i)) <= headBudget) {
            wa += chW(s.charAt(i));
            i++;
        }
        int j = s.length(), wb = 0;
        while (j > i && wb + chW(s.charAt(j - 1)) <= tailBudget) {
            wb += chW(s.charAt(j - 1));
            j--;
        }
        if (i >= j) return s.substring(0, Math.min(i, s.length())) + "…";
        // 首尾都不要落在空格上，避免出现"better … e eyes"这种空隙
        while (i > 0 && s.charAt(i - 1) == ' ') i--;
        while (j < s.length() && s.charAt(j) == ' ') j++;
        return s.substring(0, i) + "…" + s.substring(j);
    }

    private void post() {
        if (ctx == null) return;
        // 刷视频（抖音/快手/B站等）时自动撤掉卡片，避免跟着视频标题乱跳
        if (Prefs.hideOnVideo(ctx) && MediaWatcher.get().isVideoSession()) {
            if (posted) {
                try {
                    Object o0 = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (o0 instanceof NotificationManager) {
                        ((NotificationManager) o0).cancel(NOTIF_ID);
                    }
                } catch (Throwable ignored) {
                }
                posted = false;
                Log.i(TAG, "tc hide (video)");
            }
            return;
        }
        Object o = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!(o instanceof NotificationManager)) return;
        NotificationManager nm = (NotificationManager) o;

        AudioAnalysis a = AudioEngine.get().analysis();
        float db = a == null ? -120f : a.rmsDb();
        float bpm = a == null ? 0f : a.bpm();
        String note = a == null ? "" : a.noteName();

        MediaWatcher mw = MediaWatcher.get();
        String song = mw.song();
        String appLabel = mw.appLabel();

        String title;
        if (song != null && song.length() > 0) {
            title = song;
        } else if (appLabel != null && appLabel.length() > 0) {
            title = "正在播放 · " + appLabel;
        } else {
            title = "实时分析";
        }
        // 胶囊（收起态）显示短名，展开态显示完整名：
        //   contentTitle       = 完整歌名（展开卡 / 通知栏）
        //   shortCriticalText  = 中间省略后的短名（胶囊）
        String fullTitle = title;

        StringBuilder sb = new StringBuilder();
        if (bpm > 0f) sb.append(String.format(Locale.US, "%.0f BPM", bpm));
        if (db > -119f) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "%.1f dB", db));
        }
        if (note != null && note.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(note);
        }
        if (sb.length() == 0) sb.append("等待声音");
        String text = sb.toString();

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_ID)
                : new Notification.Builder(ctx);
        // 图标优先用"正在播放的那个 App"自己的通知图标（如酷狗），取不到再用内置波形
        boolean iconSet = false;
        try {
            android.graphics.drawable.Icon playingIc = mw.playingIcon();
            if (playingIc != null) {
                b.setSmallIcon(playingIc);
                iconSet = true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "tc icon fail " + t);
        }
        if (!iconSet) {
            b.setSmallIcon(R.drawable.ic_stat_viz);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 注意：官方文档明确要求"不得 setColorized(true)"，之前正是这一条让
                // hasPromotableCharacteristics() 返回 false
                .setColor(0xFF4FD1FF);

        // 样式用 BigTextStyle（官方允许的四种之一）
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                // 展开态把完整歌名放在第一行，胶囊上则是截断后的短标题
                String big = fullTitle.equals(title) ? text : (fullTitle + "\n" + text);
                b.setStyle(new Notification.BigTextStyle().bigText(big));
            } catch (Throwable t) {
                Log.i(TAG, "tc style err " + t);
            }
        }

        // 状态栏条状标签上的短文本（≤7 字符），不设的话只显示标题
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                // 按用户选择的模式决定胶囊上显示什么
                int liveMode = Prefs.liveMode(ctx);
                String shortText;
                switch (liveMode) {
                    case 1:
                        shortText = (note != null && note.length() > 0) ? note : midEllipsis(fullTitle, 9);
                        break;
                    case 2:
                        shortText = (db > -119f)
                                ? String.format(Locale.US, "%.0f dB", db) : midEllipsis(fullTitle, 9);
                        break;
                    case 3:
                        // 歌名模式：胶囊显示简略（中间省略）或详细（完整，系统自己截尾）
                        // 完整歌名始终由 contentTitle 承载，展开卡不受这里影响
                        shortText = Prefs.nameFull(ctx) ? fullTitle : midEllipsis(fullTitle, 9);
                        break;
                    default:
                        shortText = bpm > 0f
                                ? String.format(Locale.US, "%.0f BPM", bpm) : midEllipsis(fullTitle, 9);
                        break;
                }
                if (shortText != null && shortText.length() > 0) {
                    b.setShortCriticalText(shortText);
                }
            } catch (Throwable t) {
                Log.i(TAG, "tc short err " + t);
            }
        }

        // "关闭卡片"按钮：只撤掉这张卡片，监听继续运行
        Intent stopIntent = new Intent(ctx, OverlayService.class);
        stopIntent.setAction(OverlayService.ACTION_HIDE_CARD);
        PendingIntent stopPi = PendingIntent.getService(ctx, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_launcher),
                "关闭卡片", stopPi).build());

        Notification n = b.build();
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        // 官方要求：通过 EXTRA_REQUEST_PROMOTED_ONGOING 请求升级为实时动态
        // 关掉"状态栏实时卡片"开关时不再请求升格 → 通知自动退出胶囊，
        // 但仍留在通知栏，且完全不影响监听/前台服务
        if (Prefs.liveCard(ctx)) {
            n.extras.putBoolean("android.requestPromotedOngoing", true);
        }
        nm.notify(NOTIF_ID, n);
        posted = true;

        String line = fullTitle + " | " + text;
        if (!line.equals(lastLine)) {
            lastLine = line;
            Log.i(TAG, "tc post " + line);
        }
    }
}