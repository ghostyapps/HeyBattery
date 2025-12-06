package com.example.batterystats;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;

public class ChargingJobService extends JobService {

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_CHARGE_START_LEVEL = "charge_start_level";
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";
    private static final String KEY_PREV_CHARGING = "prev_charging";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable periodicChecker;

    @Override
    public boolean onStartJob(JobParameters params) {
        // Şarj takıldı! Hemen durumu kaydet.
        checkAndRecordState();

        // Döngüyü başlat
        startPeriodicCheck(params);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopPeriodicCheck();

        if (isDeviceCharging(this)) {
            checkAndRecordState();
            return true;    // şarj devam ediyorsa yeniden başlat
        } else {
            // Fişten çekildi bilgisini artık PowerReceiver yakalıyor
            return false;
        }
    }

    private boolean isDeviceCharging(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
        }
        return false;
    }

    private void startPeriodicCheck(JobParameters params) {
        periodicChecker = new Runnable() {
            @Override
            public void run() {
                boolean isStillCharging = checkAndRecordState();

                if (isStillCharging) {
                    handler.postDelayed(this, 60_000);
                } else {
                    jobFinished(params, false);
                }
            }
        };
        // İlk döngü 1 dk sonra
        handler.postDelayed(periodicChecker, 60_000);
    }

    private void stopPeriodicCheck() {
        if (periodicChecker != null) {
            handler.removeCallbacks(periodicChecker);
        }
    }

    private boolean checkAndRecordState() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);

        if (batteryStatus == null) return false;

        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (isCharging) {
            // DÜZELTME: Şarj devam ettiği sürece başlangıç seviyesini koruyoruz.
            // Ama eğer "plug_in_level" hiç yoksa (veya hatalıysa) güncelliyoruz.
            // Asıl güncelleme MainActivity veya onStartJob'da yapılıyor.
            // Burada sadece zamanı güncelleyelim.

            int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, 0);
            boolean shouldUpdateTimestamp = false;

            if (batteryPct >= 100) {
                shouldUpdateTimestamp = true;
            } else if (batteryPct >= 80) {
                if (startLevel < 80) {
                    shouldUpdateTimestamp = true;
                }
            }

            if (shouldUpdateTimestamp) {
                long currentElapsed = SystemClock.elapsedRealtime();
                long currentUptime = SystemClock.uptimeMillis();
                long currentSessionDeepSleep = currentElapsed - currentUptime;

                // Deep sleep baz çizgisini güncelle, ama "son 80% şarj zamanı"nı
                // sadece fişten çekildiğinde yazacağız.
                prefs.edit()
                        .putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0)
                        .putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep)
                        .putBoolean(KEY_PREV_CHARGING, true)
                        .apply();
            } else {
                prefs.edit().putBoolean(KEY_PREV_CHARGING, true).apply();
            }

            // DÜZELTME: Her zaman taze tutalım, ama sadece şarjın başındaysak mantıklı.
            // Buradaki contains kontrolünü kaldırıp, MainActivity'nin yazdığına güvenmek daha doğru.
            // Ama JobService tek başına çalışıyorsa diye şu kontrolü esnettim:
            if (!prefs.contains(KEY_PLUG_IN_LEVEL)) {
                prefs.edit().putInt(KEY_PLUG_IN_LEVEL, (int)batteryPct).apply();
            }

            return true;
        } else {
            recordUnplugEvent(this);
            return false;
        }
    }

    private void recordUnplugEvent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long now = System.currentTimeMillis();

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = (level / (float) scale) * 100;

            int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, 0);
            boolean shouldReset = false;

            if (batteryPct >= 100) {
                shouldReset = true;
            }
            else if (batteryPct >= 80) {
                if (startLevel < 80) {
                    shouldReset = true;
                }
            }

            if (shouldReset) {
                long currentElapsed = SystemClock.elapsedRealtime();
                long currentUptime = SystemClock.uptimeMillis();
                long currentSessionDeepSleep = currentElapsed - currentUptime;

                prefs.edit()
                        .putLong(KEY_LAST_FULL_CHARGE, now)
                        .putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0)
                        .putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep)
                        .putBoolean(KEY_PREV_CHARGING, false)
                        .apply();
            } else {
                prefs.edit().putBoolean(KEY_PREV_CHARGING, false).apply();
            }
        } else {
            prefs.edit().putBoolean(KEY_PREV_CHARGING, false).apply();
        }

        // ... mevcut recordUnplugEvent kodu bittiğinde:

        // Bir sonraki şarj döngüsü için Job'u tekrar planla
        android.app.job.JobScheduler scheduler =
                (android.app.job.JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            android.content.ComponentName componentName =
                    new android.content.ComponentName(context, ChargingJobService.class);
            android.app.job.JobInfo info = new android.app.job.JobInfo.Builder(123, componentName)
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(info);
        }
    }
}