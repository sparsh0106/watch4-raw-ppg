package com.example.watch4rawppg.health

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.InsertRecordsResponse
import android.health.connect.datatypes.Device
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RespiratoryRateRecord
import android.health.connect.datatypes.SkinTemperatureRecord
import android.health.connect.datatypes.units.Percentage
import android.health.connect.datatypes.units.Temperature
import android.os.Build
import android.os.OutcomeReceiver
import android.util.Log
import com.example.watch4rawppg.processing.HealthMetricsSnapshot
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * Continuously publishes validated physiological metrics (Heart Rate, SpO2, HRV RMSSD,
 * Respiratory Rate, and Wrist Skin Temperature) to Android Health Connect (Google Health)
 * and Samsung Health.
 */
@SuppressLint("NewApi")
class HealthConnectPublisher(private val context: Context) {

    companion object {
        private const val TAG = "RawGreenPPG_HC"
        private const val PUBLISH_INTERVAL_MS = 12_000L

        val HEALTH_CONNECT_PERMISSIONS = arrayOf(
            "android.permission.health.WRITE_HEART_RATE",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_OXYGEN_SATURATION",
            "android.permission.health.READ_OXYGEN_SATURATION",
            "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
            "android.permission.health.WRITE_RESPIRATORY_RATE",
            "android.permission.health.WRITE_SKIN_TEMPERATURE",
            "android.permission.health.READ_SKIN_TEMPERATURE"
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val healthConnectManager: HealthConnectManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(HealthConnectManager::class.java)
        } else {
            null
        }

    private val hrSamplesBuffer = ArrayList<HeartRateRecord.HeartRateSample>(32)
    private var batchStartTimeMs: Long = 0L
    private var lastPublishTimeMs: Long = 0L

    val publishedRecordCount = AtomicInteger(0)
    @Volatile var lastSyncStatus: String = "Ready"
        private set

    private fun buildWatchMetadata(): Metadata {
        val device = Device.Builder()
            .setManufacturer(Build.MANUFACTURER ?: "Samsung")
            .setModel(Build.MODEL ?: "Galaxy Watch4")
            .setType(Device.DEVICE_TYPE_WATCH)
            .build()

        return Metadata.Builder()
            .setDevice(device)
            .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
            .build()
    }

    /**
     * Queues a live health snapshot and flushes a batch to Health Connect / Samsung Health
     * every PUBLISH_INTERVAL_MS (~12 seconds).
     */
    fun onMetricsUpdated(snapshot: HealthMetricsSnapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || healthConnectManager == null) {
            lastSyncStatus = "Unsupported OS"
            return
        }

        val nowMs = if (snapshot.timestampMs > 1_600_000_000_000L) {
            snapshot.timestampMs
        } else {
            System.currentTimeMillis()
        }

        if (batchStartTimeMs == 0L) {
            batchStartTimeMs = nowMs - 1000L
            lastPublishTimeMs = nowMs
        }

        val bpmLong = snapshot.heartRateBpm.roundToLong()
        if (bpmLong in 35L..220L) {
            synchronized(hrSamplesBuffer) {
                val sampleInstant = Instant.ofEpochMilli(nowMs)
                if (hrSamplesBuffer.isEmpty() ||
                    hrSamplesBuffer.last().time.isBefore(sampleInstant)
                ) {
                    hrSamplesBuffer.add(HeartRateRecord.HeartRateSample(bpmLong, sampleInstant))
                }
            }
        }

        if (nowMs - lastPublishTimeMs >= PUBLISH_INTERVAL_MS) {
            flushRecords(snapshot, batchStartTimeMs, nowMs)
            batchStartTimeMs = nowMs
            lastPublishTimeMs = nowMs
        }
    }

    private fun flushRecords(
        snapshot: HealthMetricsSnapshot,
        startMs: Long,
        endMs: Long
    ) {
        val manager = healthConnectManager ?: return
        val metadata = buildWatchMetadata()
        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(Instant.ofEpochMilli(endMs))
        val records = ArrayList<Record>(5)

        val hrSamplesCopy: List<HeartRateRecord.HeartRateSample>
        synchronized(hrSamplesBuffer) {
            hrSamplesCopy = ArrayList(hrSamplesBuffer)
            hrSamplesBuffer.clear()
        }

        // 1. Heart Rate Record
        if (hrSamplesCopy.isNotEmpty()) {
            val firstTime = hrSamplesCopy.first().time
            val lastTime = hrSamplesCopy.last().time
            val safeStart = if (firstTime.isBefore(lastTime)) firstTime else lastTime.minusMillis(500)
            try {
                val hrRecord = HeartRateRecord.Builder(
                    metadata,
                    safeStart,
                    lastTime,
                    hrSamplesCopy
                )
                    .setStartZoneOffset(zoneOffset)
                    .setEndZoneOffset(zoneOffset)
                    .build()
                records.add(hrRecord)
            } catch (e: Exception) {
                Log.w(TAG, "Skipped invalid HeartRateRecord: ${e.message}")
            }
        }

        val instantNow = Instant.ofEpochMilli(endMs)

        // 2. SpO2 (Oxygen Saturation) Record
        if (snapshot.spo2Percent in 70f..100f) {
            try {
                val spo2Record = OxygenSaturationRecord.Builder(
                    metadata,
                    instantNow,
                    Percentage.fromValue(snapshot.spo2Percent.toDouble())
                )
                    .setZoneOffset(zoneOffset)
                    .build()
                records.add(spo2Record)
            } catch (e: Exception) {
                Log.w(TAG, "Skipped invalid OxygenSaturationRecord: ${e.message}")
            }
        }

        // 3. HRV RMSSD Record
        if (snapshot.rmssdMs in 1.0f..250.0f) {
            try {
                val hrvRecord = HeartRateVariabilityRmssdRecord.Builder(
                    metadata,
                    instantNow,
                    snapshot.rmssdMs.toDouble()
                )
                    .setZoneOffset(zoneOffset)
                    .build()
                records.add(hrvRecord)
            } catch (e: Exception) {
                Log.w(TAG, "Skipped invalid HeartRateVariabilityRmssdRecord: ${e.message}")
            }
        }

        // 4. Respiratory Rate Record
        if (snapshot.respiratoryRateBrpm in 4.0f..60.0f) {
            try {
                val respRecord = RespiratoryRateRecord.Builder(
                    metadata,
                    instantNow,
                    snapshot.respiratoryRateBrpm.toDouble()
                )
                    .setZoneOffset(zoneOffset)
                    .build()
                records.add(respRecord)
            } catch (e: Exception) {
                Log.w(TAG, "Skipped invalid RespiratoryRateRecord: ${e.message}")
            }
        }

        // 5. Skin Temperature Record (if wrist skin temperature sensor reported)
        if (snapshot.skinTempCelsius in 25.0f..43.0f) {
            try {
                val skinRecord = SkinTemperatureRecord.Builder(
                    metadata,
                    Instant.ofEpochMilli(startMs.coerceAtMost(endMs - 500L)),
                    instantNow
                )
                    .setBaseline(Temperature.fromCelsius(snapshot.skinTempCelsius.toDouble()))
                    .setMeasurementLocation(SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST)
                    .setStartZoneOffset(zoneOffset)
                    .setEndZoneOffset(zoneOffset)
                    .build()
                records.add(skinRecord)
            } catch (e: Exception) {
                Log.w(TAG, "Skipped invalid SkinTemperatureRecord: ${e.message}")
            }
        }

        if (records.isEmpty()) return

        try {
            manager.insertRecords(
                records,
                executor,
                object : OutcomeReceiver<InsertRecordsResponse, HealthConnectException> {
                    override fun onResult(result: InsertRecordsResponse) {
                        val added = result.records.size
                        val total = publishedRecordCount.addAndGet(added)
                        lastSyncStatus = "Synced ($total recs)"
                        Log.i(TAG, "Published $added records to Health Connect / Samsung Health (total=$total)")
                    }

                    override fun onError(error: HealthConnectException) {
                        lastSyncStatus = "HC Err (${error.errorCode})"
                        Log.w(TAG, "Health Connect insert error (${error.errorCode}): ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            lastSyncStatus = "Sync err"
            Log.w(TAG, "Failed to invoke HealthConnectManager.insertRecords", e)
        }
    }

    fun openHealthConnectSettings() {
        try {
            val intent = Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (ex: Exception) {
                Log.w(TAG, "Could not open Health Connect settings", ex)
            }
        }
    }
}
