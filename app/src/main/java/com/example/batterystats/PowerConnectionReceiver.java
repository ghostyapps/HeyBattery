package com.example.batterystats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;

public class PowerConnectionReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // SADECE KABLO ÇEKİLDİĞİNDE ÇALIŞ
        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {

            // 1. İşlemciyi 3 saniyeliğine ZORLA uyanık tut (WakeLock)
            // Bu, sistemin işlemi öldürmesini engeller.
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = null;
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyBattery:CriticalSave");
                wl.acquire(3000);
            }

            try {
                // Kritik Kayıt İşlemi
                performResetLogic(context);
            } finally {
                // İşim bitti, servisleri durdur
                try {
                    context.stopService(new Intent(context, ChargingMonitorService.class));
                } catch (Exception ignored) {}

                if (wl != null && wl.isHeld()) {
                    wl.release();
                }
            }
        }
        // KABLO TAKILDIĞINDA
        else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            // Sadece servisi tetikle, kayıt işini servise bırak
            try {
                Intent serviceIntent = new Intent(context, ChargingMonitorService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void performResetLogic(Context context) {
        // 1. Sistemden anlık pil seviyesini al (Senkronize olarak)
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus == null) return;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float currentPct = (level / (float) scale) * 100f;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Başlangıç seviyesini oku (Bunu servis kaydetmişti)
        int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, -1);

        // Eğer veri yoksa ve pil %100 ise yine de resetle (Güvenlik önlemi)
        if (startLevel == -1 && currentPct >= 100f) {
            startLevel = 0; // Resetlemeye zorla
        } else if (startLevel == -1) {
            return; // Veri yok, %100 değil, işlem yapma.
        }

        boolean shouldReset = false;

        // --- KARAR ANI ---
        if (currentPct >= 100f) {
            shouldReset = true;
        } else if (currentPct >= 80f && startLevel < 80) {
            shouldReset = true;
        }

        SharedPreferences.Editor editor = prefs.edit();

        if (shouldReset) {
            long currentElapsed = SystemClock.elapsedRealtime();
            long currentUptime = SystemClock.uptimeMillis();
            long currentSessionDeepSleep = currentElapsed - currentUptime;

            // Verileri Hazırla
            editor.putLong(KEY_LAST_FULL_CHARGE, System.currentTimeMillis());
            editor.putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0L);
            editor.putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep);
        }

        // Başlangıç verisini temizle
        editor.remove(KEY_PLUG_IN_LEVEL);

        // --- EN KRİTİK NOKTA: COMMIT ---
        // apply() kullanmıyoruz! commit() işlemi diske yazılana kadar kodu bloklar.
        // WakeLock sayesinde CPU uyumadan bu işlem biter.
        editor.commit();
    }
}