package com.example.watch4rawppg.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.watch4rawppg.health.HealthConnectPublisher
import com.example.watch4rawppg.presentation.theme.Watch4RawPPGTheme
import com.example.watch4rawppg.processing.HealthMetricsSnapshot
import com.example.watch4rawppg.service.HealthSensorService
import com.example.watch4rawppg.service.HealthTelemetryRepository
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val PPG_PERMISSION =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            startHealthService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            Watch4RawPPGTheme {
                val isTracking by HealthTelemetryRepository.isTracking
                val status by HealthTelemetryRepository.statusText
                val snap by HealthTelemetryRepository.metrics

                HealthDashboardScreen(
                    isTracking = isTracking,
                    status = status,
                    snap = snap,
                    onStartClick = { requestPermissionsAndStart() },
                    onStopClick = { stopHealthService() },
                    onFlushClick = { flushHealthService() },
                    onHealthConnectClick = {
                        HealthConnectPublisher(applicationContext).openHealthConnectSettings()
                    }
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            PPG_PERMISSION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            needed.addAll(HealthConnectPublisher.HEALTH_CONNECT_PERMISSIONS)
        }

        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            HealthTelemetryRepository.statusText.value = "Requesting permissions..."
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startHealthService()
        }
    }

    private fun startHealthService() {
        val intent = Intent(this, HealthSensorService::class.java).apply {
            action = HealthSensorService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopHealthService() {
        val intent = Intent(this, HealthSensorService::class.java).apply {
            action = HealthSensorService.ACTION_STOP
        }
        startService(intent)
    }

    private fun flushHealthService() {
        val intent = Intent(this, HealthSensorService::class.java).apply {
            action = HealthSensorService.ACTION_FLUSH
        }
        startService(intent)
    }
}

private val CardBg = Color(0xFF12161F)
private val AccentGreen = Color(0xFF00E676)
private val AccentCyan = Color(0xFF00E5FF)
private val AccentAmber = Color(0xFFFFB300)
private val AccentCoral = Color(0xFFFF5252)
private val AccentPurple = Color(0xFFB388FF)
private val TextMuted = Color(0xFF9EA7B8)

@Composable
private fun HealthDashboardScreen(
    isTracking: Boolean,
    status: String,
    snap: HealthMetricsSnapshot,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onFlushClick: () -> Unit,
    onHealthConnectClick: () -> Unit
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(
                    onClick = if (isTracking) onStopClick else onStartClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTracking) Color(0xFF3E1820) else Color(0xFF0E3A2A),
                        contentColor = if (isTracking) AccentCoral else AccentGreen
                    )
                ) {
                    Text(
                        text = if (isTracking) "Stop" else "Start",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        ) { contentPadding ->
            TransformingLazyColumn(
                contentPadding = contentPadding,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "WATCH4 BIO-HUB",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen
                            )
                            Text(
                                text = status,
                                fontSize = 10.sp,
                                color = if (snap.motionArtifactActive) AccentAmber else TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 1. Primary Vitals Row: HR + SpO2
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = "HR (BPM)",
                            value = if (snap.heartRateBpm > 0f)
                                String.format(Locale.US, "%.0f", snap.heartRateBpm)
                            else "--",
                            sub = if (snap.ibiMs > 0) "${snap.ibiMs} ms" else "IBI --",
                            accent = AccentCoral
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = "SpO2 (%)",
                            value = if (snap.spo2Percent > 0f)
                                String.format(Locale.US, "%.1f%%", snap.spo2Percent)
                            else "--",
                            sub = String.format(Locale.US, "R=%.2f", snap.rRatio),
                            accent = AccentCyan
                        )
                    }
                }

                // 2. Secondary Vitals Row: HRV (RMSSD / SDNN) + Resp Rate
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = "RMSSD",
                            value = if (snap.rmssdMs > 0f)
                                String.format(Locale.US, "%.1f", snap.rmssdMs)
                            else "--",
                            sub = String.format(Locale.US, "SDNN %.0fms", snap.sdnnMs),
                            accent = AccentGreen
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = "RESP / PI",
                            value = if (snap.respiratoryRateBrpm > 0f)
                                String.format(Locale.US, "%.1f", snap.respiratoryRateBrpm)
                            else "--",
                            sub = String.format(Locale.US, "PI %.2f%%", snap.perfusionIndexPct),
                            accent = AccentPurple
                        )
                    }
                }

                // 3. Autonomic Stress & pNN50 Card
                item {
                    val stressState = when {
                        snap.stressIndex <= 0f -> "Calibrating"
                        snap.stressIndex < 150f -> "Optimal"
                        snap.stressIndex < 350f -> "Moderate"
                        else -> "High Load"
                    }
                    CompactInfoCard(
                        title = "AUTONOMIC & STRESS",
                        line1 = String.format(
                            Locale.US,
                            "Stress SI: %.0f (%s)",
                            snap.stressIndex,
                            stressState
                        ),
                        line2 = String.format(
                            Locale.US,
                            "pNN50: %.1f%% | Temp: %s",
                            snap.pnn50Pct,
                            if (snap.skinTempCelsius > 0f)
                                String.format(Locale.US, "%.1f°C", snap.skinTempCelsius)
                            else "N/A"
                        ),
                        accent = AccentAmber
                    )
                }

                // 4. Multi-Wavelength Optical PPG Channels
                item {
                    CompactInfoCard(
                        title = "OPTICAL PPG (G / R / IR)",
                        line1 = "G:${snap.greenAdc}  R:${snap.redAdc}",
                        line2 = String.format(
                            Locale.US,
                            "IR:%d  Filt:%.1f",
                            snap.irAdc,
                            snap.filteredPpg
                        ),
                        accent = AccentGreen
                    )
                }

                // 5. Synchronized 3-Axis IMU & Motion Filter
                item {
                    CompactInfoCard(
                        title = "3-AXIS IMU & NLMS FILTER",
                        line1 = String.format(
                            Locale.US,
                            "X:%.2f Y:%.2f Z:%.2f g",
                            snap.accelX,
                            snap.accelY,
                            snap.accelZ
                        ),
                        line2 = String.format(
                            Locale.US,
                            "|a|:%.2fg | %.0fHz | N:%d",
                            snap.accelMagG,
                            snap.sampleRateHz,
                            snap.totalSamples
                        ),
                        accent = AccentCyan
                    )
                }

                // 6. Health Connect & Samsung Health Sync Status
                item {
                    CompactInfoCard(
                        title = "HEALTH CONNECT SYNC",
                        line1 = "Status: ${snap.hcSyncStatus}",
                        line2 = "Published: ${snap.hcPublishedRecords} records",
                        accent = AccentPurple
                    )
                }

                // 7. Action Buttons
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = onFlushClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardBg,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Flush", fontSize = 11.sp)
                        }
                        Button(
                            onClick = onHealthConnectClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardBg,
                                contentColor = AccentCyan
                            )
                        ) {
                            Text("HC Perms", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 16.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = sub,
            fontSize = 9.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CompactInfoCard(
    title: String,
    line1: String,
    line2: String,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            fontSize = 9.sp,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = line1,
            fontSize = 11.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = line2,
            fontSize = 10.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
    }
}
