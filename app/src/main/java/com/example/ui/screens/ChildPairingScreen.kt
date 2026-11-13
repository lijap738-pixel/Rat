package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChildDevice
import com.example.data.model.PairedLink
import com.example.hardware.DeviceTelemetryHelper

@Composable
fun ChildPairingScreen(
  savedLinks: List<PairedLink>,
  isPairing: Boolean,
  errorMessage: String?,
  onClaimCode: (code: String, deviceName: String, model: String, osVersion: String, batteryPct: Int, isCharging: Boolean) -> Unit,
  onSelectSavedLink: (PairedLink) -> Unit,
  onSwitchToParent: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var codeInput by remember { mutableStateOf("") }
  var nameInput by remember { mutableStateOf("${Build.MODEL} (Child)") }

  Box(
    modifier = modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 540.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Pair Child Device",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onSwitchToParent) {
          Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text("Parent Mode")
        }
      }

      Image(
        painter = painterResource(id = R.drawable.img_empty_devices),
        contentDescription = "Pair child device illustration",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(160.dp)
          .clip(RoundedCornerShape(20.dp)),
      )

      Text(
        text = "Enter the 6-character pairing code shown on your Parent Guardian Console. All monitoring features remain visible and consent-controlled.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (!errorMessage.isNullOrBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      OutlinedTextField(
        value = codeInput,
        onValueChange = { codeInput = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(12) },
        label = { Text("6-Character Pairing Code") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("pairing_code_input"),
      )

      OutlinedTextField(
        value = nameInput,
        onValueChange = { nameInput = it.take(50) },
        label = { Text("Device Nickname") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("child_device_name_input"),
      )

      Button(
        onClick = {
          val battery = DeviceTelemetryHelper.readBattery(context)
          onClaimCode(
            codeInput,
            nameInput.ifBlank { Build.MODEL },
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE}",
            battery.levelPercent,
            battery.isCharging,
          )
        },
        enabled = !isPairing && codeInput.length >= 6,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("pair_device_button"),
      ) {
        if (isPairing) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        } else {
          Icon(Icons.Filled.Link, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Link Device to Family")
        }
      }

      if (savedLinks.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Previously Paired Families",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        savedLinks.forEach { link ->
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .clickable { onSelectSavedLink(link) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.width(12.dp))
              Column {
                Text(link.deviceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Tap to reconnect to family console", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }
}
