package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AuditLog
import com.example.data.model.ChildDevice
import com.example.data.model.DeviceRole
import com.example.data.model.FileCategory
import com.example.data.model.SharedFile
import com.example.hardware.PreparedSafFile
import com.example.hardware.SafFileHelper
import com.example.ui.components.Base64ImageViewer
import com.example.ui.components.formatTimestampExact
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.launch

@Composable
fun FileManagerScreen(
  devices: List<ChildDevice>,
  sharedFiles: List<SharedFile>,
  isParent: Boolean,
  onUploadPreparedFile: (ChildDevice, PreparedSafFile) -> Unit,
  onDeleteFile: (SharedFile) -> Unit,
  onFileExported: (ChildDevice?, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }
  var previewFile by remember { mutableStateOf<SharedFile?>(null) }
  var pendingExportFile by remember { mutableStateOf<SharedFile?>(null) }
  var statusNote by remember { mutableStateOf<String?>(null) }
  val targetDevice = devices.firstOrNull()

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
    onResult = { uri ->
      if (uri != null && targetDevice != null) {
        scope.launch {
          val prepared = SafFileHelper.inspectUri(context, uri)
          onUploadPreparedFile(targetDevice, prepared)
          statusNote = "Uploaded ${prepared.fileName} via Photo Picker"
        }
      }
    },
  )

  val safDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri != null && targetDevice != null) {
        scope.launch {
          val prepared = SafFileHelper.inspectUri(context, uri)
          onUploadPreparedFile(targetDevice, prepared)
          statusNote = "Authorized & shared ${prepared.fileName} via Storage Access Framework"
        }
      }
    },
  )

  val safExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*"),
    onResult = { destUri ->
      val file = pendingExportFile
      if (destUri != null && file != null) {
        scope.launch {
          val ok = SafFileHelper.exportSharedFileToUri(context, file, destUri)
          statusNote = if (ok) "Exported ${file.fileName}" else "Export failed"
          if (ok) onFileExported(devices.firstOrNull { it.id == file.deviceId }, file.fileName)
        }
      }
      pendingExportFile = null
    },
  )

  val filteredFiles = remember(sharedFiles, selectedCategory) {
    if (selectedCategory == null) sharedFiles
    else sharedFiles.filter { it.category == selectedCategory!!.value }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .widthIn(max = 820.dp)
        .fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Text(
          text = "Authorized File Manager",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "Uses Android Photo Picker & Storage Access Framework (SAF). Private app folders and unauthorized storage are never accessed.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (targetDevice != null) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
              },
              modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("pick_media_saf_button"),
            ) {
              Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Photo / Video")
            }
            OutlinedButton(
              onClick = { safDocumentLauncher.launch(arrayOf("*/*")) },
              modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("pick_document_saf_button"),
            ) {
              Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("SAF Document")
            }
          }
        }
      }

      if (!statusNote.isNullOrBlank()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = statusNote!!,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }

      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = selectedCategory == null,
            onClick = { selectedCategory = null },
            label = { Text("All (${sharedFiles.size})") },
          )
          FileCategory.entries.forEach { cat ->
            FilterChip(
              selected = selectedCategory == cat,
              onClick = { selectedCategory = cat },
              label = { Text(cat.label) },
            )
          }
        }
      }

      if (filteredFiles.isEmpty()) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(40.dp))
              Spacer(Modifier.height(10.dp))
              Text("No authorized files shared yet", style = MaterialTheme.typography.titleMedium)
              Text(
                text = if (isParent) "Child or Parent can share explicit photos, videos, or documents via Android SAF." else "Tap Photo/Video or SAF Document above to share selected items with your parent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(filteredFiles, key = { it.id }) { file ->
          SharedFileCard(
            file = file,
            onPreview = { previewFile = file },
            onDownload = {
              pendingExportFile = file
              safExportLauncher.launch(file.fileName)
            },
            onDelete = { onDeleteFile(file) },
          )
        }
      }
    }
  }

  previewFile?.let { file ->
    AlertDialog(
      onDismissRequest = { previewFile = null },
      title = { Text(file.fileName) },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          Text(
            text = "${file.mimeType} • ${SafFileHelper.formatFileSize(file.sizeBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(10.dp))
          if (!file.thumbnailBase64.isNullOrBlank()) {
            Base64ImageViewer(
              base64Data = file.thumbnailBase64,
              contentDescription = file.fileName,
              placeholderText = "Preview unavailable",
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp)),
            )
          } else if (!file.textPreview.isNullOrBlank()) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = file.textPreview,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { previewFile = null }) { Text("Close") }
      },
    )
  }
}

@Composable
private fun SharedFileCard(
  file: SharedFile,
  onPreview: () -> Unit,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
) {
  val icon = when (FileCategory.fromValue(file.category)) {
    FileCategory.PHOTO -> Icons.Filled.Image
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.DOCUMENT -> Icons.Filled.Description
  }
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onPreview),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.size(44.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = file.category, tint = MaterialTheme.colorScheme.primary)
          }
        }
        Spacer(Modifier.width(12.dp))
        Column {
          Text(file.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            text = "${SafFileHelper.formatFileSize(file.sizeBytes)} • Shared by ${file.uploaderRole}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Row {
        IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Download, contentDescription = "Save via SAF")
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove shared file", tint = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
fun AuditLogsScreen(
  logs: List<AuditLog>,
  modifier: Modifier = Modifier,
) {
  var filter by remember { mutableStateOf("all") }
  val filtered = remember(logs, filter) {
    when (filter) {
      "sessions" -> logs.filter {
        it.eventType.contains("camera") || it.eventType.contains("mic") || it.eventType.contains("screen") || it.eventType.contains("session")
      }
      "pairing" -> logs.filter { it.eventType.contains("pair") || it.eventType.contains("device") }
      "files" -> logs.filter { it.eventType.contains("file") || it.eventType.contains("location") }
      else -> logs
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .widthIn(max = 820.dp)
        .fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text(
          text = "Security Audit Logs",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "Immutable, append-only Firestore trail for every pairing, consent decision, live session, and file transfer.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("all" to "All", "sessions" to "Sessions", "pairing" to "Pairing", "files" to "Files & GPS").forEach { (key, label) ->
            FilterChip(
              selected = filter == key,
              onClick = { filter = key },
              label = { Text(label) },
            )
          }
        }
      }

      if (filtered.isEmpty()) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(36.dp))
              Spacer(Modifier.height(8.dp))
              Text("No audit events recorded yet", style = MaterialTheme.typography.titleSmall)
            }
          }
        }
      } else {
        items(filtered, key = { it.id }) { log ->
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = log.eventType.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                  )
                }
                Text(
                  text = formatTimestampExact(log.createdAt),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(Modifier.height(8.dp))
              Text(log.details, style = MaterialTheme.typography.bodyMedium)
              Spacer(Modifier.height(4.dp))
              Text(
                text = "Device: ${log.deviceName} • Actor: ${log.actorRole}",
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

@Composable
fun SettingsScreen(
  userName: String,
  userEmail: String,
  currentRole: DeviceRole,
  themePreference: ThemePreference,
  onSelectTheme: (ThemePreference) -> Unit,
  onSwitchRole: () -> Unit,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
      modifier = Modifier
        .widthIn(max = 720.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = "Settings & Security",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Text(userName.ifBlank { "Google Account" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(userEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))
          Text(
            text = "Current Mode: ${if (currentRole == DeviceRole.PARENT) "Parent Guardian Console" else "Child Protected Device"}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Appearance Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreference.entries.forEach { pref ->
              FilterChip(
                selected = themePreference == pref,
                onClick = { onSelectTheme(pref) },
                label = { Text(pref.label) },
              )
            }
          }
        }
      }

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text("Security & Transparency Guarantees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          }
          Text("• Firebase Auth Google Sign-In & Role-Based Firestore Security Rules", style = MaterialTheme.typography.bodySmall)
          Text("• WebRTC DTLS-SRTP Encrypted Live Streams with Auto-Expiring Sessions", style = MaterialTheme.typography.bodySmall)
          Text("• Foreground Services & Mandatory Notifications for Camera, Mic, Screen", style = MaterialTheme.typography.bodySmall)
          Text("• Zero-Bypass Storage Access Framework & Photo Picker", style = MaterialTheme.typography.bodySmall)
        }
      }

      OutlinedButton(
        onClick = onSwitchRole,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .testTag("switch_role_button"),
      ) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Switch Parent / Child Mode")
      }

      Button(
        onClick = onSignOut,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .testTag("sign_out_button"),
      ) {
        Icon(Icons.Filled.Logout, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Sign Out")
      }
    }
  }
}
