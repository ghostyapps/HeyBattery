package com.example.batterystats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // ARTIK SERVİS YOK.
            // Telefon yeniden başladığında "Şarj Tuzağını" (Job) tekrar kuruyoruz.
            // Böylece telefon açıldıktan sonra şarja takarsan sistem bizi uyandırır.

            ComponentName componentName = new ComponentName(context, ChargingJobService.class);
            JobInfo info = new JobInfo.Builder(123, componentName)
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .build();

            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                scheduler.schedule(info);
            }
        }
    }
}