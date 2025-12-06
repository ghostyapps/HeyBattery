package com.example.batterystats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;

public class PowerReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";
    private static final String KEY_PREV_CHARGING = "prev_charging";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
            handlePowerDisconnected(context);
        }
    }

    private void handlePowerDisconnected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();

        // Mevcut pil yüzdesini al
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus == null) {
            return;
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100f;

        int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, 0);
        boolean shouldReset = false;

        // Senin istediğin mantık:
        // - Şarj esnasında 80+'a ulaştıysa
        // - Ya da zaten 80+ iken takılıp 80+'da çıkarıldıysa
        if (batteryPct >= 100f) {
            shouldReset = true;
        } else if (batteryPct >= 80f) {
            if (startLevel < 80) {
                // 70 → 85 gibi
                shouldReset = true;
            } else {
                // Zaten 80+ iken takıldı, 80+ iken çıktı
                shouldReset = true;
            }
        }

        SharedPreferences.Editor editor = prefs.edit();

        if (shouldReset) {
            long currentElapsed = SystemClock.elapsedRealtime();
            long currentUptime = SystemClock.uptimeMillis();
            long currentSessionDeepSleep = currentElapsed - currentUptime;

            editor.putLong(KEY_LAST_FULL_CHARGE, now);
            editor.putLong(KEY_DEEP_SLEEP_ACCUMULATED, 0L);
            editor.putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, currentSessionDeepSleep);
        }

        editor.putBoolean(KEY_PREV_CHARGING, false);
        editor.apply();
    }
}