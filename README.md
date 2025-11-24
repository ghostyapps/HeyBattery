# HeyBattery

![Screenshot](HeyBattery_Screenshots_3.png)

A lightweight Android app that tracks your battery usage and charging behavior.

## Features
- Shows current battery percentage.
- Tracks time since last charge above 80%.
- Calculates deep sleep duration using *(elapsedRealtime – uptimeMillis)*.
- Estimates remaining usage time based on previous usage cycles.
- Displays real-time charging info.
- Tap charging info to cycle between **Watts → Volts → mA**.
- Dynamic refresh:
  - **3s** updates while charging
  - **60s** updates while discharging
- Clean UI with light/dark theme support.
- Scrollable About screen with detailed explanations.

---

## Permissions Used

### **1. android.permission.FOREGROUND_SERVICE**
Required so the app can continue receiving battery updates while it is open and active.  
HeyBattery does *not* run permanently in the background — only while the main screen is open.

### **2. Usage Access Permission (Requested by System UI)**
Some devices — especially Xiaomi, Redmi, POCO, Realme, Oppo — may prompt you to grant **Usage Access** when real-time battery or charging information is requested.

The app does **not** collect or transmit any data; the permission is needed only to read system-level battery statistics on certain Android builds.

### On some devices, you must manually enable:
Settings → Apps → Special Access → Usage Access → HeyBattery → Allow

### If Usage Access *cannot* be enabled normally
Some OEMs (Xiaomi/MIUI/HyperOS, some Oppo/Realme models) hide or block the toggle.  
In that case, try this alternative method:
Settings → Apps → HeyBattery → (⋮ menu in top-right corner) → Allow permission to change usage access

After enabling this internal toggle, return to:
Settings → Apps → Special Access → Usage Access → HeyBattery → Allow

Without this permission, some devices may return blocked or zero values for:
- Charging current (mA)
- Charging power (W)
- Deep sleep information

The app does not connect to the internet, does not collect any data, and no other permission is required to work.
