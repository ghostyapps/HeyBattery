package com.example.batterystats;

public class ChargeCycle {
    public long fullChargeTimestamp;
    public int startLevel;
    public long durationMillis;
    public int endLevel;
    
    public ChargeCycle() {
        // Default constructor for JSON deserialization
    }
    
    public ChargeCycle(long fullChargeTimestamp, int startLevel) {
        this.fullChargeTimestamp = fullChargeTimestamp;
        this.startLevel = startLevel;
        this.durationMillis = 0;
        this.endLevel = startLevel;
    }
    
    public void updateEndData(long currentTime, int currentLevel) {
        this.durationMillis = currentTime - fullChargeTimestamp;
        this.endLevel = currentLevel;
    }
    
    public double getDrainRatePerHour() {
        if (durationMillis <= 0) return 0;
        double hoursElapsed = durationMillis / 3600000.0;
        double percentUsed = startLevel - endLevel;
        return percentUsed / hoursElapsed;
    }
}
