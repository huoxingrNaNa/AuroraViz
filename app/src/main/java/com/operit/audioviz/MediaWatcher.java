package com.operit.audioviz;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;

/**
 * 读取"当前哪个应用在播放音频、放的是什么"。
 *
 * 走 MediaSessionManager.getActiveSessions()，调用方必须是一个已被授予
 * "通知使用权"的 NotificationListenerService，因此 MediaWatcherService
 * 必须先被系统绑定（可用 cmd notification disallow/allow_listener 强制重绑）。
 *
 * 除了读取，还持有真实播放器的 MediaController，供 MediaCard 转发控制指令，
 * 这样顶部卡片上的播放/暂停、上一首/下一首是可用的。
 */
public final class MediaWatcher {

    private static final String TAG = "AuroraViz";
    private static final long POLL_MS = 900L;
    private static final int ART_MAX = 1024;

    private static final MediaWatcher INSTANCE = new MediaWatcher();

    public static MediaWatcher get() {
        return INSTANCE;
    }

    /**
     * 通知监听是否真的连上了系统。
     * 注意：系统设置里有"通知使用权"授权，不代表监听服务已经连上（可能是僵尸绑定），
     * 只有连上了才能读取媒体会话、才有机会上岛。
     */
    public static boolean listenerConnected() {
        return INSTANCE.connected;
    }

    private Context ctx;
    private MediaSessionManager msm;
    private Handler handler;
    /** 通知监听是否已连上系统（由 attach/detach 维护） */
    private volatile boolean connected;
    private boolean polling;

    private String pkg = "";
    private String label = "";
    private String song = "";
    private String artist = "";
    private int state = PlaybackState.STATE_NONE;
    private long positionMs;
    private long durationMs;
    private Bitmap art;
    private MediaController controller;
    private NotificationListenerService nls;
    private android.graphics.drawable.Icon playingIcon;
    /** 上一次成功取到图标的包名，用于判断是否该清掉旧图标 */
    private String lastIconPkg = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            try {
                scan();
            } catch (Throwable t) {
                Log.i(TAG, "mw err " + t);
            }
            if (handler != null) handler.postDelayed(this, POLL_MS);
        }
    };

    /** 应用启动图标缓存（同一包只加载一次） */
    private final java.util.HashMap<String, android.graphics.drawable.Icon> appIconCache = new java.util.HashMap<>();

    /**
     * 应用的启动图标（渲染成 96px 位图）。用于播放器不发通知、借不到小图标时兜底，
     * 至少显示的仍是这个 App 自己的图标。
     */
    private android.graphics.drawable.Icon appIcon(String pkg) {
        if (pkg == null) return null;
        if (appIconCache.containsKey(pkg)) return appIconCache.get(pkg);
        android.graphics.drawable.Icon out = null;
        try {
            android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d != null) {
                int s = 96;
                Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bm);
                d.setBounds(0, 0, s, s);
                d.draw(cv);
                out = android.graphics.drawable.Icon.createWithBitmap(bm);
            }
        } catch (Throwable t) {
            Log.i(TAG, "mw appicon fail " + pkg + " " + t);
        }
        appIconCache.put(pkg, out);
        return out;
    }

    /**
     * 把"别的应用的通知小图标"在本进程里绘制成位图再带走。
     * 直接传 ResourceIcon 会让系统在渲染我们的通知时去加载外部包资源，
     * 实测会失败或显示成另一个应用的图标（酷狗在播却显示网易云图标）。
     */
    private android.graphics.drawable.Icon toBitmapIcon(String pkg, android.graphics.drawable.Icon src) {
        try {
            android.graphics.drawable.Drawable d = null;
            if (src.getType() == android.graphics.drawable.Icon.TYPE_RESOURCE) {
                try {
                    String rp = src.getResPackage();
                    Context pc = (rp != null && !rp.equals(ctx.getPackageName()))
                            ? ctx.createPackageContext(rp, 0) : ctx;
                    d = pc.getResources().getDrawable(src.getResId(), null);
                } catch (Throwable ignored) {
                }
            }
            if (d == null) d = src.loadDrawable(ctx);
            if (d == null) return null;
            int s = 96;
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, s, s);
            d.draw(cv);
            return android.graphics.drawable.Icon.createWithBitmap(bm);
        } catch (Throwable t) {
            Log.i(TAG, "mw icon render fail " + pkg + " " + t);
            return null;
        }
    }

    public void attach(NotificationListenerService svc) {
        connected = true;
        rebindTries = 0;
        if (polling) return;
        nls = svc;
        ctx = svc.getApplicationContext();
        Object o = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (!(o instanceof MediaSessionManager)) {
            Log.i(TAG, "mw no MediaSessionManager");
            return;
        }
        msm = (MediaSessionManager) o;
        handler = new Handler(Looper.getMainLooper());
        polling = true;
        handler.postDelayed(poll, 300L);
        Log.i(TAG, "mw attach");
    }

    public void detach() {
        connected = false;
        polling = false;
        if (handler != null) {
            try {
                handler.removeCallbacks(poll);
            } catch (Throwable ignored) {
            }
        }
        handler = null;
        msm = null;
        controller = null;
        // 服务销毁后不再持有它的引用，避免静态单例把已销毁的 Service 留住
        nls = null;
        ctx = null;
        playingIcon = null;
        lastIconPkg = "";
        clear();
        Log.i(TAG, "mw detach");
    }

    public boolean isReady() {
        return msm != null;
    }

    private long lastRebind;
    /** 连续失败的软重绑次数，超过阈值后升级为 Shizuku 强制解绑重绑 */
    private int rebindTries;

    /**
     * 若通知使用权还没真正连上（重装 APK 或重启后 ColorOS 常出现
     * "设置里显示已授权、但 onListenerConnected 不回调"的僵尸绑定），主动救一次。
     *
     * 前两次用官方 NotificationListenerService.requestRebind()（不需要额外权限，
     * 但 ColorOS 上经常被直接忽略）；仍不成功且有 Shizuku 时，改用
     * `cmd notification disallow_listener/allow_listener` 强制重绑——实测这条必成。
     */
    public void ensureConnected(Context c) {
        if (msm != null || c == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRebind < 8000L) return;
        lastRebind = now;
        rebindTries++;
        String comp = c.getPackageName() + "/" + MediaWatcherService.class.getName();
        if (rebindTries > 1 && ShizukuHelper.hasPermission()) {
            Log.i(TAG, "mw force rebind try=" + rebindTries);
            new Thread(() -> {
                String out = ShizukuHelper.shell(
                        "cmd notification disallow_listener " + comp
                                + "; sleep 1; cmd notification allow_listener " + comp);
                Log.i(TAG, "mw force rebind out=" + out.replace('\n', ' '));
            }, "mw-rebind").start();
            // 强制重绑本身要 1 秒 + 系统重连要几秒，放宽到 20 秒再试下一轮
            lastRebind = now + 12000L;
            return;
        }
        try {
            // 先解绑（清掉系统侧的僵尸 binder），再请求重绑
            if (nls != null) {
                try {
                    nls.requestUnbind();
                } catch (Throwable ignored) {
                }
            }
            NotificationListenerService.requestRebind(
                    new android.content.ComponentName(c, MediaWatcherService.class));
            Log.i(TAG, "mw rebind req try=" + rebindTries);
        } catch (Throwable t) {
            Log.i(TAG, "mw rebind fail " + t);
        }
    }

    public String song() {
        return song;
    }

    public String artist() {
        return artist;
    }

    public String appLabel() {
        return label;
    }

    public String appPackage() {
        return pkg;
    }

    public int playbackState() {
        return state;
    }

    public long positionMs() {
        return positionMs;
    }

    public long durationMs() {
        return durationMs;
    }

    public Bitmap albumArt() {
        return art;
    }

    public android.graphics.drawable.Icon playingIcon() {
        return playingIcon;
    }

    public MediaController controller() {
        return controller;
    }

    private void clear() {
        pkg = "";
        label = "";
        song = "";
        artist = "";
        state = PlaybackState.STATE_NONE;
        videoSession = false;
        art = null;
    }

    /** 当前正在播放的是否"视频类"应用（短视频/长视频/直播） */
    private boolean videoSession = false;

    /**
     * 视频场景的宽限截止时间（elapsedRealtime）。
     * 切集、缓冲、暂停的一瞬间 PlaybackState 会短暂变成非播放态，
     * 没有宽限就会表现成"卡片偶尔又冒出来一下"。
     */
    private volatile long videoUntil = 0L;

    public boolean isVideoSession() {
        return videoSession
                || android.os.SystemClock.elapsedRealtime() < videoUntil;
    }

    /**
     * 判定为视频场景时，把宽限延长到 15 秒；
     * 并从"非视频"跃迁到"视频"的瞬间立刻撤掉卡片，避免最长 1.2 秒的闪现。
     */
    private void markVideoGrace() {
        boolean was = isVideoSession();
        videoUntil = android.os.SystemClock.elapsedRealtime() + VIDEO_GRACE_MS;
        if (!was) {
            try {
                TextCard.get().refresh();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 视频场景宽限期：覆盖切集、缓冲、短暂暂停 */
    private static final long VIDEO_GRACE_MS = 15000L;

    /** 合并三路信号：会话包名 / 分身通知 / 前台应用，并统一处理宽限 */
    private void applyVideoFlag(boolean base) {
        boolean v = base || isVideoPkg(fgPkg);
        videoSession = v;
        if (v) markVideoGrace();
    }

    // ---- 前台应用兜底判定 ----
    // 分身应用（user999）的媒体会话拿不到，它的媒体通知也未必带 mediaSession token，
    // 于是靠"前台是不是视频类应用"兜底：用 Shizuku 读 dumpsys，2 秒限频、异步执行。
    private volatile String fgPkg = "";
    private volatile long fgAt = 0L;
    private volatile boolean fgBusy = false;
    private static final long FG_MIN_MS = 2000L;
    private final java.util.concurrent.ExecutorService fgExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // 从 "topResumedActivity=ActivityRecord{... u999 tv.danmaku.bili/com.xxx.Activity t1871}" 里取包名
    private static final java.util.regex.Pattern ACT_PKG =
            java.util.regex.Pattern.compile("\\s([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+");

    private void refreshForeground() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (fgBusy || now - fgAt < FG_MIN_MS) return;
        fgAt = now;
        if (!ShizukuHelper.hasPermission()) return;
        fgBusy = true;
        fgExec.execute(() -> {
            try {
                String out = ShizukuHelper.shell(
                        "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'");
                java.util.regex.Matcher m = ACT_PKG.matcher(out == null ? "" : out);
                if (m.find()) fgPkg = m.group(1);
            } catch (Throwable ignored) {
            } finally {
                fgBusy = false;
            }
        });
    }

    /** 视频类应用包名（短视频、长视频、直播）。命中这些就属于"刷视频"场景 */
    private static final String[] VIDEO_PKGS = {
            "com.ss.android.ugc.aweme",      // 抖音
            "com.ss.android.ugc.aweme.lite", // 抖音极速版
            "com.ss.android.ugc.live",       // 抖音直播
            "com.ss.android.article.video",  // 西瓜视频
            "com.smile.gifmaker",            // 快手
            "com.kuaishou.nebula",           // 快手极速版
            "com.tencent.weishi",            // 微视
            "tv.danmaku.bili",               // 哔哩哔哩
            "com.bilibili.app.in",           // 哔哩哔哩国际版
            "com.bilibili.livehime",         // 哔哩哔哩直播姬
            "com.xingin.xhs",                // 小红书
            "com.tencent.qqlive",            // 腾讯视频
            "com.qiyi.video",                // 爱奇艺
            "com.youku.phone",               // 优酷
            "com.hunantv.imgo.activity",     // 芒果TV
            "com.google.android.youtube",    // YouTube
            "com.sina.weibo",                // 微博
            "com.tencent.mm"                 // 微信（视频号/朋友圈视频）
    };

    private static boolean isVideoPkg(String p) {
        if (p == null) return false;
        for (String v : VIDEO_PKGS) {
            if (p.equals(v) || p.startsWith(v + ".")) return true;
        }
        return false;
    }

    /**
     * 分身应用（ColorOS 的 MultiApp，运行在 user999）的媒体会话拿不到，
     * 但它的通知能看到。这里从通知里的 mediaSession token 反查播放状态。
     */
    private boolean videoPlayingViaNotification() {
        if (nls == null || ctx == null) return false;
        try {
            android.service.notification.StatusBarNotification[] arr = nls.getActiveNotifications();
            if (arr == null) return false;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) continue;
                String p = arr[i].getPackageName();
                if (!isVideoPkg(p)) continue;
                android.app.Notification an = arr[i].getNotification();
                if (an == null || an.extras == null) continue;
                Object tk = an.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION);
                if (!(tk instanceof android.media.session.MediaSession.Token)) continue;
                try {
                    MediaController mc = new MediaController(ctx,
                            (android.media.session.MediaSession.Token) tk);
                    PlaybackState st = mc.getPlaybackState();
                    if (st != null) {
                        int s = st.getState();
                        if (s == PlaybackState.STATE_PLAYING
                                || s == PlaybackState.STATE_BUFFERING) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "mw video(notif) fail " + t);
        }
        return false;
    }

    private void scan() {
        if (msm == null || ctx == null) return;
        // 分身应用（user999）的媒体会话拿不到，先并行取两路兜底信号：
        // 1) 分身 App 的媒体通知里的 token 是否处于播放态
        // 2) 前台应用是不是视频类（Shizuku 读 dumpsys，异步、2 秒限频）
        boolean videoByNotif = videoPlayingViaNotification();
        refreshForeground();
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(ctx, MediaWatcherService.class));
        } catch (Throwable t) {
            Log.i(TAG, "mw getActiveSessions fail " + t);
            return;
        }
        if (list == null || list.isEmpty()) {
            if (!pkg.isEmpty()) {
                Log.i(TAG, "mw none was=" + pkg);
                controller = null;
                clear();
            }
            // 注意：这条分支以前直接 return，导致"分身视频 App 在播、主用户没有任何会话"
            // 时视频标记永远是 false，卡片就会冒出来
            applyVideoFlag(videoByNotif);
            return;
        }
        String myPkg = ctx.getPackageName();
        MediaController best = null;
        int bestState = PlaybackState.STATE_NONE;
        for (int i = 0; i < list.size(); i++) {
            MediaController c = list.get(i);
            if (c == null) continue;
            String p;
            PlaybackState st;
            try {
                p = c.getPackageName();
                st = c.getPlaybackState();
            } catch (Throwable t) {
                continue;
            }
            if (p == null || p.equals(myPkg)) continue;
            if (st == null) continue;
            int s = st.getState();
            if (s != PlaybackState.STATE_PLAYING
                    && s != PlaybackState.STATE_PAUSED
                    && s != PlaybackState.STATE_BUFFERING) {
                continue;
            }
            if (best == null || (s == PlaybackState.STATE_PLAYING
                    && bestState != PlaybackState.STATE_PLAYING)) {
                best = c;
                bestState = s;
                if (s == PlaybackState.STATE_PLAYING) break;
            }
        }
        if (best == null) {
            if (!pkg.isEmpty()) {
                Log.i(TAG, "mw none was=" + pkg);
                controller = null;
                clear();
            }
            videoSession = videoByNotif;   // clear() 会把标记归零，这里重设
            applyVideoFlag(videoByNotif);
            return;
        }
        String np;
        try {
            np = best.getPackageName();
        } catch (Throwable t) {
            return;
        }
        MediaMetadata md = null;
        try {
            md = best.getMetadata();
        } catch (Throwable ignored) {
        }
        String ns2 = md == null ? "" : safe(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String na2 = md == null ? "" : safe(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (na2.isEmpty() && md != null) {
            na2 = safe(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        }
        boolean changed = !np.equals(pkg) || !ns2.equals(song) || bestState != state;
        controller = best;
        // 标记当前会话是否属于"视频类"应用（刷短视频/看视频）
        applyVideoFlag(isVideoPkg(np) || videoByNotif);
        // 取"正在播放的那个 App"的图标，用作状态栏卡片/胶囊图标。
        // 顺序：应用启动图标 → 通知小图标 → 内置波形图标。
        // 之所以把"启动图标"放第一位：启动图标只取决于包名，任何播放器都必然有，
        // 行为一致、可预测；而"通知小图标"要看播放器肯不肯发通知（酷狗概念版就
        // 播放时不发）、用什么图标（有的塞纯白占位图），各 App 表现不同，不能当主路径。
        // 所有图标都在本进程画成位图（BitmapIcon）再带出去，不把别家的
        // ResourceIcon 直接塞进我们的通知——那会让系统跨包加载资源，可能错位。
        // 换 App 时必须重算，绝不能残留上一个 App 的图标（这就是"酷狗在播却显示
        // 网易云图标"的原因：旧代码从不清理 playingIcon）。
        try {
            if (nls != null && np != null && !np.equals(lastIconPkg)) {
                android.graphics.drawable.Icon ic = appIcon(np);
                String src = ic != null ? "launcher" : "own";
                if (ic == null) {
                    try {
                        android.service.notification.StatusBarNotification[] arr = nls.getActiveNotifications();
                        if (arr != null) {
                            for (int i = 0; i < arr.length; i++) {
                                if (arr[i] == null || !np.equals(arr[i].getPackageName())) continue;
                                android.app.Notification an = arr[i].getNotification();
                                android.graphics.drawable.Icon si = an == null ? null : an.getSmallIcon();
                                if (si != null) {
                                    ic = toBitmapIcon(np, si);
                                    if (ic != null) src = "notif";
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                playingIcon = ic;
                lastIconPkg = np;
                Log.i(TAG, "mw icon " + np + " -> " + src);
            }
        } catch (Throwable ignored) {
        }
        try {
            PlaybackState bst = best.getPlaybackState();
            positionMs = bst == null ? 0L : Math.max(0L, bst.getPosition());
        } catch (Throwable ignored) {
        }
        if (md != null) {
            try {
                long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (d > 0L) durationMs = d;
            } catch (Throwable ignored) {
            }
        }
        if (changed) {
            label = labelOf(np);
            if (!ns2.equals(song)) {
                art = loadArt(md);
            }
            pkg = np;
            song = ns2;
            artist = na2;
            state = bestState;
            Log.i(TAG, "mw now pkg=" + pkg + " label=" + label
                    + " state=" + state + " song=" + song
                    + " artist=" + artist + " art=" + (art != null));
        } else {
            pkg = np;
            song = ns2;
            artist = na2;
            state = bestState;
        }
    }

    /** 取专辑图，超过 1024 就先缩小，避免 binder 传输过大 */
    private Bitmap loadArt(MediaMetadata md) {
        if (md == null) return null;
        Bitmap b = null;
        try {
            b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        } catch (Throwable t) {
            Log.i(TAG, "mw art fail " + t);
            return null;
        }
        if (b == null) return null;
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            if (w <= 0 || h <= 0) return null;
            if (w > ART_MAX || h > ART_MAX) {
                float s = ART_MAX / (float) Math.max(w, h);
                Bitmap nb = Bitmap.createScaledBitmap(b,
                        Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
                if (nb != b) {
                    try {
                        b.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                b = nb;
            }
            Log.i(TAG, "mw art " + b.getWidth() + "x" + b.getHeight());
        } catch (Throwable t) {
            Log.i(TAG, "mw art scale fail " + t);
        }
        return b;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 应用显示名。三级回退：ApplicationInfo.label → 启动 Activity 的 label → 包名。
     * 有些应用（例如酷狗 lite）把名字挂在 activity 上，只读 ApplicationInfo 会退回包名。
     */
    private String labelOf(String p) {
        if (ctx == null) return p;
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            CharSequence cs = ai.loadLabel(pm);
            if (cs != null) {
                String s = cs.toString().trim();
                if (s.length() > 0 && !s.equals(p)) return s;
            }
        } catch (Throwable t) {
            Log.i(TAG, "mw label app fail " + t);
        }
        try {
            Intent it = pm.getLaunchIntentForPackage(p);
            if (it != null) {
                ResolveInfo ri = pm.resolveActivity(it, 0);
                if (ri != null) {
                    CharSequence cs = ri.loadLabel(pm);
                    if (cs != null) {
                        String s = cs.toString().trim();
                        if (s.length() > 0 && !s.equals(p)) return s;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "mw label act fail " + t);
        }
        return p;
    }
}