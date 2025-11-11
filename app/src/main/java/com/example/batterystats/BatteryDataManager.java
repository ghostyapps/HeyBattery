package com.example.batterystats;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class BatteryDataManager {
    private static final String FILENAME = "charge_cycles.json";
    private static final int MAX_CYCLES = 30; // Keep last 30 cycles
    
    private Context context;
    
    public BatteryDataManager(Context context) {
        this.context = context;
    }
    
    public List<ChargeCycle> loadChargeCycles() {
        List<ChargeCycle> cycles = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILENAME);
        
        if (!file.exists()) {
            return cycles;
        }
        
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            
            String json = new String(data, "UTF-8");
            JSONArray jsonArray = new JSONArray(json);
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                ChargeCycle cycle = new ChargeCycle();
                cycle.fullChargeTimestamp = obj.getLong("fullChargeTimestamp");
                cycle.startLevel = obj.getInt("startLevel");
                cycle.durationMillis = obj.getLong("durationMillis");
                cycle.endLevel = obj.getInt("endLevel");
                cycles.add(cycle);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return cycles;
    }
    
    public void saveChargeCycles(List<ChargeCycle> cycles) {
        try {
            // Keep only the most recent cycles
            List<ChargeCycle> cyclesToSave = cycles;
            if (cycles.size() > MAX_CYCLES) {
                cyclesToSave = cycles.subList(cycles.size() - MAX_CYCLES, cycles.size());
            }
            
            JSONArray jsonArray = new JSONArray();
            for (ChargeCycle cycle : cyclesToSave) {
                JSONObject obj = new JSONObject();
                obj.put("fullChargeTimestamp", cycle.fullChargeTimestamp);
                obj.put("startLevel", cycle.startLevel);
                obj.put("durationMillis", cycle.durationMillis);
                obj.put("endLevel", cycle.endLevel);
                jsonArray.put(obj);
            }
            
            String json = jsonArray.toString(2); // Pretty print with indent
            
            File file = new File(context.getFilesDir(), FILENAME);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes("UTF-8"));
            fos.flush(); // Ensure data is written to disk
            fos.getFD().sync(); // Force sync to storage
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void addChargeCycle(ChargeCycle cycle) {
        List<ChargeCycle> cycles = loadChargeCycles();
        cycles.add(cycle);
        saveChargeCycles(cycles);
    }
    
    public void updateCurrentCycle(long currentTime, int currentLevel) {
        List<ChargeCycle> cycles = loadChargeCycles();
        if (!cycles.isEmpty()) {
            ChargeCycle lastCycle = cycles.get(cycles.size() - 1);
            lastCycle.updateEndData(currentTime, currentLevel);
            saveChargeCycles(cycles);
        }
    }
    
    public double getAverageDrainRate() {
        List<ChargeCycle> cycles = loadChargeCycles();
        if (cycles.isEmpty()) {
            return 0;
        }
        
        double totalDrainRate = 0;
        int validCycles = 0;
        
        // Use last 10 cycles for average (or all if less than 10)
        int startIndex = Math.max(0, cycles.size() - 10);
        
        for (int i = startIndex; i < cycles.size(); i++) {
            ChargeCycle cycle = cycles.get(i);
            double drainRate = cycle.getDrainRatePerHour();
            if (drainRate > 0 && drainRate < 50) { // Sanity check
                totalDrainRate += drainRate;
                validCycles++;
            }
        }
        
        return validCycles > 0 ? totalDrainRate / validCycles : 0;
    }
}
