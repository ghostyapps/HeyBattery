package com.example.batterystats;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private TextView batteryPercentage;
    private TextView timeSinceCharge;
    private TextView remainingTime;
    private TextView batteryHealth;
    private TextView greetingText;
    
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
        
        setContentView(R.layout.activity_main);

        batteryPercentage = findViewById(R.id.batteryPercentage);
        timeSinceCharge = findViewById(R.id.timeSinceCharge);
        remainingTime = findViewById(R.id.remainingTime);
        batteryHealth = findViewById(R.id.batteryHealth);
        greetingText = findViewById(R.id.greetingText);
        
        dataManager = new BatteryDataManager(this);
        
        // Set up easter egg tap listener
        greetingText.setOnClickListener(v -> handleGreetingTap());
        
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
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
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
        // Get battery level
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;
        
        // Update battery percentage
        batteryPercentage.setText(String.format("%.0f%%", batteryPct));
        
        // Update time since last full charge
        long lastFullCharge = prefs.getLong(KEY_LAST_FULL_CHARGE, 0);
        if (lastFullCharge > 0) {
            long timeDiff = System.currentTimeMillis() - lastFullCharge;
            String timeString = formatTimeDuration(timeDiff);
            timeSinceCharge.setText(timeString);
            
            // Update current cycle data
            dataManager.updateCurrentCycle(System.currentTimeMillis(), (int) batteryPct);
        } else {
            timeSinceCharge.setText("No data yet");
        }
        
        // Calculate and update remaining time estimate using average drain rate
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
        
        // Update battery health
        int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        String healthString = getBatteryHealthString(health);
        batteryHealth.setText(healthString + ".");
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
    
    private String getBatteryHealthString(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "Over Voltage";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "Cold";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "Unspecified Failure";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN:
                return "Unknown";
            default:
                return "Unknown (" + health + ")";
        }
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
}
