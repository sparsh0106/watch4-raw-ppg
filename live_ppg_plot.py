import csv
import re
import subprocess
import sys
import threading
from collections import deque
from datetime import datetime
from queue import Empty, Queue

import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns
from matplotlib.animation import FuncAnimation
from scipy import signal

# Parse both full extended telemetry lines and legacy 3-field PPG_GREEN lines
EXTENDED_LOG_PATTERN = re.compile(
    r"PPG_GREEN,timestamp=(\d+),value=(-?\d+),status=(-?\d+)"
    r"(?:,red=(-?\d+),ir=(-?\d+),filt=([-\d.]+),"
    r"ax=([-\d.]+),ay=([-\d.]+),az=([-\d.]+),amag=([-\d.]+),"
    r"hr=([-\d.]+),spo2=([-\d.]+),r=([-\d.]+),pi=([-\d.]+),"
    r"ibi=(-?\d+),sdnn=([-\d.]+),rmssd=([-\d.]+),pnn50=([-\d.]+),"
    r"si=([-\d.]+),resp=([-\d.]+),stemp=([-\d.]+),atemp=([-\d.]+),fs=([-\d.]+))?"
)

WINDOW_SECONDS = 12.0


def get_adb_device_args():
    if len(sys.argv) > 1 and not sys.argv[1].startswith("-"):
        return ["-s", sys.argv[1]]

    result = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, check=True
    )
    devices = [
        line.split()[0]
        for line in result.stdout.strip().splitlines()[1:]
        if "\tdevice" in line
    ]

    if not devices:
        print(
            "Error: No ADB devices found. Ensure your Galaxy Watch4 is connected.",
            file=sys.stderr,
        )
        sys.exit(1)

    target = next((d for d in devices if ":" in d), devices[0])
    if len(devices) > 1:
        print(f"Multiple ADB handles detected: {devices}. Using target: {target}")
    return ["-s", target]


adb_target_args = get_adb_device_args()
data_queue = Queue()


def read_logcat(proc):
    for line in proc.stdout:
        match = EXTENDED_LOG_PATTERN.search(line)
        if not match:
            continue
        groups = match.groups()
        ts_ms = int(groups[0])
        green = int(groups[1])
        status = int(groups[2])

        if groups[3] is not None:
            red = int(groups[3])
            ir = int(groups[4])
            filt = float(groups[5])
            ax = float(groups[6])
            ay = float(groups[7])
            az = float(groups[8])
            amag = float(groups[9])
            hr = float(groups[10])
            spo2 = float(groups[11])
            r_ratio = float(groups[12])
            pi_pct = float(groups[13])
            ibi = int(groups[14])
            sdnn = float(groups[15])
            rmssd = float(groups[16])
            pnn50 = float(groups[17])
            si = float(groups[18])
            resp = float(groups[19])
            stemp = float(groups[20])
            atemp = float(groups[21])
            fs = float(groups[22])
        else:
            red, ir, filt = 0, 0, 0.0
            ax, ay, az, amag = 0.0, 0.0, 1.0, 1.0
            hr, spo2, r_ratio, pi_pct = 0.0, 0.0, 0.0, 0.0
            ibi, sdnn, rmssd, pnn50 = 0, 0.0, 0.0, 0.0
            si, resp, stemp, atemp, fs = 0.0, 0.0, 0.0, 0.0, 100.0

        data_queue.put(
            (
                ts_ms,
                green,
                status,
                red,
                ir,
                filt,
                ax,
                ay,
                az,
                amag,
                hr,
                spo2,
                r_ratio,
                pi_pct,
                ibi,
                sdnn,
                rmssd,
                pnn50,
                si,
                resp,
                stemp,
                atemp,
                fs,
            )
        )


subprocess.run(["adb", *adb_target_args, "logcat", "-c"], check=True)

process = subprocess.Popen(
    ["adb", *adb_target_args, "logcat", "-s", "RawGreenPPG:I"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
)

thread = threading.Thread(target=read_logcat, args=(process,), daemon=True)
thread.start()

csv_file = open("ppg_samples.csv", "w", newline="", encoding="utf-8")
writer = csv.writer(csv_file)
writer.writerow(
    [
        "timestamp_ms",
        "iso_time",
        "green_adc",
        "red_adc",
        "ir_adc",
        "filtered_ppg",
        "accel_x_g",
        "accel_y_g",
        "accel_z_g",
        "accel_mag_g",
        "hr_bpm",
        "spo2_pct",
        "r_ratio",
        "pi_pct",
        "ibi_ms",
        "sdnn_ms",
        "rmssd_ms",
        "pnn50_pct",
        "stress_index",
        "resp_brpm",
        "skin_temp_c",
        "ambient_temp_c",
        "sample_rate_hz",
        "status",
    ]
)

# ---------------------------------------------------------------------------
# Configure Seaborn Theme & Distinct Multi-Subplot Color Palettes
# ---------------------------------------------------------------------------
sns.set_theme(
    style="darkgrid",
    rc={
        "figure.facecolor": "#0b0f19",
        "axes.facecolor": "#111827",
        "axes.edgecolor": "#374151",
        "axes.labelcolor": "#e5e7eb",
        "grid.color": "#1f2937",
        "grid.linestyle": "--",
        "grid.alpha": 0.6,
        "text.color": "#f9fafb",
        "xtick.color": "#9ca3af",
        "ytick.color": "#9ca3af",
        "legend.facecolor": "#1f2937",
        "legend.edgecolor": "#4b5563",
        "legend.fontsize": 8.5,
    },
)

pal_ppg = sns.color_palette("crest", 4)
pal_vitals = sns.color_palette("rocket_r", 4)
pal_hrv = sns.color_palette("viridis", 4)
pal_imu = sns.color_palette("mako", 4)

fig, axes = plt.subplots(2, 2, figsize=(16, 9.5), sharex=False)
fig.canvas.manager.set_window_title(
    "Galaxy Watch4 — Real-Time Multi-Sensor Bio-Telemetry Dashboard (Seaborn)"
)
fig.suptitle(
    "Galaxy Watch4 Bio-Hub — Absolute-Time Multi-Wavelength PPG, Vitals, HRV & IMU",
    fontsize=13,
    fontweight="bold",
    color="#34d399",
    y=0.98,
)

(ax_ppg, ax_vitals), (ax_hrv, ax_imu) = axes
time_formatter = mdates.DateFormatter("%H:%M:%S")

# --- Subplot 1: Multi-Wavelength PPG & NLMS Filtered Waveform (Crest/Neon Palette) ---
ax_ppg.set_title(
    "1. Optical PPG Waveforms (NLMS Motion-Cleaned + Raw Green/Red/IR)",
    fontsize=10.5,
    fontweight="bold",
    color="#6ee7b7",
)
ax_ppg.set_xlabel("Absolute Wall-Clock Time (HH:MM:SS)")
ax_ppg.set_ylabel("Amplitude (Zero-Mean ADC)")
ax_ppg.xaxis.set_major_formatter(time_formatter)

(line_filt_ppg,) = ax_ppg.plot(
    [], [], color="#10b981", linewidth=1.8, label="NLMS Filtered PPG: waiting..."
)
(line_green_ac,) = ax_ppg.plot(
    [],
    [],
    color=pal_ppg[0],
    linewidth=1.0,
    alpha=0.55,
    label="Green ADC (530nm): waiting...",
)
(line_red_ir_ac,) = ax_ppg.plot(
    [],
    [],
    color="#f43f5e",
    linewidth=1.0,
    alpha=0.65,
    linestyle=":",
    label="Red/IR ADC (660/940nm): waiting...",
)
(line_peaks,) = ax_ppg.plot(
    [],
    [],
    "o",
    color="#facc15",
    markersize=5,
    label="Systolic Peaks: waiting...",
)
leg_ppg = ax_ppg.legend(loc="upper left", framealpha=0.88)

# --- Subplot 2: Cardiopulmonary Vitals (Rocket Palette) ---
ax_vitals.set_title(
    "2. Cardiopulmonary Vitals (Heart Rate BPM, SpO2 %, Respiration brpm)",
    fontsize=10.5,
    fontweight="bold",
    color="#fda4af",
)
ax_vitals.set_xlabel("Absolute Wall-Clock Time (HH:MM:SS)")
ax_vitals.set_ylabel("BPM / SpO2 (%) / Resp (brpm)")
ax_vitals.xaxis.set_major_formatter(time_formatter)

(line_hr,) = ax_vitals.plot(
    [], [], color="#fb7185", linewidth=2.0, label="Heart Rate: waiting..."
)
(line_spo2,) = ax_vitals.plot(
    [], [], color="#38bdf8", linewidth=1.8, label="Blood Oxygen SpO2: waiting..."
)
(line_resp,) = ax_vitals.plot(
    [],
    [],
    color="#a78bfa",
    linewidth=1.6,
    linestyle="--",
    label="Respiratory Rate: waiting...",
)
leg_vitals = ax_vitals.legend(loc="upper left", framealpha=0.88)

# --- Subplot 3: HRV Tachogram & Autonomic Stress (Viridis Palette) ---
ax_hrv.set_title(
    "3. Heart Rate Variability (IBI Tachogram, RMSSD, SDNN & Baevsky Stress)",
    fontsize=10.5,
    fontweight="bold",
    color="#fde047",
)
ax_hrv.set_xlabel("Absolute Wall-Clock Time (HH:MM:SS)")
ax_hrv.set_ylabel("Interval / HRV (ms) & Stress Index")
ax_hrv.xaxis.set_major_formatter(time_formatter)

(line_ibi,) = ax_hrv.plot(
    [], [], color=pal_hrv[3], linewidth=1.8, marker=".", markersize=4, label="IBI: waiting..."
)
(line_rmssd,) = ax_hrv.plot(
    [], [], color=pal_hrv[2], linewidth=1.7, label="RMSSD / SDNN: waiting..."
)
(line_stress,) = ax_hrv.plot(
    [],
    [],
    color="#f97316",
    linewidth=1.4,
    linestyle="-.",
    label="Baevsky Stress SI: waiting...",
)
leg_hrv = ax_hrv.legend(loc="upper left", framealpha=0.88)

# --- Subplot 4: 3-Axis IMU Accelerometer & NLMS Motion Reference (Mako Palette) ---
ax_imu.set_title(
    "4. Synchronized 3-Axis IMU Accelerometer & Motion Artifact State",
    fontsize=10.5,
    fontweight="bold",
    color="#7dd3fc",
)
ax_imu.set_xlabel("Absolute Wall-Clock Time (HH:MM:SS)")
ax_imu.set_ylabel("Acceleration (g)")
ax_imu.xaxis.set_major_formatter(time_formatter)

(line_ax,) = ax_imu.plot([], [], color=pal_imu[1], linewidth=1.2, label="Accel X / Y: waiting...")
(line_ay,) = ax_imu.plot([], [], color=pal_imu[2], linewidth=1.2, alpha=0.85, label="Accel Z: waiting...")
(line_az,) = ax_imu.plot([], [], color=pal_imu[3], linewidth=1.2, alpha=0.85, label="Motion State: waiting...")
(line_amag,) = ax_imu.plot(
    [], [], color="#f43f5e", linewidth=1.9, label="Vector |a| Mag: waiting..."
)
leg_imu = ax_imu.legend(loc="upper left", framealpha=0.88)

plt.tight_layout(rect=[0.0, 0.0, 1.0, 0.95])

# ---------------------------------------------------------------------------
# Rolling Buffers (Pruned by Absolute Time Window)
# ---------------------------------------------------------------------------
ts_sec_buf = deque()
mpl_date_buf = deque()
green_buf = deque()
red_buf = deque()
ir_buf = deque()
filt_buf = deque()
ax_buf = deque()
ay_buf = deque()
az_buf = deque()
amag_buf = deque()
hr_buf = deque()
spo2_buf = deque()
resp_buf = deque()
ibi_buf = deque()
rmssd_buf = deque()
sdnn_buf = deque()
stress_buf = deque()

total_samples = 0
last_monotonic_ms = 0
latest_metrics = {
    "green": 0,
    "status": 0,
    "red": 0,
    "ir": 0,
    "filt": 0.0,
    "ax": 0.0,
    "ay": 0.0,
    "az": 1.0,
    "amag": 1.0,
    "hr": 0.0,
    "spo2": 0.0,
    "r": 0.0,
    "pi": 0.0,
    "ibi": 0,
    "sdnn": 0.0,
    "rmssd": 0.0,
    "pnn50": 0.0,
    "si": 0.0,
    "resp": 0.0,
    "stemp": 0.0,
    "atemp": 0.0,
    "fs": 100.0,
}


def compute_python_dsp_enhancements(g_arr, filt_arr, fs_est):
    """
    Applies zero-phase Butterworth bandpass filtering (0.5 - 4.0 Hz) and systolic peak
    detection in Python when needed, and estimates NLMS SNR gain in dB.
    """
    n = len(g_arr)
    if n < 30:
        return filt_arr, np.array([], dtype=int), 0.0

    g_zero = g_arr - np.median(g_arr)
    safe_fs = float(np.clip(fs_est, 25.0, 200.0))

    # Zero-phase 3rd-order Butterworth bandpass (0.5 - 4.0 Hz) via SciPy
    try:
        sos = signal.butter(
            3, [0.5, min(4.0, safe_fs * 0.4)], btype="bandpass", fs=safe_fs, output="sos"
        )
        scipy_clean = signal.sosfiltfilt(sos, g_zero)
    except Exception:
        scipy_clean = filt_arr

    # Fuse watch real-time NLMS output with zero-phase SciPy bandpass for display clarity
    if np.std(filt_arr) > 1.0:
        display_filt = 0.6 * filt_arr + 0.4 * scipy_clean
    else:
        display_filt = scipy_clean

    # Detect systolic peaks with refractory distance (0.32s) and prominence
    min_dist = max(int(safe_fs * 0.32), 5)
    prom = max(np.std(display_filt) * 0.45, 5.0)
    peaks, _ = signal.find_peaks(display_filt, distance=min_dist, prominence=prom)

    # Estimate SNR improvement (inband cardiac power vs out-of-band noise power)
    raw_var = np.var(g_zero) + 1e-6
    clean_var = np.var(display_filt) + 1e-6
    noise_var = max(raw_var - clean_var, 1e-6)
    snr_db = float(np.clip(10.0 * np.log10(clean_var / noise_var), -5.0, 35.0))

    return display_filt, peaks, snr_db


def update(_):
    global total_samples, last_monotonic_ms

    while True:
        try:
            item = data_queue.get_nowait()
        except Empty:
            break

        (
            ts_ms,
            green,
            status,
            red,
            ir,
            filt,
            ax_v,
            ay_v,
            az_v,
            amag_v,
            hr,
            spo2,
            r_ratio,
            pi_pct,
            ibi,
            sdnn,
            rmssd,
            pnn50,
            si,
            resp,
            stemp,
            atemp,
            fs,
        ) = item

        # Enforce strictly monotonic timestamp for duplicate-ms sensor bursts
        if ts_ms <= last_monotonic_ms:
            ts_ms = last_monotonic_ms + 2
        last_monotonic_ms = ts_ms

        iso_str = datetime.fromtimestamp(ts_ms / 1000.0).strftime("%H:%M:%S.%f")[:-3]
        writer.writerow(
            [
                ts_ms,
                iso_str,
                green,
                red,
                ir,
                filt,
                ax_v,
                ay_v,
                az_v,
                amag_v,
                hr,
                spo2,
                r_ratio,
                pi_pct,
                ibi,
                sdnn,
                rmssd,
                pnn50,
                si,
                resp,
                stemp,
                atemp,
                fs,
                status,
            ]
        )
        total_samples += 1

        latest_metrics.update(
            {
                "green": green,
                "status": status,
                "red": red,
                "ir": ir,
                "filt": filt,
                "ax": ax_v,
                "ay": ay_v,
                "az": az_v,
                "amag": amag_v,
                "hr": hr,
                "spo2": spo2,
                "r": r_ratio,
                "pi": pi_pct,
                "ibi": ibi,
                "sdnn": sdnn,
                "rmssd": rmssd,
                "pnn50": pnn50,
                "si": si,
                "resp": resp,
                "stemp": stemp,
                "atemp": atemp,
                "fs": fs,
            }
        )

        if green != 0:
            t_sec = ts_ms / 1000.0
            dt_obj = datetime.fromtimestamp(t_sec)
            ts_sec_buf.append(t_sec)
            mpl_date_buf.append(mdates.date2num(dt_obj))
            green_buf.append(float(green))
            red_buf.append(float(red))
            ir_buf.append(float(ir))
            filt_buf.append(float(filt))
            ax_buf.append(ax_v)
            ay_buf.append(ay_v)
            az_buf.append(az_v)
            amag_buf.append(amag_v)
            hr_buf.append(hr)
            spo2_buf.append(spo2)
            resp_buf.append(resp)
            ibi_buf.append(float(ibi) if ibi > 0 else np.nan)
            rmssd_buf.append(rmssd if rmssd > 0 else np.nan)
            sdnn_buf.append(sdnn if sdnn > 0 else np.nan)
            stress_buf.append(si if si > 0 else np.nan)

    if len(ts_sec_buf) < 4:
        csv_file.flush()
        return

    newest_sec = ts_sec_buf[-1]
    while ts_sec_buf and (
        (newest_sec - ts_sec_buf[0]) > WINDOW_SECONDS or ts_sec_buf[0] > newest_sec
    ):
        ts_sec_buf.popleft()
        mpl_date_buf.popleft()
        green_buf.popleft()
        red_buf.popleft()
        ir_buf.popleft()
        filt_buf.popleft()
        ax_buf.popleft()
        ay_buf.popleft()
        az_buf.popleft()
        amag_buf.popleft()
        hr_buf.popleft()
        spo2_buf.popleft()
        resp_buf.popleft()
        ibi_buf.popleft()
        rmssd_buf.popleft()
        sdnn_buf.popleft()
        stress_buf.popleft()

    x_dates = np.array(mpl_date_buf, dtype=float)
    x_min_date = mdates.date2num(datetime.fromtimestamp(newest_sec - WINDOW_SECONDS))
    x_max_date = x_dates[-1]

    g_arr = np.array(green_buf, dtype=float)
    r_arr = np.array(red_buf, dtype=float)
    ir_arr = np.array(ir_buf, dtype=float)
    f_arr = np.array(filt_buf, dtype=float)

    m = latest_metrics
    display_filt, peaks, snr_db = compute_python_dsp_enhancements(
        g_arr, f_arr, m["fs"]
    )

    # If watch HR hasn't locked yet, derive instant BPM & IBI from Python peak detector
    py_hr = m["hr"]
    py_ibi = m["ibi"]
    py_rmssd = m["rmssd"]
    py_sdnn = m["sdnn"]
    if len(peaks) >= 2:
        t_arr = np.array(ts_sec_buf, dtype=float)
        peak_ibis = np.diff(t_arr[peaks]) * 1000.0
        valid_ibis = peak_ibis[(peak_ibis >= 300.0) & (peak_ibis <= 1600.0)]
        if len(valid_ibis) >= 1:
            if py_ibi <= 0:
                py_ibi = int(round(valid_ibis[-1]))
            if py_hr <= 0.0:
                py_hr = float(60000.0 / np.mean(valid_ibis))
                hr_buf[-1] = py_hr
            if len(valid_ibis) >= 2 and py_rmssd <= 0.0:
                py_rmssd = float(np.sqrt(np.mean(np.diff(valid_ibis) ** 2)))
                py_sdnn = float(np.std(valid_ibis))
                rmssd_buf[-1] = py_rmssd
                sdnn_buf[-1] = py_sdnn

    # --- 1. Update PPG Subplot ---
    g_zero = g_arr - np.mean(g_arr)
    # Scale raw zero-mean green to comparable range with bandpass signal
    g_scale = (np.std(display_filt) / max(np.std(g_zero), 1.0)) if np.std(display_filt) > 1.0 else 1.0
    g_vis = g_zero * np.clip(g_scale, 0.15, 1.0)

    if np.any(r_arr != 0):
        rir_zero = (r_arr - np.mean(r_arr)) * 0.5
    elif np.any(ir_arr != 0):
        rir_zero = (ir_arr - np.mean(ir_arr)) * 0.5
    else:
        rir_zero = np.zeros_like(g_vis)

    line_filt_ppg.set_data(x_dates, display_filt)
    line_green_ac.set_data(x_dates, g_vis)
    line_red_ir_ac.set_data(x_dates, rir_zero)

    if len(peaks) > 0:
        line_peaks.set_data(x_dates[peaks], display_filt[peaks])
    else:
        line_peaks.set_data([], [])

    ax_ppg.set_xlim(x_min_date, x_max_date)
    y_ppg_low = float(min(np.min(display_filt), np.min(g_vis)))
    y_ppg_high = float(max(np.max(display_filt), np.max(g_vis)))
    pad_ppg = max((y_ppg_high - y_ppg_low) * 0.18, 10.0)
    ax_ppg.set_ylim(y_ppg_low - pad_ppg, y_ppg_high + pad_ppg)

    leg_ppg_texts = leg_ppg.get_texts()
    if len(leg_ppg_texts) >= 4:
        leg_ppg_texts[0].set_text(
            f"NLMS Filtered PPG: {display_filt[-1]:+.1f} | Fs: {m['fs']:.1f} Hz | SNR: {snr_db:+.1f} dB"
        )
        leg_ppg_texts[1].set_text(
            f"Green ADC (530nm): {m['green']} | PI: {m['pi']:.2f}% | Status: {m['status']}"
        )
        leg_ppg_texts[2].set_text(
            f"Red ADC (660nm): {m['red']} | IR ADC (940nm): {m['ir']}"
        )
        leg_ppg_texts[3].set_text(
            f"Systolic Peaks ({len(peaks)} in window) | HR: {py_hr:.1f} BPM"
        )

    # --- 2. Update Vitals Subplot (HR, SpO2, Respiration) ---
    hr_arr = np.array(hr_buf, dtype=float)
    spo2_arr = np.array(spo2_buf, dtype=float)
    resp_arr = np.array(resp_buf, dtype=float)

    line_hr.set_data(x_dates, hr_arr)
    line_spo2.set_data(x_dates, spo2_arr)
    line_resp.set_data(x_dates, resp_arr)
    ax_vitals.set_xlim(x_min_date, x_max_date)

    valid_vitals = np.concatenate([hr_arr[hr_arr > 0], spo2_arr[spo2_arr > 0], resp_arr[resp_arr > 0]])
    if len(valid_vitals) > 0:
        vmin, vmax = float(np.min(valid_vitals)), float(np.max(valid_vitals))
        ax_vitals.set_ylim(max(0.0, vmin - 8.0), vmax + 10.0)
    else:
        ax_vitals.set_ylim(0.0, 120.0)

    mean_hr = float(np.mean(hr_arr[hr_arr > 0])) if np.any(hr_arr > 0) else py_hr
    leg_vitals_texts = leg_vitals.get_texts()
    if len(leg_vitals_texts) >= 3:
        leg_vitals_texts[0].set_text(
            f"Heart Rate: {py_hr:.1f} BPM | Window Mean: {mean_hr:.1f} BPM"
        )
        leg_vitals_texts[1].set_text(
            f"SpO2: {m['spo2']:.1f}% | Red/IR Ratio R={m['r']:.3f} | PI={m['pi']:.2f}%"
        )
        temp_str = f"{m['stemp']:.1f}°C" if m["stemp"] > 0 else "N/A"
        leg_vitals_texts[2].set_text(
            f"Resp Rate: {m['resp']:.1f} brpm | Wrist Temp: {temp_str}"
        )

    # --- 3. Update HRV & Autonomic Stress Subplot ---
    ibi_arr = np.array(ibi_buf, dtype=float)
    rmssd_arr = np.array(rmssd_buf, dtype=float)
    stress_arr = np.array(stress_buf, dtype=float)

    line_ibi.set_data(x_dates, np.nan_to_num(ibi_arr, nan=float(py_ibi)))
    line_rmssd.set_data(x_dates, np.nan_to_num(rmssd_arr, nan=py_rmssd))
    line_stress.set_data(x_dates, np.nan_to_num(stress_arr, nan=m["si"]))
    ax_hrv.set_xlim(x_min_date, x_max_date)

    hrv_max = max(float(py_ibi), py_rmssd, m["si"], 120.0)
    ax_hrv.set_ylim(0.0, hrv_max * 1.18)

    stress_label = (
        "Calm/Optimal"
        if m["si"] < 150
        else ("Moderate" if m["si"] < 350 else "Elevated Stress")
    )
    leg_hrv_texts = leg_hrv.get_texts()
    if len(leg_hrv_texts) >= 3:
        leg_hrv_texts[0].set_text(
            f"IBI Tachogram: {py_ibi} ms | pNN50: {m['pnn50']:.1f}%"
        )
        leg_hrv_texts[1].set_text(
            f"HRV RMSSD: {py_rmssd:.1f} ms | SDNN: {py_sdnn:.1f} ms"
        )
        leg_hrv_texts[2].set_text(
            f"Baevsky Stress SI: {m['si']:.0f} ({stress_label})"
        )

    # --- 4. Update 3-Axis IMU Subplot ---
    ax_arr = np.array(ax_buf, dtype=float)
    ay_arr = np.array(ay_buf, dtype=float)
    az_arr = np.array(az_buf, dtype=float)
    amag_arr = np.array(amag_buf, dtype=float)

    line_ax.set_data(x_dates, ax_arr)
    line_ay.set_data(x_dates, ay_arr)
    line_az.set_data(x_dates, az_arr)
    line_amag.set_data(x_dates, amag_arr)
    ax_imu.set_xlim(x_min_date, x_max_date)

    imu_min = float(min(np.min(ax_arr), np.min(ay_arr), np.min(az_arr), -1.2))
    imu_max = float(max(np.max(ax_arr), np.max(ay_arr), np.max(az_arr), np.max(amag_arr), 1.5))
    ax_imu.set_ylim(imu_min - 0.25, imu_max + 0.35)

    motion_active = abs(m["amag"] - 1.0) > 0.18
    motion_str = "ACTIVE (NLMS Cancelling)" if motion_active else "Stationary (Optimal)"
    leg_imu_texts = leg_imu.get_texts()
    if len(leg_imu_texts) >= 4:
        leg_imu_texts[0].set_text(
            f"Accel X: {m['ax']:+.2f} g | Accel Y: {m['ay']:+.2f} g"
        )
        leg_imu_texts[1].set_text(
            f"Accel Z: {m['az']:+.2f} g | Amb Temp: {m['atemp']:.1f}°C"
        )
        leg_imu_texts[2].set_text(f"Motion State: {motion_str}")
        leg_imu_texts[3].set_text(
            f"Vector |a|: {m['amag']:.2f} g | Total Samples: {total_samples}"
        )

    csv_file.flush()


animation = FuncAnimation(
    fig, update, interval=120, blit=False, cache_frame_data=False
)

try:
    plt.show()
finally:
    process.terminate()
    csv_file.close()
