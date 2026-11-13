package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AuditLog
import com.example.data.model.ChildDevice
import com.example.data.model.FamilyAccount
import com.example.data.model.MonitoringSession
import com.example.data.model.PairingCode
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.data.model.SharedFile
import com.example.data.repository.SafeNestRepository
import com.example.hardware.PreparedSafFile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ParentDashboardUiState(
  val isLoading: Boolean = true,
  val family: FamilyAccount? = null,
  val devices: List<ChildDevice> = emptyList(),
  val pairingCodes: List<PairingCode> = emptyList(),
  val sessions: List<MonitoringSession> = emptyList(),
  val sharedFiles: List<SharedFile> = emptyList(),
  val auditLogs: List<AuditLog> = emptyList(),
  val errorMessage: String? = null,
  val bannerMessage: String? = null,
  val lastGeneratedCode: PairingCode? = null,
)

class ParentViewModel(
  private val repository: SafeNestRepository,
  private val currentUserId: String,
  displayName: String,
  email: String?,
) : ViewModel() {

  private val _errorMessage = MutableStateFlow<String?>(null)
  private val _bannerMessage = MutableStateFlow<String?>(null)
  private val _lastGeneratedCode = MutableStateFlow<PairingCode?>(null)

  private fun unwrapError(error: Throwable, tag: String): String {
    val firestoreError = generateSequence(error) { it.cause }
      .filterIsInstance<FirebaseFirestoreException>()
      .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val root = firestoreError ?: error
    Log.w(TAG, "$tag error", root)
    return root.message ?: "Unable to sync family data"
  }

  private val familyFlow = repository.observeFamily(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "family"); emit(null) }

  private val devicesFlow = repository.observeDevices(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "devices"); emit(emptyList()) }

  private val codesFlow = repository.observePairingCodes(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "codes"); emit(emptyList()) }

  private val sessionsFlow = repository.observeSessions(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "sessions"); emit(emptyList()) }

  private val filesFlow = repository.observeSharedFiles(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "files"); emit(emptyList()) }

  private val logsFlow = repository.observeAuditLogs(currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "logs"); emit(emptyList()) }

  private val coreState = combine(
    familyFlow,
    devicesFlow,
    codesFlow,
    sessionsFlow,
    filesFlow,
  ) { family, devices, codes, sessions, files ->
    ParentDashboardUiState(
      isLoading = false,
      family = family,
      devices = devices,
      pairingCodes = codes,
      sessions = sessions,
      sharedFiles = files,
    )
  }

  val uiState: StateFlow<ParentDashboardUiState> = combine(
    coreState,
    logsFlow,
    _errorMessage.asStateFlow(),
    _bannerMessage.asStateFlow(),
    _lastGeneratedCode.asStateFlow(),
  ) { base, logs, err, banner, code ->
    base.copy(
      auditLogs = logs,
      errorMessage = err,
      bannerMessage = banner,
      lastGeneratedCode = code,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000L),
    initialValue = ParentDashboardUiState(isLoading = true),
  )

  init {
    viewModelScope.launch {
      repository.ensureFamilyExists(displayName, email)
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Failed to initialize family profile" }
    }
    startSessionExpirationWatchdog()
  }

  private fun startSessionExpirationWatchdog() {
    viewModelScope.launch {
      while (isActive) {
        delay(5000L)
        val now = System.currentTimeMillis()
        val expiredSessions = uiState.value.sessions.filter { session ->
          !session.isTerminal() && (session.expiresAt?.toDate()?.time ?: Long.MAX_VALUE) <= now
        }
        expiredSessions.forEach { session ->
          repository.endMonitoringSession(
            familyId = currentUserId,
            session = session,
            actorRole = "system",
            reason = "Session expired automatically after ${session.requestedDurationMinutes} minutes",
            expired = true,
          )
        }
      }
    }
  }

  fun clearMessages() {
    _errorMessage.value = null
    _bannerMessage.value = null
  }

  fun generatePairingCode(deviceLabel: String) {
    viewModelScope.launch {
      repository.generatePairingCode(deviceLabel)
        .onSuccess { code ->
          _lastGeneratedCode.value = code
          _bannerMessage.value = "Pairing code ${code.code} created (valid 15m)"
        }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Could not generate pairing code" }
    }
  }

  fun revokePairingCode(code: String) {
    viewModelScope.launch {
      repository.revokePairingCode(code)
        .onSuccess { _bannerMessage.value = "Pairing code $code revoked" }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Could not revoke code" }
    }
  }

  fun updateDeviceSettings(deviceId: String, name: String, screenTimeLimitMinutes: Long) {
    viewModelScope.launch {
      repository.updateDeviceSettings(currentUserId, deviceId, name, screenTimeLimitMinutes)
        .onSuccess { _bannerMessage.value = "Device settings saved" }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Failed to update settings" }
    }
  }

  fun removeDevice(device: ChildDevice) {
    viewModelScope.launch {
      repository.removeChildDevice(currentUserId, device)
        .onSuccess { _bannerMessage.value = "${device.deviceName} removed from family" }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Failed to remove device" }
    }
  }

  fun requestSession(
    device: ChildDevice,
    sessionType: SessionType,
    durationMinutes: Long,
    sdpOffer: String?,
    onSessionCreated: (MonitoringSession) -> Unit = {},
  ) {
    viewModelScope.launch {
      repository.requestMonitoringSession(
        familyId = currentUserId,
        device = device,
        sessionType = sessionType,
        durationMinutes = durationMinutes,
        sdpOffer = sdpOffer,
      ).onSuccess { session ->
        _bannerMessage.value = "Requested ${sessionType.label} — waiting for child approval"
        onSessionCreated(session)
      }.onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to request session"
      }
    }
  }

  fun endSession(session: MonitoringSession, reason: String = "Stopped by parent") {
    viewModelScope.launch {
      repository.endMonitoringSession(
        familyId = currentUserId,
        session = session,
        actorRole = "parent",
        reason = reason,
      ).onSuccess {
        _bannerMessage.value = "${SessionType.fromValue(session.sessionType).label} session stopped"
      }.onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to stop session"
      }
    }
  }

  fun shareAuthorizedFile(device: ChildDevice, prepared: PreparedSafFile) {
    viewModelScope.launch {
      repository.shareAuthorizedFile(
        familyId = currentUserId,
        device = device,
        uploaderRole = "parent",
        fileName = prepared.fileName,
        mimeType = prepared.mimeType,
        sizeBytes = prepared.sizeBytes,
        category = prepared.category,
        thumbnailBase64 = prepared.thumbnailBase64,
        textPreview = prepared.textPreview,
        localUriString = prepared.localUriString,
      ).onSuccess {
        _bannerMessage.value = "Shared ${prepared.fileName} with ${device.deviceName}"
      }.onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to share file"
      }
    }
  }

  fun deleteSharedFile(file: SharedFile) {
    viewModelScope.launch {
      repository.deleteSharedFile(currentUserId, file)
        .onSuccess { _bannerMessage.value = "Removed ${file.fileName}" }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Failed to remove file" }
    }
  }

  fun logFileDownload(device: ChildDevice, fileName: String) {
    viewModelScope.launch {
      repository.appendAuditLog(
        familyId = currentUserId,
        actorRole = "parent",
        childUid = device.childUid,
        deviceId = device.id,
        deviceName = device.deviceName,
        eventType = "file_downloaded",
        details = "Parent exported authorized file via SAF: $fileName",
      )
    }
  }

  private companion object {
    const val TAG = "ParentViewModel"
  }
}
