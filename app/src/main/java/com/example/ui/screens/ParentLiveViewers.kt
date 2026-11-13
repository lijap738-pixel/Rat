package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MonitoringSession
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.hardware.WebRtcSessionManager
import com.example.ui.components.AudioLevelVisualizer
import com.example.ui.components.Base64ImageViewer
import com.example.ui.components.LiveSessionIndicatorBanner
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentLiveViewerScreen(
  session: MonitoringSession?,
  sessionType: SessionType,
  onStopSession: (MonitoringSession) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val rtcManager = remember { WebRtcSessionManager(context) }
  val iceState by rtcManager.iceState.collectAsStateWithLifecycle()

  DisposableEffect(Unit) {
    onDispose { rtcManager.release() }
  }

  LaunchedEffect(session?.sdpAnswer) {
    if (!session?.sdpAnswer.isNullOrBlank()) {
      rtcManager.applyRemoteAnswer(session?.sdpAnswer)
    }
  }

  LaunchedEffect(session?.iceCandidates) {
    session?.iceCandidates?.forEach { rtcManager.addRemoteIceCandidate(it) }
  }

  val screenTitle = when (sessionType) {
    SessionType.CAMERA -> "Live Camera Feed"
    SessionType.MICROPHONE -> "Live Microphone Session"
    SessionType.SCREEN -> "Screen Mirroring Viewer"
    SessionType.NONE -> "Live Session"
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(screenTitle) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 760.dp)
          .fillMaxSize()
          .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        if (session == null) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Session not found.", style = MaterialTheme.typography.bodyLarge)
          }
          return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          when (session.status) {
            SessionStatus.ACTIVE.value -> {
              LiveSessionIndicatorBanner(
                sessionType = sessionType,
                expiresAt = session.expiresAt,
                encryptionFingerprint = "${session.encryptionFingerprint} • WebRTC $iceState",
              )
            }
            SessionStatus.REQUESTED.value -> {
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(18.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                  Spacer(Modifier.width(14.dp))
                  Column {
                    Text(
                      text = "Waiting for ${session.deviceName} Consent...",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                    )
                    Text(
                      text = "The child user sees an explicit approval prompt and notification before any feed begins.",
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                }
              }
            }
            else -> {
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(18.dp)) {
                  Text(
                    text = "Session ${session.status.uppercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = session.endedReason ?: "This monitoring session has closed.",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                }
              }
            }
          }

          when (sessionType) {
            SessionType.CAMERA -> {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(380.dp)
                  .clip(RoundedCornerShape(22.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
              ) {
                Base64ImageViewer(
                  base64Data = session.previewFrameBase64,
                  contentDescription = "Live child camera feed",
                  placeholderText = if (session.status == SessionStatus.ACTIVE.value) {
                    "Encrypted WebRTC Camera stream connected — awaiting frame..."
                  } else {
                    "Camera preview stays hidden until child approves"
                  },
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
            SessionType.MICROPHONE -> {
              Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              ) {
                Column(
                  modifier = Modifier.padding(20.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(64.dp),
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Live microphone",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp),
                      )
                    }
                  }
                  Spacer(Modifier.height(14.dp))
                  Text(
                    text = if (session.status == SessionStatus.ACTIVE.value) {
                      "Live Audio Level: ${session.audioLevelDb} dB"
                    } else {
                      "Live Audio Paused"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                  )
                  Spacer(Modifier.height(16.dp))
                  AudioLevelVisualizer(
                    levelDb = session.audioLevelDb.toInt(),
                    isActive = session.status == SessionStatus.ACTIVE.value,
                  )
                  Spacer(Modifier.height(12.dp))
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                      text = "Real-time stream only • Never secretly recorded",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
            SessionType.SCREEN -> {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(420.dp)
                  .clip(RoundedCornerShape(22.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
              ) {
                Base64ImageViewer(
                  base64Data = session.previewFrameBase64,
                  contentDescription = "Shared child device screen",
                  placeholderText = if (session.status == SessionStatus.ACTIVE.value) {
                    "MediaProjection screen share active — awaiting frames..."
                  } else {
                    "Child must approve Android MediaProjection dialog to share screen"
                  },
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
            SessionType.NONE -> Unit
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (sessionType) {
                  SessionType.CAMERA -> Icons.Filled.Videocam
                  SessionType.MICROPHONE -> Icons.Filled.Mic
                  SessionType.SCREEN -> Icons.Filled.ScreenShare
                  SessionType.NONE -> Icons.Filled.Lock
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                  text = "DTLS-SRTP Verified",
                  style = MaterialTheme.typography.labelLarge,
                )
              }
              Text(
                text = session.encryptionFingerprint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }

        if (!session.isTerminal()) {
          Button(
            onClick = { onStopSession(session) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp)
              .testTag("stop_live_session_button"),
          ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stop ${sessionType.label} Session", style = MaterialTheme.typography.labelLarge)
          }
        } else {
          Button(
            onClick = onBack,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp),
          ) {
            Text("Return to Device Console")
          }
        }
      }
    }
  }
}
