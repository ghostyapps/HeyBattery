package com.example.batterystats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

public class PowerConnectionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Kablo takıldı veya çıktı
        if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction()) ||
                Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {

            // İşlemciyi uyandır
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyBattery:WakeUp");
                wl.acquire(3000);
            }

            // Servisi dürterek başlat (Zaten çalışıyorsa onStartCommand tetiklenir)
            try {
                Intent serviceIntent = new Intent(context, ChargingMonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}