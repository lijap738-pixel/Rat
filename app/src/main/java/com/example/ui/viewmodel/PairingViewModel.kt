package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChildDevice
import com.example.data.model.PairedLink
import com.example.data.repository.SafeNestRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PairingViewModel(
  private val repository: SafeNestRepository,
  currentUserId: String,
) : ViewModel() {

  val savedLinks: StateFlow<List<PairedLink>> = repository.observePairedLinks(currentUserId)
    .catch { error ->
      val firestoreError = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
      if (firestoreError == null && error is CancellationException) throw error
      Log.w("PairingVM", "Error observing paired links", firestoreError ?: error)
      emit(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

  private val _isPairing = MutableStateFlow(false)
  val isPairing: StateFlow<Boolean> = _isPairing.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  fun clearError() {
    _error.value = null
  }

  fun claimCode(
    code: String,
    deviceName: String,
    deviceModel: String,
    osVersion: String,
    batteryLevel: Int,
    isCharging: Boolean,
    onSuccess: (ChildDevice) -> Unit,
  ) {
    viewModelScope.launch {
      _isPairing.value = true
      _error.value = null
      repository.claimPairingCode(
        rawCode = code,
        deviceName = deviceName,
        deviceModel = deviceModel,
        osVersion = osVersion,
        batteryLevel = batteryLevel,
        isCharging = isCharging,
      ).onSuccess { device ->
        _isPairing.value = false
        onSuccess(device)
      }.onFailure { err ->
        _isPairing.value = false
        _error.value = err.localizedMessage ?: "Failed to pair device"
      }
    }
  }
}
