package com.example.wifispeaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class Notifications {
    static final String CHANNEL_ID = "wifi_speaker_stream";

    private Notifications() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WiFi Speaker Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("WiFi audio streaming status");
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    static Notification build(Context context, String title, String text) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
