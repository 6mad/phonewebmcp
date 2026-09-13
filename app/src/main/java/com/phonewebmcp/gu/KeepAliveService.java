package com.phonewebmcp.gu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * 前台常驻服务：防止系统（尤其是国产 ROM 如 vivo）冻结后台进程，
 * 保证 127.0.0.1:8765 的 JSON API 服务持续可用。
 */
public class KeepAliveService extends Service {
    public static final String CHANNEL_ID = "keepalive";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "运行状态",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("WebView 调试器运行中")
                .setContentText("API 服务: 127.0.0.1:8765 · 点开查看信息")
                .setOngoing(true)
                .build();

        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}