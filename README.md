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

## How It Works

### Battery Tracking
- The app monitors battery status using Android's `BatteryManager`
- When the battery reaches 80-100% while charging, it records this as a "full charge"
- The timestamp is saved using SharedPreferences for persistence

### Time Calculations
- **Time Since Charge:** Calculated from the saved full charge timestamp
- **Remaining Time:** Estimated based on current battery drain rate
  - Drain rate = (Battery used) / (Time elapsed)
  - Remaining time = (Current battery) / (Drain rate)


## Notes

- The app needs to observe at least one full charge cycle to start tracking accurately
- Battery time estimates improve in accuracy after a few charge/discharge cycles
- The app updates battery information in real-time while running


## Customization

You can easily customize:
- **Colors:** Edit `res/values/colors.xml`
- **Text sizes:** Modify `textSize` attributes in `activity_main.xml`
- **App name:** Change in `res/values/strings.xml`
- **Theme:** Adjust in `res/values/themes.xml`
