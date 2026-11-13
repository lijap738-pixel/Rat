package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SessionType
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusLiveAlert
import com.example.ui.theme.StatusOffline
import com.example.ui.theme.StatusOnline
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun LiveSessionIndicatorBanner(
  sessionType: SessionType,
  expiresAt: Timestamp?,
  encryptionFingerprint: String,
  modifier: Modifier = Modifier,
) {
  val transition = rememberInfiniteTransition(label = "live_pulse")
  val pulseAlpha by transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(750),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "pulse_alpha",
  )

  var remainingSec by remember(expiresAt) {
    mutableLongStateOf(((expiresAt?.toDate()?.time ?: System.currentTimeMillis()) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
  }
  LaunchedEffect(expiresAt) {
    while (true) {
      val diff = ((expiresAt?.toDate()?.time ?: System.currentTimeMillis()) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L
      remainingSec = diff
      delay(1000L)
    }
  }

  val icon = when (sessionType) {
    SessionType.CAMERA -> Icons.Filled.Videocam
    SessionType.MICROPHONE -> Icons.Outlined.Mic
    SessionType.SCREEN -> Icons.Outlined.ScreenShare
    SessionType.NONE -> Icons.Filled.Lock
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = StatusLiveAlert.copy(alpha = 0.12f),
    shape = RoundedCornerShape(16.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, StatusLiveAlert.copy(alpha = 0.4f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .alpha(pulseAlpha)
            .clip(CircleShape)
            .background(StatusLiveAlert),
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = "${sessionType.label} active", tint = StatusLiveAlert, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
          Text(
            text = "${sessionType.label.uppercase(Locale.US)} ACTIVE",
            style = MaterialTheme.typography.labelLarge,
            color = StatusLiveAlert,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = encryptionFingerprint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      val mins = remainingSec / 60
      val secs = remainingSec % 60
      Surface(
        color = StatusLiveAlert,
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = String.format(Locale.US, "%02d:%02d", mins, secs),
          style = MaterialTheme.typography.labelMedium,
          fontFamily = JetBrainsMonoFamily,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }
  }
}

@Composable
fun OnlineStatusPill(isOnline: Boolean, modifier: Modifier = Modifier) {
  val color = if (isOnline) StatusOnline else StatusOffline
  val label = if (isOnline) "Online" else "Offline"
  Surface(
    modifier = modifier,
    color = color.copy(alpha = 0.14f),
    shape = RoundedCornerShape(50),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(color),
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
fun BatteryStatusPill(batteryLevel: Long, isCharging: Boolean, modifier: Modifier = Modifier) {
  val tint = when {
    isCharging -> MaterialTheme.colorScheme.secondary
    batteryLevel <= 20L -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
  }
  Row(
    modifier = modifier.semantics { contentDescription = "Battery $batteryLevel percent" },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.width(3.dp))
    Text(
      text = "${batteryLevel}%",
      style = MaterialTheme.typography.labelMedium,
      fontFamily = JetBrainsMonoFamily,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
fun PairingCodeDisplayCard(
  code: String,
  deviceLabel: String,
  onCopied: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val clipboardManager = LocalClipboardManager.current
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "PAIRING CODE FOR ${deviceLabel.uppercase(Locale.US)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = code.chunked(3).joinToString(" "),
          style = MaterialTheme.typography.headlineMedium,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Expires in 15 minutes • One-time use",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
      }
      IconButton(
        onClick = {
          clipboardManager.setText(AnnotatedString(code))
          onCopied()
        },
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = "Copy pairing code",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
fun Base64ImageViewer(
  base64Data: String?,
  contentDescription: String,
  placeholderText: String,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
) {
  val bitmap = remember(base64Data) {
    if (base64Data.isNullOrBlank()) null
    else runCatching {
      val bytes = Base64.decode(base64Data, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
  }

  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier,
    )
  } else {
    Box(
      modifier = modifier
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = placeholderText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}

@Composable
fun AudioLevelVisualizer(
  levelDb: Int,
  isActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val barCount = 18
  val primary = MaterialTheme.colorScheme.secondary
  val track = MaterialTheme.colorScheme.surfaceVariant
  val alert = StatusLiveAlert

  val transition = rememberInfiniteTransition(label = "mic_wave")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28f,
    animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Restart),
    label = "wave_phase",
  )

  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(92.dp),
  ) {
    val barWidth = size.width / (barCount * 1.6f)
    val gap = barWidth * 0.6f
    val normalized = (levelDb / 100f).coerceIn(0.08f, 1f)
    for (i in 0 until barCount) {
      val waveFactor = if (isActive) {
        0.55f + 0.45f * kotlin.math.sin(phase + i * 0.6f).coerceIn(-1f, 1f)
      } else {
        0.15f
      }
      val barHeight = (size.height * normalized * waveFactor).coerceAtLeast(10f)
      val x = i * (barWidth + gap) + gap / 2f
      val y = (size.height - barHeight) / 2f
      val color = when {
        !isActive -> track
        levelDb > 75 && i > barCount - 5 -> alert
        else -> primary
      }
      drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
      )
    }
  }
}

fun formatRelativeTime(timestamp: Timestamp?): String {
  if (timestamp == null) return "Just now"
  val diffMs = (System.currentTimeMillis() - timestamp.toDate().time).coerceAtLeast(0L)
  val mins = diffMs / 60_000L
  return when {
    mins < 1L -> "Just now"
    mins < 60L -> "${mins}m ago"
    mins < 1440L -> "${mins / 60L}h ago"
    else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp.toDate().time))
  }
}

fun formatTimestampExact(timestamp: Timestamp?): String {
  if (timestamp == null) return "--"
  return SimpleDateFormat("MMM d • HH:mm:ss", Locale.getDefault()).format(timestamp.toDate())
}
