package com.ghostyapps.heybattery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class ChargingMonitorService extends Service {

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "silent_persistent_channel_v7";

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";

    private BroadcastReceiver batteryReceiver;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        Notification notification = buildNotification("Initializing...", false);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            if (notificationManager != null) notificationManager.notify(NOTIFICATION_ID, notification);
        }

        // Servis başlar başlamaz durumu kontrol et
        checkBatteryState();
        registerBatteryReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Servis her tetiklendiğinde kontrol et
        checkBatteryState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), ChargingMonitorService.class);
        restartServiceIntent.setPackage(getPackageName());
        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Battery Monitor Service", NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean isCharging) {
        int smallIconId = R.drawable.ic_monitor;
        android.graphics.Bitmap largeIcon = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.ic_stat_name);

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HeyBattery")
                .setContentText(text)
                .setSmallIcon(smallIconId)
                .setLargeIcon(largeIcon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setColor(android.graphics.Color.BLACK)
                .setColorized(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void checkBatteryState() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            processBatteryLogic(batteryStatus);
        }
    }

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    processBatteryLogic(intent);
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
    }

    // --- KRİTİK MANTIK BÖLÜMÜ ---
    private void processBatteryLogic(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        float batteryPct = (level / (float) scale) * 100f;
        boolean isChargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Dosyada "Başlangıç Seviyesi" var mı kontrol et
        boolean hasPlugInRecord = prefs.contains(KEY_PLUG_IN_LEVEL);

        if (isChargingNow) {
            // ŞARJ OLUYOR
            // Eğer kayıt yoksa, şu anki seviyeyi başlangıç olarak kaydet
            if (!hasPlugInRecord) {
                prefs.edit().putInt(KEY_PLUG_IN_LEVEL, (int) batteryPct).apply();
            }
        } else {
            // ŞARJ OLMUYOR (DISCHARGING)
            // Eğer "Başlangıç Kaydı" varsa, demek ki AZ ÖNCE kablo çekildi!
            // Çünkü kablo çekilmese kayıt olmazdı.
            if (hasPlugInRecord) {
                int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, -1);

                // Resetleme mantığını çalıştır
                performResetCheck(startLevel, batteryPct, prefs);

                // Kaydı sil (Artık discharge modundayız)
                // commit() kullanıyoruz ki servis ölmeden kesin yazılsın
                prefs.edit().remove(KEY_PLUG_IN_LEVEL).commit();
            }
        }

        // Bildirim Güncelle
        String statusText;
        if (isChargingNow) {
            statusText = (batteryPct >= 100) ? "Fully Charged" : String.format(Locale.US, "Charging: %.0f%%", batteryPct);
        } else {
            statusText = String.format(Locale.US, "Discharging: %.0f%%", batteryPct);
        }
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText, isChargingNow));
        }
    }

    private void performResetCheck(int startLevel, float currentPct, SharedPreferences prefs) {
        boolean shouldReset = false;

        // Kural 1: %100 olduysa KESİN Reset.
        if (currentPct >= 100f) {
            shouldReset = true;
        }
        // Kural 2: %80'i geçtiyse VE %80'in altında başladıysa Reset.
        else if (currentPct >= 80f && startLevel < 80) {
            shouldReset = true;
        }

        if (shouldReset) {
            long currentElapsed = SystemClock.elapsedRealtime();
            long currentUptime = SystemClock.uptimeMillis();
            long currentSessionDeepSleep = currentElapsed - currentUptime;

            // Verileri COMMIT ile kaydet (Atomik işlem)
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_LAST_FULL_CHARGE, System.currentTimeMillis());
            editor.putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0L);
            editor.putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep);
            editor.commit(); // apply() değil commit()!
        }
    }
}