package com.operit.audioviz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 悬浮窗前台服务：承载可视化视图、音频采集与窗口拖拽交互。 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.operit.audioviz.action.START";
    public static final String ACTION_STOP = "com.operit.audioviz.action.STOP";
    /** 只关闭状态栏卡片，不影响监听 */
    public static final String ACTION_HIDE_CARD = "com.operit.audioviz.action.HIDE_CARD";
    public static final String ACTION_STATE = "com.operit.audioviz.action.STATE";
    public static final String ACTION_ERROR = "com.operit.audioviz.action.ERROR";
    public static final String ACTION_APPLY = "com.operit.audioviz.action.APPLY";
    /** 悬浮窗被隐藏（但监听继续） */
    public static final String ACTION_OVERLAY_CHANGED = "com.operit.audioviz.action.OVERLAY_CHANGED";

    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SHOW_OVERLAY = "show_overlay";

    private static final String CHANNEL_ID = "aurora_overlay";
    private static final int NOTIF_ID = 1001;

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private FrameLayout overlayView;
    private VisualizerView viz;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaProjection.Callback projectionCallback;

    private float downRawX, downRawY;
    private int startX, startY;
    private long lastTapAt = 0L;
    private boolean dragMoved = false;
    private float pinchStartDist = 0f;
    private int pinchStartW = 0, pinchStartH = 0;
    private final Runnable longPressHide = () -> {
        if (!dragMoved) hideOverlayKeepRunning();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    /** 双声道只对柱状/示波生效，其余样式一律单声道 */
    private int curStereoMode() {
        int st = Prefs.style(this);
        boolean ok = (st == Style.COLUMN.ordinal() || st == Style.SCOPE.ordinal())
                && Prefs.stereoOn(this);
        return ok ? Prefs.stereoMode(this) + 1 : 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE_CARD.equals(intent.getAction())) {
            // 只关掉状态栏卡片，监听继续跑
            Prefs.setLiveCard(this, false);
            TextCard.get().stop();
            broadcastState();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_APPLY.equals(intent.getAction())) {
            if (!running) {
                stopSelf();
                return START_NOT_STICKY;
            }
            boolean wantOverlay = intent.getBooleanExtra(EXTRA_SHOW_OVERLAY,
                    Prefs.overlayEnabled(this));
            Prefs.setOverlayEnabled(this, wantOverlay);
            if (wantOverlay && overlayView != null && params != null) {
                params.alpha = Prefs.alpha(this);
                if (viz != null) viz.setDrawBackground(!Prefs.bare(this));
                try {
                    wm.updateViewLayout(overlayView, params);
                } catch (Exception ignored) {
                }
            }
            if (wantOverlay) {
                if (overlayView == null) {
                    showOverlay();
                } else if (viz != null) {
                    viz.setStyle(Style.byIndex(Prefs.style(this)));
                    viz.setPalette(Palette.byIndex(Prefs.palette(this)));
                    viz.setDrawBackground(!Prefs.bare(this));
                    viz.setPeakMode(Prefs.peakGlow(this));
                    viz.setIdleAnim(Prefs.idleAnim(this));
                    viz.setFreqAxis(Prefs.freqAxis(this));
                    viz.setShowModeLabel(Prefs.modeLabel(this));
                    viz.setStereo(curStereoMode());
        viz.setSmoothTau(Prefs.vizSmoothTau(this));
        viz.setRenderHz(Prefs.vizHzValue(Prefs.vizHz(this)));
                    viz.setSmoothTau(Prefs.vizSmoothTau(this));
                    viz.setRenderHz(Prefs.vizHzValue(Prefs.vizHz(this)));
                    // 设置变更时同步向系统申请刷新率
                    if (params != null && overlayView != null) {
                        Refresh.apply(params, this);
                        try {
                            wm.updateViewLayout(overlayView, params);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } else {
                hideOverlay();
            }
            return START_NOT_STICKY;
        }

        int source = intent != null
                ? intent.getIntExtra(EXTRA_SOURCE, Prefs.SOURCE_MIC)
                : Prefs.SOURCE_MIC;

        if (running) {
            // 热切换：已在监听中，先释放旧的采集源（服务/通知/浮窗都保留）
            stopCaptureOnly();
        }
        startForegroundCompat(source);

        boolean ok;
        if (source == Prefs.SOURCE_SYSTEM) {
            ok = startSystemCapture(intent);
        } else {
            ok = AudioEngine.get().startMicrophone(this);
            if (!ok) {
                fail("麦克风启动失败，请检查录音权限是否已被系统或其他应用占用");
                return START_NOT_STICKY;
            }
        }

        if (!ok) return START_NOT_STICKY;

        boolean wantOverlay = intent == null
                || intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, Prefs.overlayEnabled(this));
        Prefs.setOverlayEnabled(this, wantOverlay);
        if (wantOverlay) {
            showOverlay();
        }
        TextCard.get().start(this);
        running = true;
        broadcastState();
        return START_NOT_STICKY;
    }

    /** 隐藏悬浮窗但保持监听运行 */
    /** 松手后离屏幕边不足 24dp 就贴边，省得手动对齐 */
    private void snapToEdges() {
        if (params == null || overlayView == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int m = dp(24);
        int nx = params.x;
        int ny = params.y;
        if (nx < m) nx = 0;
        else if (nx + params.width > sw - m) nx = Math.max(0, sw - params.width);
        if (ny < m) ny = 0;
        else if (ny + params.height > sh - m) ny = Math.max(0, sh - params.height);
        if (nx == params.x && ny == params.y) return;
        params.x = nx;
        params.y = ny;
        try {
            wm.updateViewLayout(overlayView, params);
        } catch (Exception ignored) {
        }
    }

    private void hideOverlayKeepRunning() {
        Prefs.setOverlayEnabled(this, false);
        hideOverlay();
        Intent i = new Intent(ACTION_OVERLAY_CHANGED).setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void hideOverlay() {
        if (overlayView != null && wm != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        overlayView = null;
        viz = null;
    }

    private boolean startSystemCapture(Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        if (intent == null) return false;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null) {
            fail("未获得屏幕录制授权，无法捕获系统音频");
            return false;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            fail("系统不支持音频回放捕获");
            return false;
        }
        try {
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            fail("无法创建音频捕获会话，请重新授权");
            return false;
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> {
                    if (!AudioEngine.get().isRunning()) return;
                    // 用户或其他系统界面结束了投屏授权
                    fail("系统音频捕获授权已结束");
                });
            }
        };
        projection.registerCallback(projectionCallback, main);

        boolean ok = AudioEngine.get().startPlaybackCapture(this, projection);
        if (!ok) {
            fail("系统音频捕获失败：当前应用可能禁止被录制，请改用麦克风模式");
            return false;
        }
        ensureVirtualDisplay();
        scheduleSilenceCheck();
        return true;
    }

    /**
     * 某些 ROM 只有在 MediaProjection 真正在投屏时才输出音频帧，
     * 这里创建一个 2x2 的极小虚拟显示用于保活（不显示给用户）。
     */
    private void ensureVirtualDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (projection == null || virtualDisplay != null) return;
        try {
            int density = getResources().getDisplayMetrics().densityDpi;
            imageReader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay("aurora-audio-capture",
                    2, 2, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, main);
        } catch (Throwable ignored) {
            releaseVirtualDisplay();
        }
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
    }

    /** 系统音频模式启动后一段时间仍无有效声压，提示可能被目标应用禁止捕获 */
    private void scheduleSilenceCheck() {
        main.postDelayed(() -> {
            if (!running) return;
            AudioEngine engine = AudioEngine.get();
            if (engine.silenceMs() > 6000L) {
                if (engine.frames() == 0L) {
                    sendWarn("未收到任何音频帧：该应用禁止被录制（Android 限制），请改用麦克风模式");
                } else {
                    sendWarn("已收到音频帧但内容为静音：请确认被监听的应用正在出声");
                }
            }
        }, 9000L);
    }

    private void sendWarn(String msg) {
        Intent i = new Intent(ACTION_ERROR).setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void fail(String msg) {
        Intent i = new Intent(ACTION_ERROR).setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
        shutdown();
    }

    private void broadcastState() {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("running", running);
        sendBroadcast(i);
    }

    // ------------------------------------------------------------ 前台通知

    private void startForegroundCompat(int source) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("aurora_fg_card", "实时分析卡片",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("把播放信息以实时活动形式显示");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = source == Prefs.SOURCE_SYSTEM ? "正在监听系统音频" : "正在监听麦克风";

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "aurora_fg_card")
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText("监听中，点击进入应用设置")
                // 单色状态栏图标（彩色自适应图标在通知栏里会显示成一块渐变方块）
                .setSmallIcon(R.drawable.ic_stat_viz)
                .setOngoing(true)
                .setContentIntent(pi)
                // 注意：这里不设 setColorized(true)/setColor —— ColorOS 会把 color
                // 直接当成整条通知的背景色，导致通知栏发蓝
                .setStyle(new Notification.BigTextStyle().bigText("监听中，点击进入应用设置"))
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build());

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = source == Prefs.SOURCE_SYSTEM
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ------------------------------------------------------------ 悬浮窗

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new FrameLayout(this);
        viz = new VisualizerView(this);
        viz.setDrawBackground(!Prefs.bare(this));
        viz.setShowModeLabel(Prefs.modeLabel(this));
        viz.setPeakMode(Prefs.peakGlow(this));
        viz.setIdleAnim(Prefs.idleAnim(this));
        viz.setFreqAxis(Prefs.freqAxis(this));
        viz.setModeLabelAnchor(2);  // 浮窗：顶部居中
        viz.setLabelTopDp(11f);
        viz.setLabelRightDp(7f);  // 竖排更贴右边缘
        viz.setStereo(curStereoMode());
        viz.setStyle(Style.byIndex(Prefs.style(this)));
        viz.setPalette(Palette.byIndex(Prefs.palette(this)));
        overlayView.addView(viz, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 关闭按钮
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xCCFFFFFF);
        close.setTextSize(10f);
        close.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0x4D000000);
        gd.setStroke(dp(1), 0x44FFFFFF);
        close.setBackground(gd);
        // 只隐藏浮窗，继续在后台监听（要真正停止请用主按钮或通知栏“停止”）
        close.setOnClickListener(v -> hideOverlayKeepRunning());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(22), dp(22),
                Gravity.TOP | Gravity.END);
        clp.topMargin = dp(8);
        clp.rightMargin = dp(4);
        overlayView.addView(close, clp);

        float ratio = Prefs.size(this);
        int w = (int) (dp(300) * ratio);
        int h = (int) (dp(168) * ratio);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        params.alpha = Prefs.alpha(this);
        int savedX = Prefs.posX(this), savedY = Prefs.posY(this);
        if (savedX != Integer.MIN_VALUE && savedY != Integer.MIN_VALUE) {
            // 恢复上次位置，并 clamp 在屏幕内
            params.x = Math.max(0, Math.min(savedX, Math.max(0, dm.widthPixels - w)));
            params.y = Math.max(0, Math.min(savedY, Math.max(0, dm.heightPixels - h)));
        } else {
            params.x = Math.max(0, dm.widthPixels - w - dp(10));
            params.y = Math.max(dp(80), (int) (dm.heightPixels * 0.12f));
        }

        overlayView.setOnTouchListener(this::onOverlayTouch);

        // 向系统申请悬浮窗刷新率（否则高刷屏上多半只给 60Hz）
        Refresh.apply(params, this);

        try {
            wm.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
            fail("悬浮窗添加失败，请确认已授予“显示在其他应用上层”权限");
        }
    }

    private boolean onOverlayTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = params.x;
                startY = params.y;
                dragMoved = false;
                main.removeCallbacks(longPressHide);
                main.postDelayed(longPressHide, 700);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                main.removeCallbacks(longPressHide);
                if (e.getPointerCount() >= 2) {
                    pinchStartDist = spacing(e);
                    pinchStartW = params.width;
                    pinchStartH = params.height;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    // 双指缩放：直接改变悬浮窗尺寸
                    float d = spacing(e);
                    if (pinchStartDist > dp(12) && d > dp(12) && pinchStartW > 0) {
                        dragMoved = true;
                        float k = d / pinchStartDist;
                        int nw = (int) Math.max(dp(160), Math.min(dp(660), pinchStartW * k));
                        int nh = (int) Math.max(dp(90), nw * 0.56f);
                        params.width = nw;
                        params.height = nh;
                        try {
                            wm.updateViewLayout(overlayView, params);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                    dragMoved = true;
                    main.removeCallbacks(longPressHide);
                    params.x = startX + (int) dx;
                    params.y = startY + (int) dy;
                    try {
                        wm.updateViewLayout(overlayView, params);
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (pinchStartW > 0) {
                    Prefs.setSize(this, params.width / dp(300f));
                }
                return true;
            case MotionEvent.ACTION_UP: {
                main.removeCallbacks(longPressHide);
                float adx = Math.abs(e.getRawX() - downRawX);
                float ady = Math.abs(e.getRawY() - downRawY);
                if (dragMoved || adx >= dp(6) || ady >= dp(6)) {
                    snapToEdges();
                    // 记住位置，下次启动直接恢复
                    Prefs.setPos(this, params.x, params.y);
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastTapAt < 320) {
                        lastTapAt = 0;
                        cycleStyle();
                    } else {
                        lastTapAt = now;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPressHide);
                return true;
            default:
                return false;
        }
    }

    /** 两指间距，用于双指缩放 */
    private float spacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }
    private void cycleStyle() {
        int next = (Prefs.style(this) + 1) % Style.count();
        Prefs.setStyle(this, next);
        if (viz != null) viz.setStyle(Style.byIndex(next));
        broadcastState();
    }

    // ------------------------------------------------------------ 清理

    /** 只停采集（引擎 + 投屏会话），服务与浮窗继续存在，用于切换音源 */
    private void stopCaptureOnly() {
        AudioEngine.get().stop();
        releaseVirtualDisplay();
        if (projection != null) {
            try {
                if (projectionCallback != null) {
                    projection.unregisterCallback(projectionCallback);
                }
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
            projectionCallback = null;
        }
    }

    private void shutdown() {
        AudioEngine.get().stop();
        releaseVirtualDisplay();
        if (projection != null) {
            try {
                if (projectionCallback != null) {
                    projection.unregisterCallback(projectionCallback);
                }
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
            projectionCallback = null;
        }
        if (overlayView != null && wm != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        viz = null;
        TextCard.get().stop();
        running = false;
        broadcastState();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        AudioEngine.get().stop();
        releaseVirtualDisplay();
        if (overlayView != null && wm != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        TextCard.get().stop();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 让悬浮窗在用户划掉任务后仍继续，除非用户主动关闭
        super.onTaskRemoved(rootIntent);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}