package com.example.batterystats;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.graphics.Color;
import android.view.WindowManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.TimeUnit;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import android.Manifest;
import android.os.Build;
import android.content.pm.PackageManager;
import android.view.View;
import android.util.TypedValue;
import java.io.File;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView batteryPercentage;
    private TextView timeSinceCharge;
    private TextView remainingTime;
    private ImageView headerLogo;
    private TextView deepSleepTime;
    private TextView chargingInfo;
    private TextView timeToFullText;
    // Charging info toggle state: 0=Watts, 1=Volts, 2=mA, 3=Time to full
    private int chargingInfoMode = 0;
    private int lastVoltageMv = -1;
    private int lastCurrentMicroA = 0;
    private double lastWatts = -1;
    private boolean lastIsCharging = false;
    private float lastBatteryPct = -1f;

    // For estimating time to full while charging
    private long lastChargeSampleTime = 0L;
    private float lastChargeSampleLevel = -1f;
    private double avgChargeRatePerHour = 0.0; // % per hour
    // Smoothed milliseconds taken per 1% change (updated when we observe >=1% change)
    private double timePerPercentMs = 0.0;
    // Last known non-empty "time to full" text to avoid flickering back to "Calculating..."
    private String lastKnownTimeToFull = null;
    private static final float MIN_LEVEL_CHANGE = 1.0f; // En az %1 değişim gerekli
    private static final long MIN_TIME_BETWEEN_SAMPLES = 60_000L; // En az 60 saniye bekle

    // Fake smooth battery percentage display toggle and state
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

            // Dynamic refresh: 3s while charging (Watt), 60s otherwise (timers)
            long nextDelay = isChargingNow ? 3_000 : 60_000;
            uiHandler.postDelayed(this, nextDelay);
        }
    };

    // Easter egg variables
    private int tapCount = 0;
    private long firstTapTime = 0;
    private static final int REQUIRED_TAPS = 5;
    private static final long TAP_TIMEOUT = 2000; // 2 seconds

    private SharedPreferences prefs;
    private BatteryDataManager dataManager;
    private static final String PREFS_NAME = "BatteryStats";
    private static final String KEY_LAST_FULL_CHARGE = "last_full_charge";
    private static final String KEY_CHARGE_START_LEVEL = "charge_start_level";
    private static final String KEY_ASKED_BATTERY_OPT = "asked_battery_opt";
    private static final String KEY_ASKED_USAGE_STATS = "asked_usage_stats";
    private static final String KEY_BASE_DEEP_SLEEP = "base_deep_sleep";
    private static final String KEY_BASE_DEEP_SLEEP_ELAPSED = "base_deep_sleep_elapsed";
    private static final String KEY_WAS_FULL = "was_full";
    private static final String KEY_PREV_CHARGING = "prev_charging";
    private static final int REQUEST_BATTERY_OPTIMIZATION = 1001;
    private static final int REQUEST_USAGE_STATS = 1002;

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryInfo(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply saved theme preference before setting content view
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themePreference = prefs.getInt("theme_preference", 0);
        applyTheme(themePreference);

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }

        // Make status bar match header color from theme (light/dark aware)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        int headerColor = ContextCompat.getColor(this, R.color.header_background);
        getWindow().setStatusBarColor(headerColor);

        // Ensure status bar icons are readable in light/dark
        boolean isNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat insets =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (insets != null) {
            insets.setAppearanceLightStatusBars(!isNight);
        }

        setContentView(R.layout.activity_main);

        batteryPercentage = findViewById(R.id.batteryPercentage);

        if (batteryPercentage != null) {
            batteryPercentage.setOnClickListener(v -> {
                // Toggle fake smooth percentage on tap
                smoothPercentageEnabled = !smoothPercentageEnabled;
                // Force an immediate refresh using current battery intent
                IntentFilter tapFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent tapStatus = registerReceiver(null, tapFilter);
                if (tapStatus != null) {
                    updateBatteryInfo(tapStatus);
                }
            });
        }
        timeSinceCharge = findViewById(R.id.timeSinceCharge);
        // remainingTime id might not exist in XML; resolve dynamically to avoid R.id compile error
        int remainingTimeId = getResources().getIdentifier("remainingTime", "id", getPackageName());
        remainingTime = remainingTimeId != 0 ? findViewById(remainingTimeId) : null;
        headerLogo = findViewById(R.id.headerLogo);
        deepSleepTime = findViewById(R.id.deepSleepTime);
        chargingInfo = findViewById(R.id.chargingInfo);
        timeToFullText = findViewById(R.id.timeToFullText);

        // Set charging hint icon drawable according to theme
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

        // 4. kartın tamamını tıklanabilir yap
        View chargingCard = findViewById(R.id.card_charging_info);
        if (chargingCard != null) {
            chargingCard.setOnClickListener(v -> {
                // 0 = Watts, 1 = Volts, 2 = mA
                chargingInfoMode = (chargingInfoMode + 1) % 3;
                refreshChargingInfoText();
            });
        }

        dataManager = new BatteryDataManager(this);

        // Set up easter egg tap listener on header logo
        if (headerLogo != null) {
            headerLogo.setOnClickListener(v -> handleGreetingTap());
        }

        // Set up About button click listener
        TextView aboutButton = findViewById(R.id.aboutButton);
        if (aboutButton != null) {
            aboutButton.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
            });
        }

        // Set up footer signature click listener (GitHub link)
        TextView footerSignature = findViewById(R.id.footerSignature);
        if (footerSignature != null) {
            footerSignature.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/ghostyapps"));
                startActivity(browserIntent);
            });
        }

        // Start the background battery monitoring service
        startBatteryMonitorService();

        // Check and request permissions
        checkPermissions();

        // Register battery receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);

        // Initial update
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            updateBatteryInfo(batteryStatus);
        }
    }

    private void startBatteryMonitorService() {
        Intent serviceIntent = new Intent(this, BatteryMonitorService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        unregisterReceiver(batteryReceiver);
        uiHandler.removeCallbacks(uiUpdater);
    }

    private void checkPermissions() {
        // First check battery optimization
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String packageName = getPackageName();

        boolean askedBatteryOpt = prefs.getBoolean(KEY_ASKED_BATTERY_OPT, false);

        if (!powerManager.isIgnoringBatteryOptimizations(packageName) && !askedBatteryOpt) {
            showBatteryOptimizationDialog();
        } else {
            // Then check usage stats permission
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
            // After battery opt, check usage stats
            checkUsageStatsPermission();
        } else if (requestCode == REQUEST_USAGE_STATS) {
            if (hasUsageStatsPermission()) {
                Toast.makeText(this, "Usage statistics access granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some features will be limited", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateBatteryInfo(Intent intent) {
        long now = System.currentTimeMillis();
        // Get battery level
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;
        lastBatteryPct = batteryPct;

        // Track last real battery percentage value and time for fake smoothing
        if (lastRealBatteryPctForSmooth < 0f) {
            lastRealBatteryPctForSmooth = batteryPct;
            lastRealBatteryTimeMs = now;
        } else {
            // If OS-reported level changed by at least ~1%, reset the baseline
            if (Math.abs(batteryPct - lastRealBatteryPctForSmooth) >= 0.5f) {
                lastRealBatteryPctForSmooth = batteryPct;
                lastRealBatteryTimeMs = now;
            }
        }

        // Compute display percentage (optionally fake-smooth between integer steps)
        float displayPct = batteryPct;
        if (smoothPercentageEnabled && timePerPercentMs > 0.0 && batteryPct > 0f && batteryPct < 100f
                && lastRealBatteryTimeMs > 0L) {
            long elapsedSinceReal = now - lastRealBatteryTimeMs;
            if (elapsedSinceReal > 0L) {
                double extraPercent = elapsedSinceReal / timePerPercentMs;
                if (extraPercent < 0.0) extraPercent = 0.0;
                // Do not exceed almost the next integer step; keep within current + 0.99
                double maxExtra = 0.99;
                displayPct = (float) Math.min(batteryPct + extraPercent, batteryPct + maxExtra);
            }
        }

        // Update battery percentage as integer without decimals
        String formatted = String.format(Locale.US, "%.0f%%", displayPct);
        batteryPercentage.setText(formatted);

        long lastFullCharge = prefs.getLong(KEY_LAST_FULL_CHARGE, 0);

        // --- Deep Sleep Calculation (since >80% charge) ---
        long currentElapsed = SystemClock.elapsedRealtime();
        long currentDeep = currentElapsed - SystemClock.uptimeMillis();

        long baseDeep = prefs.getLong(KEY_BASE_DEEP_SLEEP, 0);
        long baseElapsed = prefs.getLong(KEY_BASE_DEEP_SLEEP_ELAPSED, 0);
        boolean wasFull = prefs.getBoolean(KEY_WAS_FULL, false);

        // Determine charging state
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

        // Cache latest charging measurements for toggle display
        lastIsCharging = isCharging;
        lastVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        lastCurrentMicroA = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
        lastWatts = computeWatts(lastVoltageMv, lastCurrentMicroA);

        // Track charge rate in % per hour while charging
        updateChargeRateEstimate(isCharging, batteryPct);

        // Kartın altındaki "Full in ..." metnini güncelle
        if (timeToFullText != null) {
            timeToFullText.setText(getTimeToFullText());
        }

        refreshChargingInfoText();

        boolean prevCharging = prefs.getBoolean(KEY_PREV_CHARGING, false);

        // If a new charging session starts
        if (isCharging && !prevCharging) {
            if (batteryPct >= 80) {
                // Already >=80% at plug-in: treat as reached >=80% now
                prefs.edit()
                        .putBoolean(KEY_WAS_FULL, true)
                        .putLong(KEY_BASE_DEEP_SLEEP, currentDeep)
                        .putLong(KEY_BASE_DEEP_SLEEP_ELAPSED, currentElapsed)
                        .apply();
                baseDeep = currentDeep;
                baseElapsed = currentElapsed;
                wasFull = true;
            } else {
                // New session below 80%
                prefs.edit().putBoolean(KEY_WAS_FULL, false).apply();
                wasFull = false;
            }
        }

        // Set baseline once per session when we first reach >=80% while charging
        if (batteryPct >= 80 && isCharging && !wasFull) {
            prefs.edit()
                    .putBoolean(KEY_WAS_FULL, true)
                    .putLong(KEY_BASE_DEEP_SLEEP, currentDeep)
                    .putLong(KEY_BASE_DEEP_SLEEP_ELAPSED, currentElapsed)
                    .apply();
            baseDeep = currentDeep;
            baseElapsed = currentElapsed;
            wasFull = true;
        }

        // If charging session ends (unplug) after reaching >=80%, record a new baseline time
        if (!isCharging && prevCharging && wasFull) {
            prefs.edit()
                    .putBoolean(KEY_WAS_FULL, false)
                    .putLong(KEY_LAST_FULL_CHARGE, now)
                    .putInt(KEY_CHARGE_START_LEVEL, Math.round(batteryPct))
                    .putLong(KEY_BASE_DEEP_SLEEP, currentDeep)
                    .putLong(KEY_BASE_DEEP_SLEEP_ELAPSED, currentElapsed)
                    .apply();
            baseDeep = currentDeep;
            baseElapsed = currentElapsed;
            wasFull = false;
            lastFullCharge = now; // update local copy so UI resets immediately
        }

        // Persist current charging state
        prefs.edit().putBoolean(KEY_PREV_CHARGING, isCharging).apply();

        // Update time since last >=80% charge (after possible baseline change)
        if (lastFullCharge > 0) {
            long timeDiff = System.currentTimeMillis() - lastFullCharge;
            String timeString = formatTimeDuration(timeDiff);
            timeSinceCharge.setText(timeString);

            // Update current cycle data
            dataManager.updateCurrentCycle(System.currentTimeMillis(), (int) batteryPct);
        } else {
            timeSinceCharge.setText("No data yet");
        }

        long deepSince80 = (baseDeep > 0) ? (currentDeep - baseDeep) : 0;
        long elapsedSince80 = (baseElapsed > 0) ? (currentElapsed - baseElapsed) : 0;

        // Guard against impossible values: deep sleep cannot exceed elapsed time since baseline
        if (deepSince80 > elapsedSince80) {
            deepSince80 = elapsedSince80;
        }

        if (deepSleepTime != null) {
            if (baseDeep <= 0) {
                // No baseline yet
                deepSleepTime.setText("No data yet");
            } else if (deepSince80 <= 0) {
                // Baseline exists but no deep sleep accumulated
                deepSleepTime.setText("0 minutes");
            } else {
                deepSleepTime.setText(formatTimeDuration(deepSince80));
            }
        }
        // --- End Deep Sleep Calculation ---

        // Calculate and update remaining time estimate using average drain rate
        if (remainingTime != null) {
            if (lastFullCharge > 0 && batteryPct < 100) {
                double avgDrainRate = dataManager.getAverageDrainRate();

                if (avgDrainRate > 0) {
                    double hoursRemaining = batteryPct / avgDrainRate;
                    long millisRemaining = (long) (hoursRemaining * 3600000);
                    String remainingString = formatTimeDuration(millisRemaining);
                    remainingTime.setText(remainingString);
                } else {
                    // Fallback to current cycle calculation if no historical data
                    long timeDiff = System.currentTimeMillis() - lastFullCharge;
                    int startLevel = prefs.getInt(KEY_CHARGE_START_LEVEL, 100);
                    float percentUsed = startLevel - batteryPct;

                    if (percentUsed > 0 && timeDiff > 0) {
                        double drainRatePerHour = (percentUsed / (timeDiff / 3600000.0));

                        if (drainRatePerHour > 0) {
                            double hoursRemaining = batteryPct / drainRatePerHour;
                            long millisRemaining = (long) (hoursRemaining * 3600000);
                            String remainingString = formatTimeDuration(millisRemaining);
                            remainingTime.setText(remainingString);
                        } else {
                            remainingTime.setText("Calculating...");
                        }
                    } else {
                        remainingTime.setText("Calculating...");
                    }
                }
            } else {
                remainingTime.setText("Not available");
            }
        }

    }



    private String formatTimeDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        // If less than 1 hour, show only minutes
        if (hours == 0) {
            if (minutes == 1) {
                return "1 minute";
            } else {
                return String.format("%d minutes", minutes);
            }
        }
        // If 1 hour or more, show hours and minutes
        else {
            if (minutes == 0) {
                // Exactly X hours
                if (hours == 1) {
                    return "1 hour";
                } else {
                    return String.format("%d hours", hours);
                }
            } else {
                // X hours and Y minutes
                String hourPart = (hours == 1) ? "1 hour" : String.format("%d hours", hours);
                String minutePart = (minutes == 1) ? "1 minute" : String.format("%d minutes", minutes);
                return String.format("%s and %s", hourPart, minutePart);
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

        if (chargingInfoMode == 0) { // W
            if (lastWatts > 0) {
                chargingInfo.setText(String.format("%.1f W", lastWatts));
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, LARGE_SP);
            } else {
                chargingInfo.setText("charging");
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP);
            }
        } else if (chargingInfoMode == 1) { // V
            if (lastVoltageMv > 0) {
                chargingInfo.setText(String.format("%.2f V", lastVoltageMv / 1000.0));
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, LARGE_SP);
            } else {
                chargingInfo.setText("N/A");
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP);
            }
        } else { // chargingInfoMode == 2 -> mA
            if (lastCurrentMicroA != 0) {
                chargingInfo.setText(String.format("%d mA", Math.round(Math.abs(lastCurrentMicroA) / 1000.0f)));
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, LARGE_SP);
            } else {
                chargingInfo.setText("N/A");
                chargingInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_SP);
            }
        }
    }

    // Track charge rate in % per hour while charging
    private void updateChargeRateEstimate(boolean isCharging, float batteryPct) {
        long now = System.currentTimeMillis();

        if (isCharging && batteryPct > 0f && batteryPct < 100f) {
            if (lastChargeSampleTime > 0 && lastChargeSampleLevel >= 0f) {
                long deltaMs = now - lastChargeSampleTime;
                float deltaLevel = batteryPct - lastChargeSampleLevel;

                // Sadece yeterli zaman geçtiyse ve yeterli seviye değişimi olduysa hesapla
                if (deltaMs >= MIN_TIME_BETWEEN_SAMPLES && deltaLevel >= MIN_LEVEL_CHANGE) {
                    double hours = deltaMs / 3600000.0;
                    double instantRate = deltaLevel / hours; // % per hour

                    // Makul olmayan değerleri filtrele (çok hızlı veya çok yavaş şarj)
                    // Örnek: %1-50 per hour arası kabul et
                    if (instantRate >= 1.0 && instantRate <= 50.0) {
                        if (avgChargeRatePerHour <= 0.0) {
                            avgChargeRatePerHour = instantRate;
                        } else {
                            // Smooth using exponential moving average
                            avgChargeRatePerHour = 0.6 * avgChargeRatePerHour + 0.4 * instantRate;
                        }
                    }

                    // ms-per-percent smoothing (for time to full)
                    double instantMsPerPercent = (double) deltaMs / deltaLevel; // ms per 1%

                    // Makul olmayan değerleri filtrele
                    // En az 30 saniye, en fazla 10 dakika per %1
                    final double MIN_MS_PER_PERCENT = 30_000.0; // 30 saniye
                    final double MAX_MS_PER_PERCENT = 600_000.0; // 10 dakika

                    if (instantMsPerPercent >= MIN_MS_PER_PERCENT &&
                            instantMsPerPercent <= MAX_MS_PER_PERCENT) {
                        if (timePerPercentMs <= 0.0) {
                            timePerPercentMs = instantMsPerPercent;
                        } else {
                            // Smooth the ms-per-percent
                            timePerPercentMs = 0.6 * timePerPercentMs + 0.4 * instantMsPerPercent;
                        }
                    }

                    // Ölçüm yapıldı, yeni baseline güncelle
                    lastChargeSampleTime = now;
                    lastChargeSampleLevel = batteryPct;
                }
                // Eğer yeterli değişim yoksa, sadece zamanı kontrol et
                // 5 dakikadan fazla veri yoksa, baseline'ı güncelle
                else if (deltaMs >= 300_000L) {
                    lastChargeSampleTime = now;
                    lastChargeSampleLevel = batteryPct;
                }
            } else {
                // İlk ölçüm
                lastChargeSampleTime = now;
                lastChargeSampleLevel = batteryPct;
            }
        } else {
            // Reset when not charging or already full
            lastChargeSampleTime = 0L;
            lastChargeSampleLevel = -1f;
            avgChargeRatePerHour = 0.0;
            timePerPercentMs = 0.0; // Bunu da sıfırlayın
            lastKnownTimeToFull = null; // Yeni oturumda baştan hesaplanacak
        }
    }

    // Returns a human-readable "time to full" while charging
    private String getTimeToFullText() {
        if (!lastIsCharging) return "";

        if (lastBatteryPct < 0f) {
            // If we don't yet have a valid reading, prefer the last known estimate if available
            return (lastKnownTimeToFull != null) ? lastKnownTimeToFull : "N/A";
        }
        if (lastBatteryPct >= 99.5f) {
            lastKnownTimeToFull = "Almost full";
            return lastKnownTimeToFull;
        }

        // Prefer ms-per-percent based estimate
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

            String result = sb.toString();
            lastKnownTimeToFull = result;
            return result;
        }

        // Fallback to %/hour based estimate
        if (avgChargeRatePerHour <= 0.0) {
            // If we don't have enough fresh data, keep showing the last known estimate
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

        // Reset if too much time has passed
        if (currentTime - firstTapTime > TAP_TIMEOUT) {
            tapCount = 1;
            firstTapTime = currentTime;
        } else {
            tapCount++;

            // Check if we reached the required taps
            if (tapCount >= REQUIRED_TAPS) {
                tapCount = 0;
                firstTapTime = 0;
                showThemeSelectionDialog();
            }
        }
    }

    private void showThemeSelectionDialog() {
        final String[] themes = {"System Default", "Light", "Dark"};
        int currentTheme = prefs.getInt("theme_preference", 0); // 0=System, 1=Light, 2=Dark

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
            case 0: // System Default
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case 1: // Light
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2: // Dark
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }

    private double getChargingWatts(Intent intent) {
        int voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1); // millivolts

        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int currentMicroA = 0;
        if (bm != null) {
            currentMicroA = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }

        if (voltageMv > 0 && currentMicroA != 0) {
            double volts = voltageMv / 1000.0;
            double amps = Math.abs(currentMicroA) / 1_000_000.0;
            return volts * amps;
        }
        return -1;
    }
}