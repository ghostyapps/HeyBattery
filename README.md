# Battery Stats App

A simple Android battery statistics application that displays:
- Current battery percentage
- Time elapsed since last full charge
- Estimated remaining battery time
- Battery health status

## Features

- Clean, text-focused interface matching the design from HeyBattery
- Real-time battery monitoring
- Automatic tracking of full charge cycles
- Battery drain rate calculation for time estimates
- Battery health status display

## Requirements

- Android Studio (Arctic Fox or newer)
- Android SDK 24 or higher (Android 7.0+)
- Java 8 or higher

## Setup Instructions

1. **Open in Android Studio:**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the `BatteryStats` folder and select it

2. **Fix Gradle Wrapper (if needed):**
   If you see a Gradle sync error, do the following:
   - Open Terminal in Android Studio (View → Tool Windows → Terminal)
   - Run this command:
     - **Mac/Linux:** `./gradlew wrapper --gradle-version 8.0`
     - **Windows:** `gradlew.bat wrapper --gradle-version 8.0`
   - If that doesn't work, click "Try Again" in the error bar and Android Studio will download Gradle automatically
   - Alternatively, go to File → Project Structure → Project, and set Gradle version to 8.0

3. **Build the Project:**
   - Once Gradle sync completes successfully
   - Click on "Build" in the menu bar
   - Select "Make Project" or press `Ctrl+F9` (Windows/Linux) or `Cmd+F9` (Mac)

4. **Run on Device/Emulator:**
   - Connect an Android device via USB with USB debugging enabled, or start an Android emulator
   - Click the green "Run" button or press `Shift+F10` (Windows/Linux) or `Ctrl+R` (Mac)
   - Select your device from the list

## How It Works

### Battery Tracking
- The app monitors battery status using Android's `BatteryManager`
- When the battery reaches 99-100% while charging, it records this as a "full charge"
- The timestamp is saved using SharedPreferences for persistence

### Time Calculations
- **Time Since Charge:** Calculated from the saved full charge timestamp
- **Remaining Time:** Estimated based on current battery drain rate
  - Drain rate = (Battery used) / (Time elapsed)
  - Remaining time = (Current battery) / (Drain rate)

### Battery Health
The app displays battery health status from Android system:
- Good
- Overheating
- Dead
- Over Voltage
- Cold
- Unknown

## File Structure

```
BatteryStats/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/batterystats/
│   │       │   └── MainActivity.java          # Main activity with battery logic
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml     # UI layout
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       ├── colors.xml
│   │       │       └── themes.xml
│   │       └── AndroidManifest.xml
│   ├── build.gradle                           # App-level Gradle config
│   └── proguard-rules.pro
├── build.gradle                               # Project-level Gradle config
├── settings.gradle
└── gradle.properties
```

## Notes

- The app needs to observe at least one full charge cycle to start tracking accurately
- Battery time estimates improve in accuracy after a few charge/discharge cycles
- The app updates battery information in real-time while running
- Battery health information is provided by the Android system and may vary by device

## Customization

You can easily customize:
- **Colors:** Edit `res/values/colors.xml`
- **Text sizes:** Modify `textSize` attributes in `activity_main.xml`
- **App name:** Change in `res/values/strings.xml`
- **Theme:** Adjust in `res/values/themes.xml`

## Future Enhancements

Potential features for future versions:
- Historical battery usage graphs
- Notifications for low battery
- Widget support
- Dark mode toggle
- Battery usage by app
- Charging speed detection
