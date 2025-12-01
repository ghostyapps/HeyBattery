package com.example.batterystats;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

public class ChargingJobService extends JobService {

    private static final String PREFS_NAME = "BatteryStats";
    // MainActivity ile aynı key'i kullandığından emin ol
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";

    @Override
    public boolean onStartJob(JobParameters params) {
        // Şarj takıldı! Android bizi uyandırdı.
        // Hızlıca kaydımızı yapıyoruz.
        recordPlugInEvent(this);

        // KRİTİK DÜZELTME: "return false" diyoruz.
        // Anlamı: "İşim bitti, beni hafızada tutma, sistemi uyutabilirsin."
        // Bu sayede Google Play Services şişmez, pil harcanmaz.
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // İş senkron olduğu ve anında bittiği için burası normalde çağrılmaz.
        // Çağrılsa bile yapacak bir işimiz yok.
        return false;
    }

    private void recordPlugInEvent(Context context) {
        // Pil seviyesini öğren
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = (level / (float) scale) * 100;

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            // Sadece "Şarja şu seviyede takıldı" bilgisini kaydediyoruz.
            prefs.edit().putInt(KEY_PLUG_IN_LEVEL, (int)batteryPct).apply();
        }
    }
}