package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AuditLog
import com.example.data.model.ChildDevice
import com.example.data.model.MonitoringSession
import com.example.data.model.SessionStatus
import com.example.data.model.SharedFile
import com.example.data.repository.SafeNestRepository
import com.example.hardware.BatterySnapshot
import com.example.hardware.LocationSnapshot
import com.example.hardware.PreparedSafFile
import com.example.hardware.UsageSnapshot
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

data class ChildDashboardUiState(
  val isLoading: Boolean = true,
  val device: ChildDevice? = null,
  val pendingRequests: List<MonitoringSession> = emptyList(),
  val activeSession: MonitoringSession? = null,
  val sharedFiles: List<SharedFile> = emptyList(),
  val auditLogs: List<AuditLog> = emptyList(),
  val errorMessage: String? = null,
  val bannerMessage: String? = null,
)

class ChildViewModel(
  private val repository: SafeNestRepository,
  private val currentUserId: String,
  private val familyId: String,
  private val deviceId: String,
) : ViewModel() {

  private val _errorMessage = MutableStateFlow<String?>(null)
  private val _bannerMessage = MutableStateFlow<String?>(null)

  private fun unwrapError(error: Throwable, tag: String): String {
    val firestoreError = generateSequence(error) { it.cause }
      .filterIsInstance<FirebaseFirestoreException>()
      .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val root = firestoreError ?: error
    Log.w(TAG, "$tag error", root)
    return root.message ?: "Unable to sync child device"
  }

  private val deviceFlow = repository.observeSingleDevice(familyId, deviceId)
    .catch { err -> _errorMessage.value = unwrapError(err, "device"); emit(null) }

  private val sessionsFlow = repository.observeSessions(familyId, currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "sessions"); emit(emptyList()) }

  private val filesFlow = repository.observeSharedFiles(familyId, currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "files"); emit(emptyList()) }

  private val logsFlow = repository.observeAuditLogs(familyId, currentUserId)
    .catch { err -> _errorMessage.value = unwrapError(err, "logs"); emit(emptyList()) }

  private val coreFlow = combine(deviceFlow, sessionsFlow, filesFlow, logsFlow) { device, sessions, files, logs ->
    val deviceSessions = sessions.filter { it.deviceId == deviceId }
    val pending = deviceSessions.filter { it.status == SessionStatus.REQUESTED.value }
    val active = deviceSessions.firstOrNull { it.status == SessionStatus.ACTIVE.value }
    ChildDashboardUiState(
      isLoading = false,
      device = device,
      pendingRequests = pending,
      activeSession = active,
      sharedFiles = files,
      auditLogs = logs,
    )
  }

  val uiState: StateFlow<ChildDashboardUiState> = combine(
    coreFlow,
    _errorMessage.asStateFlow(),
    _bannerMessage.asStateFlow(),
  ) { base, err, banner ->
    base.copy(errorMessage = err, bannerMessage = banner)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000L),
    initialValue = ChildDashboardUiState(isLoading = true),
  )

  init {
    viewModelScope.launch {
      while (isActive) {
        delay(5000L)
        val now = System.currentTimeMillis()
        val active = uiState.value.activeSession
        if (active != null && (active.expiresAt?.toDate()?.time ?: Long.MAX_VALUE) <= now) {
          repository.endMonitoringSession(
            familyId = familyId,
            session = active,
            actorRole = "system",
            reason = "Session expired automatically",
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

  fun respondToSession(session: MonitoringSession, approve: Boolean, sdpAnswer: String? = null) {
    viewModelScope.launch {
      repository.respondToSession(familyId, session, approve, sdpAnswer)
        .onSuccess {
          _bannerMessage.value = if (approve) {
            "Session approved — visible indicator active"
          } else {
            "Request declined"
          }
        }
        .onFailure { _errorMessage.value = it.localizedMessage ?: "Failed to update consent" }
    }
  }

  fun endActiveSession(session: MonitoringSession, reason: String = "Stopped by child user") {
    viewModelScope.launch {
      repository.endMonitoringSession(
        familyId = familyId,
        session = session,
        actorRole = "child",
        reason = reason,
      ).onSuccess {
        _bannerMessage.value = "Sharing stopped"
      }.onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to stop sharing"
      }
    }
  }

  fun updateStreamTelemetry(
    sessionId: String,
    previewFrameBase64: String? = null,
    audioLevelDb: Int? = null,
    iceCandidates: List<String>? = null,
  ) {
    viewModelScope.launch {
      repository.updateSessionStreamTelemetry(
        familyId = familyId,
        sessionId = sessionId,
        previewFrameBase64 = previewFrameBase64,
        audioLevelDb = audioLevelDb,
        iceCandidates = iceCandidates,
      )
    }
  }

  fun syncTelemetry(
    battery: BatterySnapshot,
    usage: UsageSnapshot,
    locationConsentEnabled: Boolean,
    location: LocationSnapshot?,
    logLocationChange: Boolean = false,
  ) {
    viewModelScope.launch {
      val currentDevice = uiState.value.device
      repository.updateChildTelemetry(
        familyId = familyId,
        deviceId = deviceId,
        batteryLevel = battery.levelPercent,
        isCharging = battery.isCharging,
        isOnline = true,
        locationConsentEnabled = locationConsentEnabled,
        latitude = location?.latitude,
        longitude = location?.longitude,
        locationLabel = location?.label,
        dailyScreenTimeMinutes = usage.totalMinutesToday,
        appUsageSummary = usage.topApps.map { it.serialize() },
      ).onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to sync telemetry"
      }
      if (logLocationChange && currentDevice != null) {
        repository.appendAuditLog(
          familyId = familyId,
          actorRole = "child",
          childUid = currentUserId,
          deviceId = deviceId,
          deviceName = currentDevice.deviceName,
          eventType = "location_toggled",
          details = if (locationConsentEnabled) "Child enabled location sharing" else "Child paused location sharing",
        )
      }
    }
  }

  fun shareAuthorizedFile(prepared: PreparedSafFile) {
    val dev = uiState.value.device ?: return
    viewModelScope.launch {
      repository.shareAuthorizedFile(
        familyId = familyId,
        device = dev,
        uploaderRole = "child",
        fileName = prepared.fileName,
        mimeType = prepared.mimeType,
        sizeBytes = prepared.sizeBytes,
        category = prepared.category,
        thumbnailBase64 = prepared.thumbnailBase64,
        textPreview = prepared.textPreview,
        localUriString = prepared.localUriString,
      ).onSuccess {
        _bannerMessage.value = "Shared ${prepared.fileName} with parent"
      }.onFailure {
        _errorMessage.value = it.localizedMessage ?: "Failed to share file"
      }
    }
  }

  private companion object {
    const val TAG = "ChildViewModel"
  }
}
