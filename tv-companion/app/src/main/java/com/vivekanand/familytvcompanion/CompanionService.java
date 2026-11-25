package com.vivekanand.familytvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

public final class CompanionService extends Service {
    private static final String TAG = "FamilyTvCompanion";
    private static final String CHANNEL_ID = "family_tv_companion";
    private static final int NOTIFICATION_ID = 6470;
    private static final String[] PREFERENCE_FILES = {
        "jio_udp_controller",
        "power-state",
        "mute-toggle-state"
    };
    private CompanionServer server;

    public static void start(Context context) {
        Intent intent = new Intent(context, CompanionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        Context storageContext = directBootContext();
        server = new CompanionServer(storageContext);
        server.start();
        Log.i(TAG, "Companion ready at elapsedRealtime=" + SystemClock.elapsedRealtime());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Context directBootContext() {
        Context application = getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || application.isDeviceProtectedStorage()) {
            return application;
        }
        Context direct = application.createDeviceProtectedStorageContext();
        for (String preferences : PREFERENCE_FILES) {
            copyPreferencesIfMissing(
                application.getSharedPreferences(preferences, Context.MODE_PRIVATE),
                direct.getSharedPreferences(preferences, Context.MODE_PRIVATE)
            );
        }
        return direct;
    }

    @SuppressWarnings("unchecked")
    private static void copyPreferencesIfMissing(SharedPreferences source, SharedPreferences destination) {
        SharedPreferences.Editor editor = destination.edit();
        boolean changed = false;
        for (var entry : source.getAll().entrySet()) {
            if (destination.contains(entry.getKey())) continue;
            Object value = entry.getValue();
            if (value instanceof String item) editor.putString(entry.getKey(), item);
            else if (value instanceof Integer item) editor.putInt(entry.getKey(), item);
            else if (value instanceof Long item) editor.putLong(entry.getKey(), item);
            else if (value instanceof Float item) editor.putFloat(entry.getKey(), item);
            else if (value instanceof Boolean item) editor.putBoolean(entry.getKey(), item);
            else if (value instanceof java.util.Set<?> item) {
                editor.putStringSet(entry.getKey(), (java.util.Set<String>) item);
            } else continue;
            changed = true;
        }
        if (changed) editor.commit();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Family TV Companion",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps local JioTV+ shortcuts available.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent activity = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            this,
            0,
            activity,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setContentTitle("Family TV Companion")
            .setContentText("JioTV+ shortcuts are ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }
}
