# Galaxy Watch4 Bio-Hub & Raw Multi-Wavelength PPG Engine

A high-performance, real-time physiological signal processing engine and research-grade telemetry suite for the **Samsung Galaxy Watch4** (Wear OS / One UI Watch).

The system captures multi-wavelength optical photoplethysmography (PPG: **Green 530 nm, Red 660 nm, Infrared 940 nm**) synchronized with a **3-axis IMU Accelerometer**, cancels wrist motion artifacts in real time using an adaptive Normalized Least Mean Squares (**NLMS**) filter, computes clinical cardiopulmonary vitals and autonomic Heart Rate Variability (**HRV**) metrics, continuously logs data in the background, synchronizes with **Android Health Connect** and **Samsung Health**, and visualizes the complete physiological telemetry stream on a multi-panel **Seaborn** live dashboard on PC using absolute wall-clock time.

---

## Key Features

### 1. Watch-Side Real-Time Physiological DSP Engine
- **Zero-Allocation $O(1)$ Hot Loop**: Pre-allocated circular buffers (`FloatArray`, `IntArray`, `LongArray`) executing on a dedicated high-priority background DSP thread (`PpgDspThread`) to guarantee 60 FPS UI performance with minimal battery drain.
- **4-Tap NLMS Adaptive Motion Artifact Filter**: Uses dynamic 3-axis IMU acceleration magnitude $\|a(t)\| - g_0$ as reference noise to cancel wrist motion artifacts from raw optical PPG signals before cardiac filtering.
- **Cascaded Biquad Bandpass Filter (0.5 Hz – 4.0 Hz / 30–240 BPM)**: High-pass DC-blocker combined with a 2nd-order Butterworth low-pass filter with online sampling rate estimation.
- **Multi-Wavelength $SpO_2$ Estimator**: Computes rolling AC RMS and DC baselines across Red ($660\text{ nm}$) and IR ($940\text{ nm}$) channels to calculate the Beer–Lambert ratio-of-ratios:
  $$R = \frac{AC_{\text{red}} / DC_{\text{red}}}{AC_{\text{ir}} / DC_{\text{ir}}}$$
  with quadratic empirical calibration ($SpO_2 = 108.5 - 18.0 R - 3.5 R^2$).
- **Perfusion Index ($PI\%$)**: Real-time peripheral pulse strength indicator:
  $$PI\% = \frac{AC_{\text{ppg}}}{|DC_{\text{ppg}}|} \times 100\%$$
- **Autonomic HRV & Stress Analysis**:
  - Outlier-rejected Inter-Beat Intervals (**IBI**) via an adaptive refractory systolic peak detector with parabolic 3-point sub-millisecond interpolation.
  - **SDNN** (Standard Deviation of NN intervals in ms).
  - **RMSSD** (Root Mean Square of Successive Differences in ms, reflecting parasympathetic vagal tone).
  - **pNN50** (Percentage of successive intervals differing by $>50\text{ ms}$).
  - **Baevsky Stress Index ($SI$)**: Sympathetic nervous system activation index calculated from interval histogram mode amplitude and variability range:
    $$SI = \frac{AMo(\%)}{2 \cdot Mo \cdot MxDMn}$$
- **Respiratory Rate ($brpm$)**: Extracted from Respiratory-Induced Intensity Variation (RIIV) using a dual-EMA bandpass filter ($0.12\text{ Hz} – 0.45\text{ Hz}$, $7 – 27\text{ brpm}$) and zero-crossing detection.
- **Wrist Skin Temperature**: Subscribes to wrist object and ambient temperature sensors when supported by the hardware.

### 2. Background Foreground Service & Health Sync
- **Persistent Android Foreground Service (`foregroundServiceType="health"`)**:
  - Acquires a partial `WakeLock` to record without interruption even when the watch screen enters ambient mode or turns off.
  - High-speed buffered local CSV logging to `/sdcard/Android/data/com.example.watch4rawppg/files/watch_health_session.csv`.
- **Android Health Connect & Samsung Health Integration**:
  - Uses Android's native `HealthConnectManager` to continuously publish validated records in 12-second batches:
    - `HeartRateRecord` (with all individual beat samples)
    - `OxygenSaturationRecord` ($SpO_2$)
    - `HeartRateVariabilityRmssdRecord` (RMSSD)
    - `RespiratoryRateRecord` (breaths/min)
    - `SkinTemperatureRecord` (wrist skin temperature)

### 3. Minimalist 60-FPS Wear OS Compose Interface
- Pure-black AMOLED layout with throttled 4 Hz UI state updates displaying:
  - Primary Vitals: Heart Rate (BPM), $SpO_2$ (%), Respiratory Rate (brpm), Perfusion Index (PI %).
  - Autonomic Card: IBI (ms), RMSSD (ms), SDNN (ms), pNN50 (%), Baevsky Stress Index (SI).
  - Raw Sensors: Green, Red, and IR ADC channels, Filtered PPG, 3-Axis Accel ($g$), and NLMS filter status.
  - Sync Status: Sampling Rate (Hz), Total sample count, and Health Connect published records.

### 4. Real-Time Multi-Subplot Seaborn Dashboard (`live_ppg_plot.py`)
- **Absolute Wall-Clock Time**: Plots all time series using real timestamps (`HH:MM:SS`) on the x-axis rather than relative time.
- **4 Synchronized Panels** with distinct Seaborn color palettes:
  1. **Optical PPG Waveforms (Crest / Neon)**: NLMS motion-compensated filtered PPG, raw Green/Red/IR, systolic peak markers, and live SNR.
  2. **Cardiopulmonary Vitals (Rocket)**: Heart Rate (BPM), $SpO_2$ (%), and Respiratory Rate (brpm).
  3. **HRV & Autonomic Stress (Viridis)**: Beat-to-beat IBI tachogram, rolling RMSSD, SDNN, and Baevsky Stress Index.
  4. **3-Axis IMU & Motion State (Mako)**: Accel $X, Y, Z$ ($g$), dynamic magnitude $|a|$, and motion artifact detection state.
- **Live Legends**: Include comprehensive numerical metrics updated on every animation frame without flickering.

---

## Repository Structure

```
├── app/
│   ├── libs/
│   │   └── samsung-health-sensor-api-1.4.1.aar  # Samsung Health Sensor Privileged SDK
│   ├── src/main/
│   │   ├── AndroidManifest.xml                 # Health permissions, foreground service, Health Connect
│   │   ├── java/com/example/watch4rawppg/
│   │   │   ├── health/
│   │   │   │   └── HealthConnectPublisher.kt   # Health Connect & Samsung Health batch publisher
│   │   │   ├── presentation/
│   │   │   │   ├── theme/                      # Wear OS Material3 theme
│   │   │   │   └── MainActivity.kt             # Minimalist 60-FPS Wear OS Compose dashboard
│   │   │   ├── processing/
│   │   │   │   └── PpgSignalProcessor.kt       # O(1) NLMS filter, biquad IIR, SpO2, HRV, Stress, Resp
│   │   │   └── service/
│   │   │       └── HealthSensorService.kt      # Foreground service, sensor listener, local CSV logger
│   │   └── res/                                # Launcher icons, strings, splash theme
│   └── build.gradle.kts                        # Wear OS Compose, Health Connect, Kotlin 2.x
├── gradle/                                     # Gradle wrapper & version catalogs
├── live_ppg_plot.py                            # Real-time multi-panel Seaborn live dashboard
└── README.md
```

---

## Setup & Getting Started

### 1. Enable Wireless Debugging on Galaxy Watch4
1. On your watch, navigate to **Settings > About watch > Software info**.
2. Tap **Software version** 7 times to enable **Developer mode**.
3. Go back to **Settings > Developer options**:
   - Turn **ADB debugging** -> **ON**.
   - Turn **Wireless debugging** -> **ON** (ensure watch and PC are on the same Wi-Fi network).
4. Tap **Wireless debugging > Pair new device** and note the IP, pairing port, and 6-digit code.

### 2. Connect via ADB
```bash
adb pair <WATCH_IP>:<PAIRING_PORT> <6_DIGIT_CODE>
adb connect <WATCH_IP>:<CONNECTION_PORT>
adb devices
```

### 3. Build & Install Watch Application
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant runtime sensor and Health Connect permissions:
```bash
for perm in \
  com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA \
  android.permission.BODY_SENSORS \
  android.permission.BODY_SENSORS_BACKGROUND \
  android.permission.ACTIVITY_RECOGNITION \
  android.permission.POST_NOTIFICATIONS \
  android.permission.health.READ_HEART_RATE \
  android.permission.health.WRITE_HEART_RATE \
  android.permission.health.READ_OXYGEN_SATURATION \
  android.permission.health.WRITE_OXYGEN_SATURATION \
  android.permission.health.WRITE_HEART_RATE_VARIABILITY \
  android.permission.health.WRITE_RESPIRATORY_RATE \
  android.permission.health.READ_SKIN_TEMPERATURE \
  android.permission.health.WRITE_SKIN_TEMPERATURE; do
  adb shell pm grant com.example.watch4rawppg "$perm" 2>/dev/null || true
done
```

### 4. Launch & Start Streaming
1. Open **WATCH4 BIO-HUB** on your watch and tap **Start**.
2. Run the real-time Seaborn dashboard on your PC:
   ```bash
   pip install matplotlib seaborn scipy numpy
   python3 live_ppg_plot.py
   ```

---

## License

MIT License. Developed for research and physiological data acquisition on Wear OS and Samsung Health Sensor API.
