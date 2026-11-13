package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChildDevice
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.data.repository.SafeNestRepository
import com.example.ui.components.BatteryStatusPill
import com.example.ui.components.LiveSessionIndicatorBanner
import com.example.ui.components.OnlineStatusPill
import com.example.ui.components.PairingCodeDisplayCard
import com.example.ui.components.formatRelativeTime
import com.example.ui.viewmodel.ParentDashboardUiState

@Composable
fun ParentDashboardScreen(
  uiState: ParentDashboardUiState,
  onGenerateCode: (String) -> Unit,
  onRevokeCode: (String) -> Unit,
  onOpenDevice: (ChildDevice) -> Unit,
  onOpenLiveSession: (String, String) -> Unit,
  onClearMessages: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showAddDialog by remember { mutableStateOf(false) }
  var deviceLabelInput by remember { mutableStateOf("") }
  val deviceCount = uiState.devices.size
  val maxDevices = SafeNestRepository.MAX_CHILD_DEVICES.toInt()
  val pendingCodes = remember(uiState.pairingCodes) {
    uiState.pairingCodes.filter {
      it.status == "pending" && (it.expiresAt?.toDate()?.time ?: 0L) > System.currentTimeMillis()
    }
  }
  val activeSessions = remember(uiState.sessions) {
    uiState.sessions.filter { it.status == SessionStatus.ACTIVE.value }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddDialog = true },
        modifier = Modifier.testTag("add_device_button"),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Pair child device")
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .widthIn(max = 820.dp)
            .fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          item {
            ParentHeroCard(
              parentName = uiState.family?.parentName ?: "Parent Guardian",
              deviceCount = deviceCount,
              maxDevices = maxDevices,
              onlineCount = uiState.devices.count { it.isOnline },
            )
          }

          if (!uiState.errorMessage.isNullOrBlank() || !uiState.bannerMessage.isNullOrBlank()) {
            item {
              val isErr = !uiState.errorMessage.isNullOrBlank()
              Surface(
                color = if (isErr) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = uiState.errorMessage ?: uiState.bannerMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                  )
                  IconButton(onClick = onClearMessages, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss message")
                  }
                }
              }
            }
          }

          if (activeSessions.isNotEmpty()) {
            items(activeSessions, key = { "active_${it.id}" }) { session ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(16.dp))
                  .clickable { onOpenLiveSession(session.id, session.sessionType) },
              ) {
                LiveSessionIndicatorBanner(
                  sessionType = SessionType.fromValue(session.sessionType),
                  expiresAt = session.expiresAt,
                  encryptionFingerprint = "${session.deviceName} • Tap to view",
                )
              }
            }
          }

          if (pendingCodes.isNotEmpty()) {
            item {
              Text(
                text = "Pending Pairing Codes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
            }
            items(pendingCodes, key = { it.code }) { codeObj ->
              Column {
                PairingCodeDisplayCard(
                  code = codeObj.code,
                  deviceLabel = codeObj.deviceLabel,
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                ) {
                  TextButton(onClick = { onRevokeCode(codeObj.code) }) {
                    Text("Revoke Code")
                  }
                }
              }
            }
          }

          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Connected Child Devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "$deviceCount / $maxDevices",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }

          if (uiState.devices.isEmpty()) {
            item {
              EmptyDevicesCard(onAddClick = { showAddDialog = true })
            }
          } else {
            items(uiState.devices, key = { it.id }) { device ->
              ChildDeviceSummaryCard(
                device = device,
                onClick = { onOpenDevice(device) },
              )
            }
          }

          item { Spacer(Modifier.height(72.dp)) }
        }
      }
    }
  }

  if (showAddDialog) {
    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("Pair New Child Device") },
      text = {
        Column {
          Text(
            text = if (deviceCount >= maxDevices) {
              "You have reached the maximum of $maxDevices connected child devices. Remove a device before pairing another."
            } else {
              "Generate a secure one-time 6-character pairing code ($deviceCount of $maxDevices slots used)."
            },
            style = MaterialTheme.typography.bodyMedium,
          )
          if (deviceCount < maxDevices) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
              value = deviceLabelInput,
              onValueChange = { deviceLabelInput = it.take(40) },
              label = { Text("Child Device Name (e.g. Maya Pixel)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("new_device_label_input"),
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (deviceCount < maxDevices) {
              onGenerateCode(deviceLabelInput.ifBlank { "Child Device ${deviceCount + 1}" })
              deviceLabelInput = ""
            }
            showAddDialog = false
          },
          enabled = deviceCount < maxDevices,
          modifier = Modifier.testTag("confirm_generate_code_button"),
        ) {
          Text("Generate Code")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun ParentHeroCard(
  parentName: String,
  deviceCount: Int,
  maxDevices: Int,
  onlineCount: Int,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(124.dp),
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_hero_family_shield),
          contentDescription = "Family guardian header",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(12.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Shield,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = "$parentName's Family",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }

      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
              text = "$deviceCount of $maxDevices Child Devices Paired",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
          }
          Text(
            text = "$onlineCount online",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { (deviceCount.toFloat() / maxDevices.toFloat()).coerceIn(0f, 1f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50)),
        )
      }
    }
  }
}

@Composable
private fun EmptyDevicesCard(onAddClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_empty_devices),
        contentDescription = "No child devices connected",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(160.dp)
          .clip(RoundedCornerShape(16.dp)),
      )
      Spacer(Modifier.height(16.dp))
      Text(
        text = "No Child Devices Connected Yet",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "Tap below to generate a secure pairing code. Enter it on the child device to link up to 10 family devices.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      Button(
        onClick = onAddClick,
        modifier = Modifier
          .height(48.dp)
          .testTag("empty_pair_device_button"),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Generate Pairing Code")
      }
    }
  }
}

@Composable
private fun ChildDeviceSummaryCard(
  device: ChildDevice,
  onClick: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .testTag("device_card_${device.id}"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(46.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = device.deviceName,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
          Spacer(Modifier.width(12.dp))
          Column {
            Text(
              text = device.deviceName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "${device.deviceModel} • ${device.osVersion}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        OnlineStatusPill(isOnline = device.isOnline)
      }

      Spacer(Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        BatteryStatusPill(batteryLevel = device.batteryLevel, isCharging = device.isCharging)

        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = "${device.dailyScreenTimeMinutes}m / ${device.screenTimeLimitMinutes}m",
            style = MaterialTheme.typography.labelMedium,
          )
        }

        Text(
          text = "Active ${formatRelativeTime(device.lastActiveAt)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Icon(
          imageVector = Icons.Filled.ChevronRight,
          contentDescription = "Manage ${device.deviceName}",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
