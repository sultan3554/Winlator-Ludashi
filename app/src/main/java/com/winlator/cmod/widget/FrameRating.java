package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {

    private long lastCpuTime = 0;
    private long lastCpuTotal = 0;
    private long lastCpuIdle = 0;
    private int lastCpuUsage = 0;

    private long lastGpuTime = 0;
    private long lastGpuBusy = 0;
    private long lastGpuTotal = 0;
    private int lastGpuUsage = 0;

    private Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;

    private String totalRAM = null;
    private final TextView tvFPS;
    private final TextView xgpu;
    private final TextView xcpu;
    private final TextView tvBattery;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;

    private HashMap graphicsDriverConfig;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        this.context = context;

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);

        tvFPS = view.findViewById(R.id.TVFPS);
        xgpu = view.findViewById(R.id.TVGPUused);
        xcpu = view.findViewById(R.id.TVCPUused);
        tvBattery = view.findViewById(R.id.TVBattery);
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("OpenGL");
        tvGPU = view.findViewById(R.id.TVGPU);
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));

        tvRAM = view.findViewById(R.id.TVRAM);
        totalRAM = getTotalRAM();

        this.graphicsDriverConfig = graphicsDriverConfig;

        addView(view);
    }

    private String getTotalRAM() {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return StringUtils.formatBytes(memoryInfo.totalMem);
    }

    private String getAvailableRAM() {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        return StringUtils.formatBytes(usedMem, false);
    }

    // CPU ===============================================================
    private String getCPUUsagePercentageProcStat() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();

            if (line != null && line.startsWith("cpu ")) {
                String[] t = line.split("\\s+");
                if (t.length >= 8) {
                    long user = Long.parseLong(t[1]);
                    long nice = Long.parseLong(t[2]);
                    long system = Long.parseLong(t[3]);
                    long idle = Long.parseLong(t[4]);
                    long iowait = Long.parseLong(t[5]);
                    long irq = Long.parseLong(t[6]);
                    long softirq = Long.parseLong(t[7]);

                    long currentIdle = idle + iowait;
                    long currentTotal = user + nice + system + idle + iowait + irq + softirq;
                    long currentTime = SystemClock.elapsedRealtime();

                    if (lastCpuTime > 0) {
                        long deltaTotal = currentTotal - lastCpuTotal;
                        long deltaIdle = currentIdle - lastCpuIdle;

                        if (deltaTotal > 0) {
                            int usage = (int) ((deltaTotal - deltaIdle) * 100L / deltaTotal);
                            usage = Math.max(0, Math.min(100, usage));

                            int smooth = (int) (0.3f * usage + 0.7f * lastCpuUsage);
                            lastCpuUsage = smooth;
                        }
                    } else {
                        int usage = (int) ((currentTotal - currentIdle) * 100L / currentTotal);
                        lastCpuUsage = usage;
                    }

                    lastCpuTotal = currentTotal;
                    lastCpuIdle = currentIdle;
                    lastCpuTime = currentTime;

                    return lastCpuUsage + "%";
                }
            }
        } catch (Exception ignored) {}
        return lastCpuUsage + "%";
    }

    private String getCPUUsagePercentageDVFS() {
        try {
            short[] current = CPUStatus.getCurrentClockSpeeds();
            long sumPct = 0;
            int cores = 0;

            for (int i = 0; i < current.length; i++) {
                short max = CPUStatus.getMaxClockSpeed(i);
                if (max > 0) {
                    int pct = (current[i] * 100) / max;
                    sumPct += pct;
                    cores++;
                }
            }

            if (cores > 0) {
                int usage = (int) (sumPct / cores);
                int smooth = (int) (0.4f * usage + 0.6f * lastCpuUsage);
                lastCpuUsage = smooth;
                return smooth + "%";
            }
        } catch (Exception ignored) {}
        return getCPUUsagePercentageProcStat();
    }

    // GPU ===============================================================
    private String getGPUUsage() {
        long currentTime = SystemClock.elapsedRealtime();

        try {
            File adreno = new File("/sys/class/kgsl/kgsl-3d0/gpubusy");
            if (adreno.exists()) {
                String line = readFirstLine(adreno);
                if (line != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) {
                        long busy = Long.parseLong(p[0]);
                        long total = Long.parseLong(p[1]);

                        int usage;

                        if (lastGpuTotal > 0) {
                            long dBusy = busy - lastGpuBusy;
                            long dTotal = total - lastGpuTotal;
                            usage = (int) ((dBusy * 100L) / dTotal);
                        } else {
                            usage = (int) ((busy * 100L) / total);
                        }

                        usage = Math.max(0, Math.min(100, usage));
                        usage = (int) (0.4f * usage + 0.6f * lastGpuUsage);

                        lastGpuUsage = usage;
                        lastGpuTime = currentTime;
                        lastGpuBusy = busy;
                        lastGpuTotal = total;

                        return usage + "%";
                    }
                }
            }
        } catch (Exception ignored) {}

        if (lastGpuUsage > 0 && currentTime - lastGpuTime < 5000)
            return lastGpuUsage + "%";

        return "0%";
    }

    private String readFirstLine(File f) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            return br.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class CPUStatus {
        private static String readLine(String path) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                return br.readLine();
            } catch (Exception e) {
                return null;
            }
        }

        public static int getCoreCount() {
            try {
                return new File("/sys/devices/system/cpu/")
                        .listFiles((f, n) -> n.matches("cpu[0-9]+")).length;
            } catch (Exception e) {
                return Runtime.getRuntime().availableProcessors();
            }
        }

        public static short[] getCurrentClockSpeeds() {
            int cores = getCoreCount();
            short[] clocks = new short[cores];

            for (int i = 0; i < cores; i++) {
                String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
                String line = readLine(path);

                if (line != null) {
                    try {
                        clocks[i] = (short) (Integer.parseInt(line.trim()) / 1000);
                        continue;
                    } catch (Exception ignored) {}
                }

                clocks[i] = 0;
            }

            return clocks;
        }

        public static short getMaxClockSpeed(int core) {
            String path = "/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq";
            String line = readLine(path);

            if (line != null) {
                try {
                    return (short) (Integer.parseInt(line.trim()) / 1000);
                } catch (Exception ignored) {}
            }

            return 1;
        }
    }

    // ================================
    // BATTERY — WITHOUT WATT
    // ================================
    private String getBatteryInfo() {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent bs = context.registerReceiver(null, filter);

            int level = bs.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bs.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float pct = (level * 100f) / scale;

            int temp = bs.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            float cTemp = temp / 10f;

            int status = bs.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            if (charging) {
                return String.format(Locale.ENGLISH,
                        "%d%% | Chg | %d°C", (int)pct, (int)cTemp);
            } else {
                return String.format(Locale.ENGLISH,
                        "%d%% | Dis | %d°C", (int)pct, (int)cTemp);
            }

        } catch (Exception e) {
            return "0% | Dis | 0°C";
        }
    }

    private int getBatteryColor(String info) {
        info = info.toLowerCase();

        if (info.contains("chg")) return 0xFF00FF00;
        if (info.contains("dis")) return 0xFFFF4444;

        return 0xFFFFFFFF;
    }

    // ================================
    // UI UPDATE
    // ================================

    public void setRenderer(String renderer) {
        tvRenderer.setText(renderer);
    }

    public void setGpuName (String gpuName) {
        tvGPU.setText(gpuName);
    }

    
    public void reset() {
        tvRenderer.setText("OpenGL");
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
    }


    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();

        long now = SystemClock.elapsedRealtime();

        if (now >= lastTime + 500) {
            lastFPS = ((float) frameCount * 1000f) / (now - lastTime);
            post(this);
            frameCount = 0;
            lastTime = now;
        }

        frameCount++;
    }

    @Override
    public void run() {
        if (getVisibility() == GONE)
            setVisibility(VISIBLE);

        tvFPS.setText(String.valueOf(Math.round(lastFPS)));
        String cpu = getCPUUsagePercentageDVFS();
        String gpu = getGPUUsage();
        String battery = getBatteryInfo();

        xcpu.setText(cpu);
        xgpu.setText(gpu);

        tvBattery.setText(battery);
        tvBattery.setTextColor(getBatteryColor(battery));

        tvRAM.setText(getAvailableRAM() + " GB Used / " + totalRAM + " Total");
    }
    }
