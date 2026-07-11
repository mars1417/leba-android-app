package com.leba.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * JavaScript接口桥：WebView JS → Android原生通知
 * 通过 @JavascriptInterface 暴露给页面JS调用
 */
public class NotificationBridge {

    private static final String TAG = "NotifBridge";
    private static final String CHANNEL_ID = "leba_notifications";
    private static final int BADGE_NOTIF_ID = 1001;

    private final Context context;
    private int badgeCount = 0;

    public NotificationBridge(Context context) {
        this.context = context;
        ensureChannel();
    }

    /** 确保通知渠道存在（与MainActivity共用ID） */
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "乐吧通知", NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("来自乐吧的通知消息");
                channel.enableVibration(true);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            } else {
                // 确保已有渠道开启了角标
                existing.setShowBadge(true);
                nm.createNotificationChannel(existing);
            }
        }
    }

    // ===== JS可调用的通知方法 =====

    /**
     * 显示Android系统通知（下拉通知栏）
     * 由JS notifManager.notifySW() 桥接调用
     */
    @android.webkit.JavascriptInterface
    public void showNotification(String title, String body, long id) {
        Log.d(TAG, "showNotification: " + title);

        // 点击通知→打开APP
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("notif_id", id);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, (int) (id % Integer.MAX_VALUE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title != null ? title : "乐吧提醒")
            .setContentText(body != null ? body : "")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) (id % 1000), builder.build());
    }

    /**
     * 更新桌面角标数字
     * 由JS notifManager.updateBadge() 桥接调用
     */
    @android.webkit.JavascriptInterface
    public void updateBadge(int count) {
        this.badgeCount = count;
        Log.d(TAG, "updateBadge: " + count);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            showBadgeNotification(count);
        }
        // API < 26: 无原生角标，忽略
    }

    /**
     * 清除桌面角标
     */
    @android.webkit.JavascriptInterface
    public void clearBadge() {
        this.badgeCount = 0;
        Log.d(TAG, "clearBadge");
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(BADGE_NOTIF_ID);
    }

    /** 通过一个静默通知携带角标数字（API 26+原生支持） */
    private void showBadgeNotification(int count) {
        if (count <= 0) {
            clearBadge();
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("乐吧公益中心")
            .setContentText("未读通知 " + count + " 条")
            .setNumber(count)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false)
            .setOngoing(false);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(BADGE_NOTIF_ID, builder.build());
    }

    public int getBadgeCount() {
        return badgeCount;
    }
}
