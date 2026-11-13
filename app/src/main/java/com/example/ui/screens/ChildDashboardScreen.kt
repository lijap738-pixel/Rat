package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MonitoringSession
import com.example.data.model.SessionType
import com.example.hardware.DeviceTelemetryHelper
import com.example.hardware.WebRtcSessionManager
import com.example.service.SafeNestCameraService
import com.example.service.SafeNestMicService
import com.example.service.SafeNestScreenShareService
import com.example.service.SessionBroadcastBus
import com.example.ui.components.AudioLevelVisualizer
import com.example.ui.components.LiveSessionIndicatorBanner
import com.example.ui.viewmodel.ChildDashboardUiState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ChildDashboardScreen(
  uiState: ChildDashboardUiState,
  onRespondSession: (MonitoringSession, Boolean, String?) -> Unit,
  onEndSession: (MonitoringSession, String) -> Unit,
  onUpdateStreamTelemetry: (String, String?, Int?) -> Unit,
  onSyncTelemetry: (Boolean, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var pendingApprovalSession by remember { mutableStateOf<MonitoringSession?>(null) }
  var locationConsentChecked by remember(uiState.device?.locationConsentEnabled) {
    mutableStateOf(uiState.device?.locationConsentEnabled ?: false)
  }
  val hasUsageAccess = remember { DeviceTelemetryHelper.hasUsageStatsPermission(context) }

  val liveAudioDb by SessionBroadcastBus.audioLevelDb.collectAsStateWithLifecycle()
  val liveScreenFrame by SessionBroadcastBus.screenFrameBase64.collectAsStateWithLifecycle()

  // Stop foreground services when activeSession terminates
  LaunchedEffect(uiState.activeSession?.id) {
    if (uiState.activeSession == null) {
      context.stopService(Intent(context, SafeNestCameraService::class.java))
      context.stopService(Intent(context, SafeNestMicService::class.java))
      context.stopService(Intent(context, SafeNestScreenShareService::class.java))
    }
  }

  // Listen to Stop Sharing action from notification
  LaunchedEffect(uiState.activeSession?.id) {
    SessionBroadcastBus.stopRequested.collect {
      val current = uiState.activeSession
      if (current != null) {
        onEndSession(current, "Stopped by child from persistent notification")
      }
    }
  }

  // Stream Mic dB or Screen Frame telemetry while active
  LaunchedEffect(uiState.activeSession?.id, uiState.activeSession?.sessionType) {
    val active = uiState.activeSession ?: return@LaunchedEffect
    while (isActive) {
      when (SessionType.fromValue(active.sessionType)) {
        SessionType.MICROPHONE -> {
          onUpdateStreamTelemetry(active.id, null, liveAudioDb)
        }
        SessionType.SCREEN -> {
          val frame = liveScreenFrame
          if (!frame.isNullOrBlank()) {
            onUpdateStreamTelemetry(active.id, frame, null)
          }
        }
        else -> Unit
      }
      delay(1000L)
    }
  }

  // Periodic battery & usage sync while dashboard is open
  LaunchedEffect(uiState.device?.id) {
    if (uiState.device != null) {
      onSyncTelemetry(locationConsentChecked, false)
    }
  }

  // Runtime permission launchers
  val notificationPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }

  val mediaProjectionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    val session = pendingApprovalSession
    pendingApprovalSession = null
    if (result.resultCode == Activity.RESULT_OK && result.data != null && session != null) {
      val svcIntent = Intent(context, SafeNestScreenShareService::class.java).apply {
        putExtra(SafeNestScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
        putExtra(SafeNestScreenShareService.EXTRA_RESULT_DATA, result.data)
      }
      ContextCompat.startForegroundService(context, svcIntent)
      val rtc = WebRtcSessionManager(context)
      rtc.createAnswerForOffer(session.sdpOffer) { answer ->
        onRespondSession(session, true, answer)
        rtc.release()
      }
    } else if (session != null) {
      onRespondSession(session, false, null)
    }
  }

  val cameraPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    val session = pendingApprovalSession
    pendingApprovalSession = null
    if (granted && session != null) {
      ContextCompat.startForegroundService(context, Intent(context, SafeNestCameraService::class.java))
      val rtc = WebRtcSessionManager(context)
      rtc.createAnswerForOffer(session.sdpOffer) { answer ->
        onRespondSession(session, true, answer)
        rtc.release()
      }
    } else if (session != null) {
      onRespondSession(session, false, null)
    }
  }

  val micPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    val session = pendingApprovalSession
    pendingApprovalSession = null
    if (granted && session != null) {
      ContextCompat.startForegroundService(context, Intent(context, SafeNestMicService::class.java))
      val rtc = WebRtcSessionManager(context)
      rtc.createAnswerForOffer(session.sdpOffer) { answer ->
        onRespondSession(session, true, answer)
        rtc.release()
      }
    } else if (session != null) {
      onRespondSession(session, false, null)
    }
  }

  val locationPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    locationConsentChecked = granted
    onSyncTelemetry(granted, true)
  }

  fun startApprovalFlow(session: MonitoringSession) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    pendingApprovalSession = session
    when (SessionType.fromValue(session.sessionType)) {
      SessionType.CAMERA -> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
          ContextCompat.startForegroundService(context, Intent(context, SafeNestCameraService::class.java))
          val rtc = WebRtcSessionManager(context)
          rtc.createAnswerForOffer(session.sdpOffer) { answer ->
            onRespondSession(session, true, answer)
            rtc.release()
          }
          pendingApprovalSession = null
        } else {
          cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
      }
      SessionType.MICROPHONE -> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
          ContextCompat.startForegroundService(context, Intent(context, SafeNestMicService::class.java))
          val rtc = WebRtcSessionManager(context)
          rtc.createAnswerForOffer(session.sdpOffer) { answer ->
            onRespondSession(session, true, answer)
            rtc.release()
          }
          pendingApprovalSession = null
        } else {
          micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
      }
      SessionType.SCREEN -> {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        if (mgr != null) {
          mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
          pendingApprovalSession = null
          onRespondSession(session, false, null)
        }
      }
      SessionType.NONE -> pendingApprovalSession = null
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    if (uiState.isLoading) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      val device = uiState.device
      LazyColumn(
        modifier = Modifier
          .widthIn(max = 760.dp)
          .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(48.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary)
                }
              }
              Spacer(Modifier.width(14.dp))
              Column {
                Text(
                  text = device?.deviceName ?: "Child Device",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = "Protected with transparent consent • No hidden access",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }

        // Incoming consent requests from parent
        if (uiState.pendingRequests.isNotEmpty()) {
          val req = uiState.pendingRequests.first()
          val type = SessionType.fromValue(req.sessionType)
          item {
            Card(
              shape = RoundedCornerShape(22.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  val icon = when (type) {
                    SessionType.CAMERA -> Icons.Filled.Videocam
                    SessionType.MICROPHONE -> Icons.Filled.Mic
                    SessionType.SCREEN -> Icons.Filled.ScreenShare
                    SessionType.NONE -> Icons.Filled.Shield
                  }
                  Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = "Parent Requests ${type.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                  )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                  text = "Duration: ${req.requestedDurationMinutes} minutes • Fingerprint: ${req.encryptionFingerprint}. A persistent notification will remain visible while active.",
                  style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  OutlinedButton(
                    onClick = { onRespondSession(req, false, null) },
                    modifier = Modifier
                      .weight(1f)
                      .height(48.dp)
                      .testTag("decline_session_button"),
                  ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Decline")
                  }
                  Button(
                    onClick = { startApprovalFlow(req) },
                    modifier = Modifier
                      .weight(1f)
                      .height(48.dp)
                      .testTag("approve_session_button"),
                  ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Approve")
                  }
                }
              }
            }
          }
        }

        // Active live session banner + hardware preview + instant Stop button
        uiState.activeSession?.let { active ->
          val activeType = SessionType.fromValue(active.sessionType)
          item {
            Card(
              shape = RoundedCornerShape(22.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveSessionIndicatorBanner(
                  sessionType = activeType,
                  expiresAt = active.expiresAt,
                  encryptionFingerprint = active.encryptionFingerprint,
                )

                if (activeType == SessionType.CAMERA) {
                  ChildCameraXStreamer(
                    onFrameCaptured = { b64 ->
                      scope.launch { onUpdateStreamTelemetry(active.id, b64, null) }
                    },
                  )
                } else if (activeType == SessionType.MICROPHONE) {
                  AudioLevelVisualizer(levelDb = liveAudioDb, isActive = true)
                  Text(
                    text = "Live audio active ($liveAudioDb dB) • Zero recording stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else if (activeType == SessionType.SCREEN) {
                  Text(
                    text = "Screen sharing is active via Android MediaProjection. You can stop anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                }

                Button(
                  onClick = {
                    context.stopService(Intent(context, SafeNestCameraService::class.java))
                    context.stopService(Intent(context, SafeNestMicService::class.java))
                    context.stopService(Intent(context, SafeNestScreenShareService::class.java))
                    onEndSession(active, "Stopped by child user")
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                  shape = RoundedCornerShape(16.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("child_stop_sharing_button"),
                ) {
                  Icon(Icons.Filled.Stop, contentDescription = null)
                  Spacer(Modifier.width(8.dp))
                  Text("Stop Sharing Anytime")
                }
              }
            }
          }
        }

        // Location Sharing with Explicit Consent
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column {
                  Text("Location Sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                  Text(
                    text = if (locationConsentChecked) "Sharing approximate location with parent" else "Paused — turn on to share location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = locationConsentChecked,
                onCheckedChange = { enable ->
                  if (enable && !DeviceTelemetryHelper.hasLocationPermission(context)) {
                    locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                  } else {
                    locationConsentChecked = enable
                    onSyncTelemetry(enable, true)
                  }
                },
                modifier = Modifier.testTag("child_location_switch"),
              )
            }
          }
        }

        // Battery & Screen-Time Sync
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Device Wellness Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryFull, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                  text = "Battery: ${device?.batteryLevel ?: 100}% • Screen time today: ${device?.dailyScreenTimeMinutes ?: 0}m / ${device?.screenTimeLimitMinutes ?: 120}m",
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                  onClick = { onSyncTelemetry(locationConsentChecked, false) },
                  modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("sync_telemetry_button"),
                ) {
                  Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Sync Now")
                }
                if (!hasUsageAccess) {
                  OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    modifier = Modifier
                      .weight(1f)
                      .height(48.dp),
                  ) {
                    Text("Usage Access")
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ChildCameraXStreamer(
  onFrameCaptured: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val executor = remember { Executors.newSingleThreadExecutor() }
  val previewView = remember { PreviewView(context) }

  DisposableEffect(lifecycleOwner) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    var lastSentMs = 0L
    cameraProviderFuture.addListener(
      {
        runCatching {
          val provider = cameraProviderFuture.get()
          val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
          }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          analysis.setAnalyzer(executor) { imageProxy ->
            val now = System.currentTimeMillis()
            if (now - lastSentMs >= 1200L) {
              lastSentMs = now
              imageProxyToBase64(imageProxy)?.let(onFrameCaptured)
            }
            imageProxy.close()
          }
          provider.unbindAll()
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }
      },
      ContextCompat.getMainExecutor(context),
    )

    onDispose {
      runCatching { cameraProviderFuture.get().unbindAll() }
      executor.shutdown()
    }
  }

  AndroidView(
    factory = { previewView },
    modifier = Modifier
      .fillMaxWidth()
      .height(220.dp)
      .clip(RoundedCornerShape(16.dp)),
  )
}

private fun imageProxyToBase64(image: androidx.camera.core.ImageProxy): String? = runCatching {
  val yBuffer = image.planes[0].buffer
  val uBuffer = image.planes[1].buffer
  val vBuffer = image.planes[2].buffer
  val ySize = yBuffer.remaining()
  val uSize = uBuffer.remaining()
  val vSize = vBuffer.remaining()
  val nv21 = ByteArray(ySize + uSize + vSize)
  yBuffer.get(nv21, 0, ySize)
  vBuffer.get(nv21, ySize, vSize)
  uBuffer.get(nv21, ySize + vSize, uSize)
  val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
  val rawOut = ByteArrayOutputStream()
  yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, rawOut)
  val bmp = BitmapFactory.decodeByteArray(rawOut.toByteArray(), 0, rawOut.size()) ?: return null
  val scaled = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true)
  if (scaled !== bmp) bmp.recycle()
  val jpegOut = ByteArrayOutputStream()
  scaled.compress(Bitmap.CompressFormat.JPEG, 55, jpegOut)
  scaled.recycle()
  val bytes = jpegOut.toByteArray()
  if (bytes.size <= 90_000) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
}.getOrNull()
