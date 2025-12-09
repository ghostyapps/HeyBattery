package com.example.batterystats;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private TextView batteryPercentage;
    private TextView timeSinceCharge;
    private TextView remainingTime;
    private ImageView headerLogo;
    private TextView deepSleepTime;
    private TextView chargingInfo;
    private TextView timeToFullText;

    private int chargingInfoMode = 0;
    private int lastVoltageMv = -1;
    private int lastCurrentMicroA = 0;
    private double lastWatts = -1;
    private boolean lastIsCharging = false;
    private float lastBatteryPct = -1f;

    private int lastTemperature = 0;

    private long lastChargeSampleTime = 0L;
    private float lastChargeSampleLevel = -1f;
    private double avgChargeRatePerHour = 0.0;
    private double timePerPercentMs = 0.0;
    private String lastKnownTimeToFull = null;
    private static final float MIN_LEVEL_CHANGE = 1.0f;
    private static final long MIN_TIME_BETWEEN_SAMPLES = 60_000L;

    // ChargingMonitorService ile uyumlu anahtarlar
    private static final String KEY_DEEP_SLEEP_ACCUMULATED = "deep_sleep_accumulated";
    private static final String KEY_LAST_SYSTEM_DEEP_SLEEP = "last_system_deep_sleep";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_PLUG_IN_LEVEL = "plug_in_level";

    private boolean smoothPercentageEnabled = true;
    private float lastRealBatteryPctForSmooth = -1f;
    private long lastRealBatteryTimeMs = 0L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, filter);

            boolean isChargingNow = false;
            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                isChargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;

                updateBatteryInfo(batteryStatus);
            }

            long nextDelay = isChargingNow ? 3_000 : 60_000;
            uiHandler.postDelayed(this, nextDelay);
        }
    };

    private int tapCount = 0;
    private long firstTapTime = 0;
    private static final int REQUIRED_TAPS = 5;
    private static final long TAP_TIMEOUT = 2000;

    private SharedPreferences prefs;
    private BatteryDataManager dataManager;
    private static final String PREFS_NAME = "BatteryStats";

    private static final String KEY_ASKED_BATTERY_OPT = "asked_battery_opt";
    private static final String KEY_ASKED_USAGE_STATS = "asked_usage_stats";
    private static final String KEY_PREV_CHARGING = "prev_charging";

    private static final int REQUEST_BATTERY_OPTIMIZATION = 1001;
    private static final int REQUEST_USAGE_STATS = 1002;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryInfo(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- İZİN KODU (Android 13+) ---
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themePreference = prefs.getInt("theme_preference", 0);
        applyTheme(themePreference);

        // Pencere Ayarları
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int headerColor = ContextCompat.getColor(this, R.color.header_background);
        getWindow().setStatusBarColor(headerColor);

        boolean isNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insets =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insets != null) {
            insets.setAppearanceLightStatusBars(!isNight);
        }

        setContentView(R.layout.activity_main);

        // --- UI Elemanlarını Bağlama ---
        batteryPercentage = findViewById(R.id.batteryPercentage);
        if (batteryPercentage != null) {
            batteryPercentage.setOnClickListener(v -> {
                smoothPercentageEnabled = !smoothPercentageEnabled;
                IntentFilter tapFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent tapStatus = registerReceiver(null, tapFilter);
                if (tapStatus != null) {
                    updateBatteryInfo(tapStatus);
                }
            });
        }

        timeSinceCharge = findViewById(R.id.timeSinceCharge);
        int remainingTimeId = getResources().getIdentifier("remainingTime", "id", getPackageName());
        remainingTime = remainingTimeId != 0 ? findViewById(remainingTimeId) : null;
        headerLogo = findViewById(R.id.headerLogo);
        deepSleepTime = findViewById(R.id.deepSleepTime);
        chargingInfo = findViewById(R.id.chargingInfo);
        timeToFullText = findViewById(R.id.timeToFullText);

        ImageView chargingHintIcon = findViewById(R.id.chargingHintIcon);
        if (chargingHintIcon != null) {
            int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            switch (nightModeFlags) {
                case Configuration.UI_MODE_NIGHT_YES:
                    chargingHintIcon.setImageResource(R.drawable.ic_hand_point_dark);
                    break;
                case Configuration.UI_MODE_NIGHT_NO:
                case Configuration.UI_MODE_NIGHT_UNDEFINED:
                    chargingHintIcon.setImageResource(R.drawable.ic_hand_point);
                    break;
            }
        }

        View chargingCard = findViewById(R.id.card_charging_info);
        if (chargingCard != null) {
            chargingCard.setOnClickListener(v -> {
                chargingInfoMode = (chargingInfoMode + 1) % 3;
                refreshChargingInfoText();
            });
        }

        dataManager = new BatteryDataManager(this);

        if (headerLogo != null) {
            headerLogo.setOnClickListener(v -> handleGreetingTap());
        }

        TextView aboutButton = findViewById(R.id.aboutButton);
        if (aboutButton != null) {
            aboutButton.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
            });
        }

        TextView footerSignature = findViewById(R.id.footerSignature);
        if (footerSignature != null) {
            footerSignature.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/ghostyapps"));
                startActivity(browserIntent);
            });
        }

        // --- Başlangıç İşlemleri ---
        checkPermissions();

        // Servisi başlat
        startPersistentService();

        // Pil verilerini ilk kez al
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);

        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            updateBatteryInfo(batteryStatus);
        }
    }

    // --- KRİTİK BÖLÜM: Uygulama Öne Gelince Servisi Kontrol Et ---
    @Override
    protected void onResume() {
        super.onResume();

        // KONTROLÜ KALDIRDIK. DİREKT BAŞLATIYORUZ.
        // Bu sayede bildirim kaybolmuşsa bile uygulama açılınca %100 geri gelir.
        startPersistentService();

        uiHandler.removeCallbacks(uiUpdater);
        uiHandler.post(uiUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
        }
        uiHandler.removeCallbacks(uiUpdater);
    }

    // --- Servis Yardımcı Metodları ---

    private void startPersistentService() {
        Intent serviceIntent = new Intent(this, ChargingMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }



    // --- İzin Kontrol Metodları ---

    private void checkPermissions() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String packageName = getPackageName();

        boolean askedBatteryOpt = prefs.getBoolean(KEY_ASKED_BATTERY_OPT, false);

        if (!powerManager.isIgnoringBatteryOptimizations(packageName) && !askedBatteryOpt) {
            showBatteryOptimizationDialog();
        } else {
            checkUsageStatsPermission();
        }
    }

    private void checkUsageStatsPermission() {
        boolean askedUsageStats = prefs.getBoolean(KEY_ASKED_USAGE_STATS, false);

        if (!hasUsageStatsPermission() && !askedUsageStats) {
            showUsageStatsDialog();
        }
    }

    private boolean hasUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void showBatteryOptimizationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("To track battery statistics accurately, this app needs to be exempted from battery optimization. Would you like to grant this permission?")
                .setPositiveButton("Allow", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_ASKED_BATTERY_OPT, true).apply();
                    requestBatteryOptimization();
                })
                .setNegativeButton("Not Now", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_ASKED_BATTERY_OPT, true).apply();
                    Toast.makeText(this, "Battery tracking may be less accurate", Toast.LENGTH_LONG).show();
                    checkUsageStatsPermission();
                })
                .setCancelable(false)
                .show();
    }

    private void showUsageStatsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Usage Statistics Permission")
                .setMessage("To provide detailed battery usage insights (like screen-on time and app usage), this app needs access to usage statistics. Would you like to grant this permission?")
                .setPositiveButton("Allow", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_ASKED_USAGE_STATS, true).apply();
                    requestUsageStatsPermission();
                })
                .setNegativeButton("Not Now", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_ASKED_USAGE_STATS, true).apply();
                    Toast.makeText(this, "Some features will be limited", Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    private void requestBatteryOptimization() {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open battery optimization settings", Toast.LENGTH_SHORT).show();
            checkUsageStatsPermission();
        }
    }

    private void requestUsageStatsPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivityForResult(intent, REQUEST_USAGE_STATS);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open usage stats settings", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BATTERY_OPTIMIZATION) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Battery optimization disabled. Tracking will be more accurate!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Battery tracking may be less accurate", Toast.LENGTH_LONG).show();
            }
            checkUsageStatsPermission();
        } else if (requestCode == REQUEST_USAGE_STATS) {
            if (hasUsageStatsPermission()) {
                Toast.makeText(this, "Usage statistics access granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some features will be limited", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Pil Verilerini Güncelleme ve Hesaplama ---

    private void updateBatteryInfo(Intent intent) {
        long now = System.currentTimeMillis();
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;
        lastBatteryPct = batteryPct;

        if (lastRealBatteryPctForSmooth < 0f) {
            lastRealBatteryPctForSmooth = batteryPct;
            lastRealBatteryTimeMs = now;
        } else {
            if (Math.abs(batteryPct - lastRealBatteryPctForSmooth) >= 0.5f) {
                lastRealBatteryPctForSmooth = batteryPct;
                lastRealBatteryTimeMs = now;
            }
        }

        float displayPct = batteryPct;
        if (smoothPercentageEnabled && timePerPercentMs > 0.0 && batteryPct > 0f && batteryPct < 100f
                && lastRealBatteryTimeMs > 0L) {
            long elapsedSinceReal = now - lastRealBatteryTimeMs;
            if (elapsedSinceReal > 0L) {
                double extraPercent = elapsedSinceReal / timePerPercentMs;
                if (extraPercent < 0.0) extraPercent = 0.0;
                double maxExtra = 0.99;
                displayPct = (float) Math.min(batteryPct + extraPercent, batteryPct + maxExtra);
            }
        }

        String formatted = String.format(Locale.US, "%.0f%%", displayPct);
        batteryPercentage.setText(formatted);

        // Deep Sleep Calculation
        long currentElapsed = SystemClock.elapsedRealtime();
        long currentUptime = SystemClock.uptimeMillis();
        long totalDeviceDeepSleep = currentElapsed - currentUptime;
        long baselineDeepSleep = prefs.getLong(KEY_LAST_SYSTEM_DEEP_SLEEP, 0);
        long deepSleepSinceCharge = totalDeviceDeepSleep - baselineDeepSleep;

        if (deepSleepSinceCharge < 0) {
            deepSleepSinceCharge = totalDeviceDeepSleep;
            prefs.edit().putLong(KEY_LAST_SYSTEM_DEEP_SLEEP, 0).apply();
        }

        if (deepSleepTime != null) {
            deepSleepTime.setText(formatTimeDuration(deepSleepSinceCharge));
        }

        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

        lastIsCharging = isCharging;
        lastVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        lastCurrentMicroA = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
        lastWatts = computeWatts(lastVoltageMv, lastCurrentMicroA);
        lastTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

        updateChargeRateEstimate(isCharging, batteryPct);

        if (timeToFullText != null) {
            timeToFullText.setText(getTimeToFullText());
        }

        refreshChargingInfoText();

        boolean prevCharging = prefs.getBoolean(KEY_PREV_CHARGING, false);
        long lastFullCharge = prefs.getLong(KEY_LAST_FULL_CHARGE, 0);

        if (isCharging && !prevCharging) {
            if (!prefs.contains(KEY_PLUG_IN_LEVEL)) {
                prefs.edit().putInt(KEY_PLUG_IN_LEVEL, (int)batteryPct).apply();
            }
        }

        if (!isCharging && prevCharging) {
            prefs.edit().putBoolean(KEY_PREV_CHARGING, false).apply();
        }

        prefs.edit().putBoolean(KEY_PREV_CHARGING, isCharging).apply();

        if (lastFullCharge > 0) {
            long timeDiff = System.currentTimeMillis() - lastFullCharge;
            String timeString = formatTimeDuration(timeDiff);
            timeSinceCharge.setText(timeString);
            dataManager.updateCurrentCycle(System.currentTimeMillis(), (int) batteryPct);
        } else {
            timeSinceCharge.setText("No data yet");
        }

        if (remainingTime != null) {
            if (lastFullCharge > 0 && batteryPct < 100) {
                double historicalDrainRate = dataManager.getAverageDrainRate();
                long timeDiff = System.currentTimeMillis() - lastFullCharge;
                int startLevel = prefs.getInt(KEY_PLUG_IN_LEVEL, 100);
                if (startLevel <= 0) startLevel = 100;

                float percentUsed = startLevel - batteryPct;
                double currentSessionDrainRate = 0;

                if (timeDiff > 0 && percentUsed > 0) {
                    double hoursElapsed = timeDiff / 3600000.0;
                    currentSessionDrainRate = percentUsed / hoursElapsed;
                }

                double finalDrainRate = 0;

                if (historicalDrainRate > 0 && currentSessionDrainRate > 0) {
                    finalDrainRate = (currentSessionDrainRate * 0.6) + (historicalDrainRate * 0.4);
                }
                else if (currentSessionDrainRate > 0) {
                    finalDrainRate = currentSessionDrainRate;
                }
                else if (historicalDrainRate > 0) {
                    finalDrainRate = historicalDrainRate;
                }

                if (finalDrainRate > 0) {
                    double hoursRemaining = batteryPct / finalDrainRate;
                    if (hoursRemaining > 200) {
                        remainingTime.setText("Calculating...");
                    } else {
                        long millisRemaining = (long) (hoursRemaining * 3600000);
                        remainingTime.setText(formatTimeDuration(millisRemaining));
                    }
                } else {
                    remainingTime.setText("Calculating...");
                }
            } else {
                remainingTime.setText("Not available");
            }
        }
    }

    private String formatTimeDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        if (hours == 0) {
            if (minutes <= 1) {
                return "1 minute";
            } else {
                return String.format(Locale.US, "%d minutes", minutes);
            }
        }
        else {
            String hUnit = (hours == 1) ? "hour" : "hours";
            if (minutes == 0) {
                return String.format(Locale.US, "%d %s", hours, hUnit);
            }
            else {
                String mUnit = (minutes == 1) ? "minute" : "minutes";
                return String.format(Locale.US, "%d %s %d %s", hours, hUnit, minutes, mUnit);
            }
        }
    }

    private void refreshChargingInfoText() {
        if (chargingInfo == null) return;

        final float SMALL_SP = 20f;
        final float LARGE_SP = 34f;

        if (!lastIsCharging) {
            chargingInfo.setText("Discharging");
            chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP);
            return;
        }

        String mainText = "";
        if (chargingInfoMode == 0) { // W
            if (lastWatts > 0) mainText = String.format(Locale.US, "%.1f W", lastWatts);
            else mainText = "charging";
        } else if (chargingInfoMode == 1) { // V
            if (lastVoltageMv > 0) mainText = String.format(Locale.US, "%.2f V", lastVoltageMv / 1000.0);
            else mainText = "N/A";
        } else { // mA
            if (lastCurrentMicroA != 0) mainText = String.format(Locale.US, "%d mA", Math.round(Math.abs(lastCurrentMicroA) / 1000.0f));
            else mainText = "N/A";
        }

        int tempC = Math.round(lastTemperature / 10.0f);
        String tempText = "\n" + tempC + "°C";

        if (mainText.equals("charging") || mainText.equals("N/A")) {
            chargingInfo.setText(mainText + tempText);
            chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP);
        } else {
            SpannableString spannable = new SpannableString(mainText + tempText);
            spannable.setSpan(new RelativeSizeSpan(0.5f), mainText.length(), spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            chargingInfo.setText(spannable);
            chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, LARGE_SP);
        }
    }

    private void updateChargeRateEstimate(boolean isCharging, float batteryPct) {
        long now = System.currentTimeMillis();

        if (isCharging && batteryPct > 0f && batteryPct < 100f) {
            if (lastChargeSampleTime > 0 && lastChargeSampleLevel >= 0f) {
                long deltaMs = now - lastChargeSampleTime;
                float deltaLevel = batteryPct - lastChargeSampleLevel;

                if (deltaMs >= MIN_TIME_BETWEEN_SAMPLES && deltaLevel >= MIN_LEVEL_CHANGE) {
                    double hours = deltaMs / 3600000.0;
                    double instantRate = deltaLevel / hours;

                    if (instantRate >= 1.0 && instantRate <= 50.0) {
                        if (avgChargeRatePerHour <= 0.0) {
                            avgChargeRatePerHour = instantRate;
                        } else {
                            avgChargeRatePerHour = 0.6 * avgChargeRatePerHour + 0.4 * instantRate;
                        }
                    }

                    double instantMsPerPercent = (double) deltaMs / deltaLevel;
                    final double MIN_MS_PER_PERCENT = 30_000.0;
                    final double MAX_MS_PER_PERCENT = 600_000.0;

                    if (instantMsPerPercent >= MIN_MS_PER_PERCENT &&
                            instantMsPerPercent <= MAX_MS_PER_PERCENT) {
                        if (timePerPercentMs <= 0.0) {
                            timePerPercentMs = instantMsPerPercent;
                        } else {
                            timePerPercentMs = 0.6 * timePerPercentMs + 0.4 * instantMsPerPercent;
                        }
                    }

                    lastChargeSampleTime = now;
                    lastChargeSampleLevel = batteryPct;
                }
                else if (deltaMs >= 300_000L) {
                    lastChargeSampleTime = now;
                    lastChargeSampleLevel = batteryPct;
                }
            } else {
                lastChargeSampleTime = now;
                lastChargeSampleLevel = batteryPct;
            }
        } else {
            lastChargeSampleTime = 0L;
            lastChargeSampleLevel = -1f;
            avgChargeRatePerHour = 0.0;
            timePerPercentMs = 0.0;
            lastKnownTimeToFull = null;
        }
    }

    private String getTimeToFullText() {
        if (!lastIsCharging) return "";

        if (lastBatteryPct < 0f) {
            return (lastKnownTimeToFull != null) ? lastKnownTimeToFull : "N/A";
        }
        if (lastBatteryPct >= 99.9f) {
            lastKnownTimeToFull = "Full";
            return lastKnownTimeToFull;
        }
        if (lastBatteryPct >= 99f) {
            lastKnownTimeToFull = "Almost full";
            return lastKnownTimeToFull;
        }

        if (timePerPercentMs > 0.0) {
            double remainingPercent = Math.max(0.0, 100.0 - lastBatteryPct);
            long millisRemaining = (long) (timePerPercentMs * remainingPercent);

            if (millisRemaining <= 0L) {
                lastKnownTimeToFull = "Almost full";
                return lastKnownTimeToFull;
            }

            long oneMinuteMillis = 60_000L;
            long oneDayMillis = 24L * 3600_000L;

            if (millisRemaining < oneMinuteMillis) {
                lastKnownTimeToFull = "Less than 1 minute";
                return lastKnownTimeToFull;
            }
            if (millisRemaining > oneDayMillis) {
                lastKnownTimeToFull = "More than 24 hours";
                return lastKnownTimeToFull;
            }

            long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millisRemaining);
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;

            StringBuilder sb = new StringBuilder("Full in ");
            if (hours > 0) {
                sb.append(hours).append(hours == 1 ? " hour" : " hours");
                if (minutes > 0) sb.append(" ");
            }
            if (minutes > 0) {
                sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
            }

            lastKnownTimeToFull = sb.toString();
            return lastKnownTimeToFull;
        }

        if (avgChargeRatePerHour <= 0.0) {
            return (lastKnownTimeToFull != null) ? lastKnownTimeToFull : "Calculating...";
        }

        double remainingPercent = Math.max(0.0, 100.0 - lastBatteryPct);
        double hoursRemaining = remainingPercent / avgChargeRatePerHour;

        if (hoursRemaining < 0) {
            lastKnownTimeToFull = "Almost full";
            return lastKnownTimeToFull;
        }

        long millisRemaining = (long) (hoursRemaining * 3600000L);
        if (millisRemaining <= 0L) {
            lastKnownTimeToFull = "Almost full";
            return lastKnownTimeToFull;
        }

        long oneMinuteMillis = 60_000L;
        if (millisRemaining < oneMinuteMillis) {
            lastKnownTimeToFull = "Less than 1 minute";
            return lastKnownTimeToFull;
        }

        long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millisRemaining);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        StringBuilder sb = new StringBuilder("Full in ");
        if (hours > 0) {
            sb.append(hours).append(hours == 1 ? " hour" : " hours");
            if (minutes > 0) sb.append(" ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }

        if (hours == 0 && minutes == 0) {
            lastKnownTimeToFull = "Full in less than 1 minute";
        } else {
            lastKnownTimeToFull = sb.toString();
        }

        return lastKnownTimeToFull;
    }

    private double computeWatts(int voltageMv, int currentMicroA) {
        if (voltageMv > 0 && currentMicroA != 0) {
            double volts = voltageMv / 1000.0;
            double amps = Math.abs(currentMicroA) / 1_000_000.0;
            return volts * amps;
        }
        return -1;
    }

    private void handleGreetingTap() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - firstTapTime > TAP_TIMEOUT) {
            tapCount = 1;
            firstTapTime = currentTime;
        } else {
            tapCount++;
            if (tapCount >= REQUIRED_TAPS) {
                tapCount = 0;
                firstTapTime = 0;
                showThemeSelectionDialog();
            }
        }
    }

    private void showThemeSelectionDialog() {
        final String[] themes = {"System Default", "Light", "Dark"};
        int currentTheme = prefs.getInt("theme_preference", 0);

        new AlertDialog.Builder(this)
                .setTitle("Choose Theme")
                .setSingleChoiceItems(themes, currentTheme, (dialog, which) -> {
                    prefs.edit().putInt("theme_preference", which).apply();
                    applyTheme(which);
                    dialog.dismiss();
                    recreate();
                    Toast.makeText(this, "Theme changed to " + themes[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyTheme(int theme) {
        switch (theme) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }
}