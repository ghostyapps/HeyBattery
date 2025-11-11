package com.example.batterystats;

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
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.TimeUnit;

public class BatteryMonitorService extends Service {

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_CHARGE_START_LEVEL = "charge_start_level";
    private static final String KEY_WAS_FULL = "was_full"; // Track if battery reached 100%
    private static final String CHANNEL_ID = "BatteryMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private SharedPreferences prefs;
    private BatteryDataManager dataManager;
    
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkBatteryStatus(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        dataManager = new BatteryDataManager(this);
        
        // Create notification channel for Android 8.0+
        createNotificationChannel();
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Register battery receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Check battery status immediately
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            checkBatteryStatus(batteryStatus);
        }
        
        // Service will restart if killed by system
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            // Receiver might not be registered
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors battery status for statistics");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeyBattery")
            .setContentText("Monitoring battery status")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }

    private void checkBatteryStatus(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;
        
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL;
        
        boolean wasFull = prefs.getBoolean(KEY_WAS_FULL, false);
        
        // Step 1: Mark that battery reached 100% while charging
        if (batteryPct >= 99 && isCharging) {
            prefs.edit().putBoolean(KEY_WAS_FULL, true).apply();
        }
        
        // Step 2: When unplugged after being full, start the timer
        if (wasFull && !isCharging) {
            long currentTime = System.currentTimeMillis();
            long lastFullCharge = prefs.getLong(KEY_LAST_FULL_CHARGE, 0);
            
            // Only update if it's been more than 1 hour since last cycle started
            if (currentTime - lastFullCharge > TimeUnit.HOURS.toMillis(1)) {
                prefs.edit().putLong(KEY_LAST_FULL_CHARGE, currentTime).apply();
                prefs.edit().putInt(KEY_CHARGE_START_LEVEL, (int) batteryPct).apply();
                
                // Create and save new charge cycle
                ChargeCycle newCycle = new ChargeCycle(currentTime, (int) batteryPct);
                dataManager.addChargeCycle(newCycle);
            }
            
            // Reset the flag
            prefs.edit().putBoolean(KEY_WAS_FULL, false).apply();
        }
    }
}
