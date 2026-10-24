package com.example.watch4rawppg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.example.watch4rawppg.health.HealthConnectPublisher
import com.example.watch4rawppg.presentation.MainActivity
import com.example.watch4rawppg.processing.HealthMetricsSnapshot
import com.example.watch4rawppg.processing.PpgSignalProcessor
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Process-wide reactive telemetry store observed by the minimalist Wear OS Compose UI.
 * Updates are throttled to 4 Hz (every 250 ms) to guarantee 60 FPS UI smoothness and minimal battery drain.
 */
object HealthTelemetryRepository {
    val isTracking = mutableStateOf(false)
    val statusText = mutableStateOf("Ready")
    val localCsvPath = mutableStateOf("")
    val metrics = mutableStateOf(HealthMetricsSnapshot())
}

class HealthSensorService : Service(), SensorEventListener {

    companion object {
        const val TAG = "RawGreenPPG"
        const val ACTION_START = "com.example.watch4rawppg.action.START"
        const val ACTION_STOP = "com.example.watch4rawppg.action.STOP"
        const val ACTION_FLUSH = "com.example.watch4rawppg.action.FLUSH"

        private const val CHANNEL_ID = "watch4_ppg_health_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5000L
    }

    private lateinit var dspThread: HandlerThread
    private lateinit var dspHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val processor = PpgSignalProcessor()
    private lateinit var healthConnectPublisher: HealthConnectPublisher

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null

    private var healthTrackingService: HealthTrackingService? = null
    private var ppgTracker: HealthTracker? = null
    private var hrTracker: HealthTracker? = null
    private var accelTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null

    private var csvWriter: BufferedWriter? = null
    private var totalSamples: Long = 0L
    private var lastUiUpdateElapsedMs: Long = 0L
    private var lastNotifUpdateElapsedMs: Long = 0L
    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        dspThread = HandlerThread("PpgDspThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
            start()
        }
        dspHandler = Handler(dspThread.looper)
        healthConnectPublisher = HealthConnectPublisher(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FLUSH -> {
                dspHandler.post {
                    try {
                        ppgTracker?.flush()
                        hrTracker?.flush()
                        accelTracker?.flush()
                        skinTempTracker?.flush()
                        csvWriter?.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Flush warning: ${e.message}")
                    }
                }
                return START_STICKY
            }
            else -> {
                startForegroundWithHealthType()
                if (!isServiceRunning) {
                    startTrackingInternal()
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundWithHealthType() {
        val notification = buildNotification("Initializing Health Sensors...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback startForeground", e)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTrackingInternal() {
        isServiceRunning = true
        totalSamples = 0L
        processor.reset()

        acquireWakeLock()
        openLocalCsvWriter()

        // Start low-latency hardware 3-axis accelerometer for NLMS motion artifact compensation
        accelSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, dspHandler)
        }

        updateStatus("Connecting to Samsung Health Sensor...")

        try {
            val service = HealthTrackingService(connectionListener, applicationContext)
            healthTrackingService = service
            service.connectService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect HealthTrackingService", e)
            updateStatus("Sensor service failed")
            HealthTelemetryRepository.isTracking.value = false
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val service = healthTrackingService ?: return
            try {
                val supported = service.trackingCapability.supportHealthTrackerTypes
                Log.i(TAG, "Supported trackers: $supported")

                // 1. Multi-Wavelength PPG Tracker (Green + Red + IR)
                val ppgType = when {
                    supported.contains(HealthTrackerType.PPG_ON_DEMAND) -> HealthTrackerType.PPG_ON_DEMAND
                    supported.contains(HealthTrackerType.PPG_CONTINUOUS) -> HealthTrackerType.PPG_CONTINUOUS
                    else -> null
                }

                if (ppgType == null) {
                    updateStatus("PPG unsupported on watch")
                    return
                }

                ppgTracker = try {
                    service.getHealthTracker(
                        ppgType,
                        setOf(PpgType.GREEN, PpgType.RED, PpgType.IR)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Multi-LED PPG fallback to GREEN only", e)
                    service.getHealthTracker(ppgType, setOf(PpgType.GREEN))
                }
                ppgTracker?.setEventListener(ppgListener)

                // 2. Continuous Heart Rate & IBI Tracker (Hardware sensor fusion)
                if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                    try {
                        hrTracker = service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                        hrTracker?.setEventListener(hrListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "HEART_RATE_CONTINUOUS unavailable: ${e.message}")
                    }
                }

                // 3. Continuous Accelerometer Tracker (SDK IMU stream)
                if (supported.contains(HealthTrackerType.ACCELEROMETER_CONTINUOUS)) {
                    try {
                        accelTracker = service.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
                        accelTracker?.setEventListener(sdkAccelListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "ACCELEROMETER_CONTINUOUS unavailable: ${e.message}")
                    }
                }

                // 4. Wrist Skin Temperature Tracker
                val skinTempType = when {
                    supported.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS) ->
                        HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
                    supported.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND) ->
                        HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND
                    else -> null
                }
                if (skinTempType != null) {
                    try {
                        skinTempTracker = service.getHealthTracker(skinTempType)
                        skinTempTracker?.setEventListener(skinTempListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skin temperature tracker unavailable: ${e.message}")
                    }
                }

                mainHandler.post {
                    HealthTelemetryRepository.isTracking.value = true
                    HealthTelemetryRepository.statusText.value = "Streaming Multi-PPG + IMU"
                }
                Log.i(TAG, "All supported health trackers registered ($ppgType)")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up trackers", e)
                updateStatus("Tracker setup failed")
            }
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "Health Sensor Service connection ended")
            updateStatus("Sensor connection ended")
        }

        override fun onConnectionFailed(exception: HealthTrackerException) {
            Log.e(TAG, "Health Sensor Service connection failed: ${exception.errorCode}", exception)
            updateStatus("Conn failed (${exception.errorCode})")
            mainHandler.post {
                HealthTelemetryRepository.isTracking.value = false
            }
        }
    }

    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: List<DataPoint>) {
            dspHandler.post {
                if (!isServiceRunning) return@post

                var lastSnapshot: HealthMetricsSnapshot? = null
                for (point in data) {
                    val rawTs = point.timestamp
                    // Ensure epoch ms is wall-clock aligned
                    val timestampMs = if (rawTs > 1_600_000_000_000L) rawTs else System.currentTimeMillis()

                    val green = point.getValue(ValueKey.PpgSet.PPG_GREEN) ?: continue
                    val greenStatus = point.getValue(ValueKey.PpgSet.GREEN_STATUS) ?: 0
                    val red = point.getValue(ValueKey.PpgSet.PPG_RED) ?: 0
                    val redStatus = point.getValue(ValueKey.PpgSet.RED_STATUS) ?: -1
                    val ir = point.getValue(ValueKey.PpgSet.PPG_IR) ?: 0
                    val irStatus = point.getValue(ValueKey.PpgSet.IR_STATUS) ?: -1

                    val filtered = processor.processSample(timestampMs, green, red, ir)
                    totalSamples += 1L

                    val snap = HealthMetricsSnapshot(
                        timestampMs = timestampMs,
                        greenAdc = green,
                        redAdc = red,
                        irAdc = ir,
                        greenStatus = greenStatus,
                        redStatus = redStatus,
                        irStatus = irStatus,
                        filteredPpg = filtered,
                        accelX = processor.accelXG,
                        accelY = processor.accelYG,
                        accelZ = processor.accelZG,
                        accelMagG = processor.accelMagG,
                        motionArtifactActive = processor.motionArtifactActive,
                        heartRateBpm = processor.heartRateBpm,
                        spo2Percent = processor.spo2Percent,
                        rRatio = processor.rRatio,
                        perfusionIndexPct = processor.perfusionIndexPct,
                        ibiMs = processor.latestIbiMs,
                        sdnnMs = processor.sdnnMs,
                        rmssdMs = processor.rmssdMs,
                        pnn50Pct = processor.pnn50Pct,
                        stressIndex = processor.stressIndex,
                        respiratoryRateBrpm = processor.respiratoryRateBrpm,
                        skinTempCelsius = processor.skinTempCelsius,
                        ambientTempCelsius = processor.ambientTempCelsius,
                        sampleRateHz = processor.sampleRateHz,
                        totalSamples = totalSamples,
                        hcPublishedRecords = healthConnectPublisher.publishedRecordCount.get(),
                        hcSyncStatus = healthConnectPublisher.lastSyncStatus
                    )
                    lastSnapshot = snap

                    // Emit structured logcat line for live PC Seaborn dashboard
                    val logLine = String.format(
                        Locale.US,
                        "PPG_GREEN,timestamp=%d,value=%d,status=%d,red=%d,ir=%d,filt=%.2f,ax=%.3f,ay=%.3f,az=%.3f,amag=%.3f,hr=%.1f,spo2=%.1f,r=%.3f,pi=%.2f,ibi=%d,sdnn=%.1f,rmssd=%.1f,pnn50=%.1f,si=%.1f,resp=%.1f,stemp=%.2f,atemp=%.2f,fs=%.1f",
                        timestampMs,
                        green,
                        greenStatus,
                        red,
                        ir,
                        filtered,
                        snap.accelX,
                        snap.accelY,
                        snap.accelZ,
                        snap.accelMagG,
                        snap.heartRateBpm,
                        snap.spo2Percent,
                        snap.rRatio,
                        snap.perfusionIndexPct,
                        snap.ibiMs,
                        snap.sdnnMs,
                        snap.rmssdMs,
                        snap.pnn50Pct,
                        snap.stressIndex,
                        snap.respiratoryRateBrpm,
                        snap.skinTempCelsius,
                        snap.ambientTempCelsius,
                        snap.sampleRateHz
                    )
                    Log.i(TAG, logLine)

                    // Append to local watch CSV storage
                    writeCsvRow(snap)
                }

                val latest = lastSnapshot ?: return@post
                healthConnectPublisher.onMetricsUpdated(latest)

                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (nowElapsed - lastUiUpdateElapsedMs >= UI_UPDATE_INTERVAL_MS) {
                    lastUiUpdateElapsedMs = nowElapsed
                    mainHandler.post {
                        if (!isServiceRunning) return@post
                        HealthTelemetryRepository.metrics.value = latest
                        HealthTelemetryRepository.statusText.value =
                            if (latest.motionArtifactActive) "NLMS Active (Motion)" else "Streaming Optimal"
                    }
                }

                if (nowElapsed - lastNotifUpdateElapsedMs >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                    lastNotifUpdateElapsedMs = nowElapsed
                    val summary = String.format(
                        Locale.US,
                        "HR: %.0f BPM | SpO2: %.1f%% | RMSSD: %.0f ms",
                        latest.heartRateBpm,
                        latest.spo2Percent,
                        latest.rmssdMs
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIFICATION_ID, buildNotification(summary))
                }
            }
        }

        override fun onFlushCompleted() {
            Log.i(TAG, "PPG flush completed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "PPG tracker error: $error")
            updateStatus("Tracker error: $error")
        }
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: List<DataPoint>) {
            dspHandler.post {
                for (point in data) {
                    val hr = point.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0
                    val status = point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0
                    val ibiList = point.getValue(ValueKey.HeartRateSet.IBI_LIST)
                    val ibiStatus = point.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                    processor.updateExternalHrAndIbi(hr, status, ibiList, ibiStatus)
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.w(TAG, "HR tracker error: $error")
        }
    }

    private val sdkAccelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: List<DataPoint>) {
            dspHandler.post {
                val last = data.lastOrNull() ?: return@post
                val rx = last.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) ?: return@post
                val ry = last.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) ?: return@post
                val rz = last.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) ?: return@post
                processor.updateAccelerometerSdkRaw(rx, ry, rz)
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {}
    }

    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: List<DataPoint>) {
            dspHandler.post {
                for (point in data) {
                    val objTemp = point.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val ambTemp = point.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    if (objTemp != null && objTemp > 15f) {
                        processor.skinTempCelsius = objTemp
                    }
                    if (ambTemp != null && ambTemp > 5f) {
                        processor.ambientTempCelsius = ambTemp
                    }
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && event.values.size >= 3) {
            processor.updateAccelerometerMps2(event.values[0], event.values[1], event.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun openLocalCsvWriter() {
        dspHandler.post {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "watch_health_session.csv")
                val isNew = !file.exists() || file.length() == 0L
                csvWriter = BufferedWriter(FileWriter(file, true), 32 * 1024)
                if (isNew) {
                    csvWriter?.write(
                        "timestamp_ms,green_adc,red_adc,ir_adc,filtered_ppg,accel_x_g,accel_y_g,accel_z_g,accel_mag_g," +
                            "hr_bpm,spo2_pct,r_ratio,pi_pct,ibi_ms,sdnn_ms,rmssd_ms,pnn50_pct,stress_index,resp_brpm," +
                            "skin_temp_c,ambient_temp_c,green_status\n"
                    )
                }
                mainHandler.post {
                    HealthTelemetryRepository.localCsvPath.value = file.absolutePath
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open local CSV writer", e)
            }
        }
    }

    private fun writeCsvRow(s: HealthMetricsSnapshot) {
        val writer = csvWriter ?: return
        try {
            val row = String.format(
                Locale.US,
                "%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%.3f,%.2f,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f,%.2f,%d\n",
                s.timestampMs,
                s.greenAdc,
                s.redAdc,
                s.irAdc,
                s.filteredPpg,
                s.accelX,
                s.accelY,
                s.accelZ,
                s.accelMagG,
                s.heartRateBpm,
                s.spo2Percent,
                s.rRatio,
                s.perfusionIndexPct,
                s.ibiMs,
                s.sdnnMs,
                s.rmssdMs,
                s.pnn50Pct,
                s.stressIndex,
                s.respiratoryRateBrpm,
                s.skinTempCelsius,
                s.ambientTempCelsius,
                s.greenStatus
            )
            writer.write(row)
            if (s.totalSamples % 200L == 0L) {
                writer.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopTrackingInternal() {
        isServiceRunning = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }

        try {
            ppgTracker?.unsetEventListener()
        } catch (_: Exception) {
        }
        ppgTracker = null

        try {
            hrTracker?.unsetEventListener()
        } catch (_: Exception) {
        }
        hrTracker = null

        try {
            accelTracker?.unsetEventListener()
        } catch (_: Exception) {
        }
        accelTracker = null

        try {
            skinTempTracker?.unsetEventListener()
        } catch (_: Exception) {
        }
        skinTempTracker = null

        try {
            healthTrackingService?.disconnectService()
        } catch (_: Exception) {
        }
        healthTrackingService = null

        dspHandler.post {
            try {
                csvWriter?.flush()
                csvWriter?.close()
            } catch (_: Exception) {
            }
            csvWriter = null
        }

        releaseWakeLock()

        mainHandler.post {
            HealthTelemetryRepository.isTracking.value = false
            HealthTelemetryRepository.statusText.value = "Stopped"
        }
        Log.i(TAG, "HealthSensorService stopped")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Watch4RawPPG::HealthSensorWakeLock").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // Up to 4 hours continuous session
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Continuous Health Telemetry",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous PPG, IMU, HRV, SpO2 & Health Connect Sync"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Watch4 BioSensor Active")
            .setContentText(contentText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(message: String) {
        mainHandler.post {
            HealthTelemetryRepository.statusText.value = message
        }
    }

    override fun onDestroy() {
        stopTrackingInternal()
        dspThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
