package com.example.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ChildDevice
import com.example.data.model.MonitoringSession
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.hardware.WebRtcSessionManager
import com.example.ui.components.BatteryStatusPill
import com.example.ui.components.LiveSessionIndicatorBanner
import com.example.ui.components.OnlineStatusPill
import com.example.ui.components.formatRelativeTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDeviceDetailScreen(
  device: ChildDevice?,
  sessions: List<MonitoringSession>,
  onBack: () -> Unit,
  onRequestSession: (ChildDevice, SessionType, Long, String?) -> Unit,
  onOpenSession: (MonitoringSession) -> Unit,
  onUpdateSettings: (String, String, Long) -> Unit,
  onRemoveDevice: (ChildDevice) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var selectedDuration by remember { mutableLongStateOf(10L) }
  var showEditDialog by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  val deviceSessions = remember(sessions, device?.id) {
    if (device == null) emptyList() else sessions.filter { it.deviceId == device.id }
  }
  val activeOrPending = remember(deviceSessions) {
    deviceSessions.firstOrNull {
      it.status == SessionStatus.ACTIVE.value || it.status == SessionStatus.REQUESTED.value
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(device?.deviceName ?: "Device Details") },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (device != null) {
            IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Edit, contentDescription = "Edit device settings")
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(48.dp)) {
              Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Remove device",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    if (device == null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text("Device not found or removed.", style = MaterialTheme.typography.bodyLarge)
      }
      return@Scaffold
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier
          .widthIn(max = 820.dp)
          .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(device.deviceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                  Text(
                    "${device.deviceModel} • ${device.osVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                OnlineStatusPill(isOnline = device.isOnline)
              }
              Spacer(Modifier.height(14.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                BatteryStatusPill(device.batteryLevel, device.isCharging)
                Text(
                  text = "Last active: ${formatRelativeTime(device.lastActiveAt)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        if (activeOrPending != null) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(20.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)),
            ) {
              Column(modifier = Modifier.padding(16.dp)) {
                if (activeOrPending.status == SessionStatus.ACTIVE.value) {
                  LiveSessionIndicatorBanner(
                    sessionType = SessionType.fromValue(activeOrPending.sessionType),
                    expiresAt = activeOrPending.expiresAt,
                    encryptionFingerprint = activeOrPending.encryptionFingerprint,
                  )
                } else {
                  Text(
                    text = "Awaiting Child Consent: ${SessionType.fromValue(activeOrPending.sessionType).label}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "The child device has received a transparent approval request.",
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                  onClick = { onOpenSession(activeOrPending) },
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_active_session_button"),
                ) {
                  Text("Open Live Session Viewer")
                }
              }
            }
          }
        }

        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Text(
                text = "Consent-Gated Live Sessions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = "Every live camera, microphone, or screen session requires explicit child approval and shows a visible indicator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(12.dp))

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5L, 10L, 15L).forEach { mins ->
                  FilterChip(
                    selected = selectedDuration == mins,
                    onClick = { selectedDuration = mins },
                    label = { Text("${mins}m expiry") },
                  )
                }
              }

              Spacer(Modifier.height(12.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                SessionActionButton(
                  label = "Camera",
                  icon = Icons.Filled.Videocam,
                  enabled = activeOrPending == null,
                  testTag = "request_camera_button",
                  modifier = Modifier.weight(1f),
                  onClick = {
                    val rtc = WebRtcSessionManager(context)
                    rtc.createOffer { offer ->
                      onRequestSession(device, SessionType.CAMERA, selectedDuration, offer)
                      rtc.release()
                    }
                  },
                )
                SessionActionButton(
                  label = "Live Mic",
                  icon = Icons.Filled.Mic,
                  enabled = activeOrPending == null,
                  testTag = "request_mic_button",
                  modifier = Modifier.weight(1f),
                  onClick = {
                    val rtc = WebRtcSessionManager(context)
                    rtc.createOffer { offer ->
                      onRequestSession(device, SessionType.MICROPHONE, selectedDuration, offer)
                      rtc.release()
                    }
                  },
                )
                SessionActionButton(
                  label = "Screen",
                  icon = Icons.Filled.ScreenShare,
                  enabled = activeOrPending == null,
                  testTag = "request_screen_button",
                  modifier = Modifier.weight(1f),
                  onClick = {
                    val rtc = WebRtcSessionManager(context)
                    rtc.createOffer { offer ->
                      onRequestSession(device, SessionType.SCREEN, selectedDuration, offer)
                      rtc.release()
                    }
                  },
                )
              }
            }
          }
        }

        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Screen-Time & App Usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              }
              Spacer(Modifier.height(10.dp))
              val progress = (device.dailyScreenTimeMinutes.toFloat() / device.screenTimeLimitMinutes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${device.dailyScreenTimeMinutes} min used today", style = MaterialTheme.typography.bodyMedium)
                Text("Limit: ${device.screenTimeLimitMinutes} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
              }
              Spacer(Modifier.height(8.dp))
              LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(8.dp)
                  .clip(RoundedCornerShape(50)),
              )
              Spacer(Modifier.height(14.dp))

              val parsedStats = device.parsedUsageStats()
              if (parsedStats.isEmpty()) {
                Text(
                  text = "No app usage telemetry synced yet. Enable Usage Access on the child device to stream daily app stats.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                parsedStats.forEach { stat ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(stat.appName, style = MaterialTheme.typography.bodyMedium)
                    Text("${stat.minutesUsed} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                  }
                }
              }
            }
          }
        }

        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("Location Sharing (Consent-Based)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              }
              Spacer(Modifier.height(8.dp))
              if (device.locationConsentEnabled && device.latitude != null && device.longitude != null) {
                Text(
                  text = device.locationLabel ?: "Shared Area",
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = String.format(Locale.US, "Lat %.5f, Lng %.5f", device.latitude, device.longitude),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Text(
                  text = "Location sharing is paused on ${device.deviceName}. Location only updates when the child enables consent.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }

  if (showEditDialog && device != null) {
    var nameInput by remember(device.deviceName) { mutableStateOf(device.deviceName) }
    var limitFloat by remember(device.screenTimeLimitMinutes) { mutableFloatStateOf(device.screenTimeLimitMinutes.toFloat()) }

    AlertDialog(
      onDismissRequest = { showEditDialog = false },
      title = { Text("Device Settings") },
      text = {
        Column {
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it.take(40) },
            label = { Text("Device Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(16.dp))
          Text("Daily Screen-Time Goal: ${limitFloat.toInt()} min", style = MaterialTheme.typography.bodyMedium)
          Slider(
            value = limitFloat,
            onValueChange = { limitFloat = it },
            valueRange = 30f..480f,
            steps = 14,
          )
        }
      },
      confirmButton = {
        Button(onClick = {
          onUpdateSettings(device.id, nameInput, limitFloat.toLong())
          showEditDialog = false
        }) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
      },
    )
  }

  if (showDeleteConfirm && device != null) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Remove ${device.deviceName}?") },
      text = { Text("This unpairs the child device from your family console and frees up 1 of your 10 device slots.") },
      confirmButton = {
        Button(
          onClick = {
            showDeleteConfirm = false
            onRemoveDevice(device)
            onBack()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Text("Remove")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
      },
    )
  }
}

@Composable
private fun SessionActionButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  enabled: Boolean,
  testTag: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier
      .height(72.dp)
      .testTag(testTag),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
      Spacer(Modifier.height(4.dp))
      Text(label, style = MaterialTheme.typography.labelMedium)
    }
  }
}
