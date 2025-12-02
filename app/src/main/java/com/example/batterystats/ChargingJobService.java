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

public class ChargingJobService extends JobService {

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";
    private static final String KEY_PREV_CHARGING = "prev_charging";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable periodicChecker;

    @Override
    public boolean onStartJob(JobParameters params) {
        // Şarj takıldı! Nöbete başla.
        startPeriodicCheck(params);
        return true; // İşimiz uzun sürecek, bizi kapatma.
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // DİKKAT: Burası Android "Süren doldu" dediğinde veya sistem zorlandığında çalışır.

        // 1. Döngüyü durdur (Çöp oluşmasın)
        stopPeriodicCheck();

        // 2. ÖLMEDEN ÖNCE SON KAYIT!
        // Eğer hala şarjdaysak, şu anki saati kaydedelim ki veri kaybı olmasın.
        checkAndRecordState();

        // 3. REENKARNASYON EMRİ
        // return true -> "İşim bitmedi! Şartlar uygun olunca beni TEKRAR BAŞLAT."
        // Böylece 10 dk limiti dolsa bile sistem bizi kısa süre sonra tekrar ayağa kaldırır.
        return true;
    }

    private void startPeriodicCheck(JobParameters params) {
        periodicChecker = new Runnable() {
            @Override
            public void run() {
                // Durumu kontrol et ve kaydet
                boolean isStillCharging = checkAndRecordState();

                if (isStillCharging) {
                    // Hala şarjda, 1 dakika sonra tekrar bak
                    handler.postDelayed(this, 60_000);
                } else {
                    // Şarj bitmiş! İşi temizce bitir.
                    jobFinished(params, false);
                }
            }
        };
        handler.post(periodicChecker);
    }

    private void stopPeriodicCheck() {
        if (periodicChecker != null) {
            handler.removeCallbacks(periodicChecker);
        }
    }

    // Bu metod hem döngüde hem de ölürken çağrılır
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
            // ŞARJ DEVAM EDİYOR: Kayıtları güncelle

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
                long now = System.currentTimeMillis();
                long currentElapsed = SystemClock.elapsedRealtime();
                long currentUptime = SystemClock.uptimeMillis();
                long currentSessionDeepSleep = currentElapsed - currentUptime;

                prefs.edit()
                        .putLong(KEY_LAST_FULL_CHARGE, now)
                        .putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0)
                        .putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep)
                        .putBoolean(KEY_PREV_CHARGING, true)
                        .apply();
            } else {
                prefs.edit().putBoolean(KEY_PREV_CHARGING, true).apply();
            }

            if (!prefs.contains(KEY_PLUG_IN_LEVEL)) {
                prefs.edit().putInt(KEY_PLUG_IN_LEVEL, (int)batteryPct).apply();
            }

            return true; // Hala şarjda
        } else {
            // ŞARJ BİTMİŞ
            prefs.edit().putBoolean(KEY_PREV_CHARGING, false).apply();
            return false; // Bitti
        }
    }
}