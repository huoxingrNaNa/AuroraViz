package com.operit.audioviz;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 只为拿到"通知使用权"而存在的空 Service。
 *
 * 权限是 MediaSessionManager.getActiveSessions() 的前置条件；这里不读通知内容，
 * 也不做任何通知拦截。系统绑定它之后立刻把控制权交给 MediaWatcher。
 */
public class MediaWatcherService extends NotificationListenerService {

    private static final String TAG = "AuroraViz";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "nls connected");
        try {
            MediaWatcher.get().attach(this);
        } catch (Throwable t) {
            Log.i(TAG, "nls attach fail " + t);
        }
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(TAG, "nls disconnected");
        MediaWatcher.get().detach();
    }

    @Override
    public void onDestroy() {
        MediaWatcher.get().detach();
        super.onDestroy();
    }
}