package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.data.model.AuditLog
import com.example.data.model.ChildDevice
import com.example.data.model.FamilyAccount
import com.example.data.model.FileCategory
import com.example.data.model.MonitoringSession
import com.example.data.model.PairedLink
import com.example.data.model.PairingCode
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.data.model.SharedFile
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class SafeNestRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    ),
    Firebase.auth,
  )

  private val random = SecureRandom()

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing SafeNest.")
  }

  private fun unwrapAndLog(error: Throwable, operationType: OperationType, path: String, expectedUid: String) {
    val firestoreError = generateSequence(error) { it.cause }
      .filterIsInstance<FirebaseFirestoreException>()
      .firstOrNull()
    if (firestoreError == null && error is CancellationException) return
    val ex = firestoreError ?: (error as? Exception)
    if (ex != null && auth.currentUser?.uid == expectedUid) {
      handleFirestoreError(ex, operationType, path)
    }
  }

  // --- FAMILY MANAGEMENT ---

  suspend fun ensureFamilyExists(parentName: String, parentEmail: String?): Result<FamilyAccount> {
    val uid = requireUserId()
    val docRef = db.collection("families").document(uid)
    return try {
      val snapshot = docRef.get().await()
      if (snapshot.exists()) {
        val existing = snapshot.toObject(FamilyAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        if (existing != null) return Result.success(existing)
      }
      val safeName = parentName.ifBlank { "Parent Guardian" }.take(80)
      val payload = mutableMapOf<String, Any>(
        "parentUid" to uid,
        "parentName" to safeName,
        "maxDevices" to MAX_CHILD_DEVICES,
        "deviceCount" to 0L,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      if (!parentEmail.isNullOrBlank()) {
        payload["parentEmail"] = parentEmail.take(120)
      }
      docRef.set(payload).await()
      val created = docRef.get().await().toObject(FamilyAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: FamilyAccount(id = uid, parentUid = uid, parentName = safeName, parentEmail = parentEmail.orEmpty())
      Result.success(created)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docRef.path)
      Result.failure(e)
    }
  }

  fun observeFamily(familyId: String): Flow<FamilyAccount?> = flow {
    val uid = requireUserId()
    val ref = db.collection("families").document(familyId)
    emitAll(
      ref.snapshots()
        .map { it.toObject(FamilyAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
        .catch { err ->
          unwrapAndLog(err, OperationType.GET, ref.path, uid)
          throw err
        }
    )
  }

  // --- PAIRING CODES & DEVICE MANAGEMENT (UP TO 10 DEVICES) ---

  fun observePairingCodes(parentUid: String): Flow<List<PairingCode>> = flow {
    val uid = requireUserId()
    val query = db.collection("pairing_codes").whereEqualTo("parentUid", parentUid)
    emitAll(
      query.snapshots()
        .map { snap ->
          snap.toObjects(PairingCode::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, "pairing_codes", uid)
          throw err
        }
    )
  }

  suspend fun generatePairingCode(deviceLabel: String): Result<PairingCode> {
    val uid = requireUserId()
    val familyRef = db.collection("families").document(uid)
    return try {
      val familySnap = familyRef.get().await()
      val currentCount = familySnap.getLong("deviceCount") ?: 0L
      if (currentCount >= MAX_CHILD_DEVICES) {
        return Result.failure(IllegalStateException("Maximum limit of $MAX_CHILD_DEVICES child devices reached."))
      }

      val code = generateUniqueCode()
      val expiresAt = Timestamp(Date(System.currentTimeMillis() + PAIRING_TTL_MS))
      val safeLabel = deviceLabel.trim().ifBlank { "Child Device" }.take(60)
      val payload = mapOf(
        "code" to code,
        "familyId" to uid,
        "parentUid" to uid,
        "deviceLabel" to safeLabel,
        "status" to "pending",
        "expiresAt" to expiresAt,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      val codeRef = db.collection("pairing_codes").document(code)
      codeRef.set(payload).await()

      appendAuditLog(
        familyId = uid,
        actorRole = "parent",
        childUid = uid,
        deviceId = "pending_$code",
        deviceName = safeLabel,
        eventType = "pairing_created",
        details = "Generated pairing code $code for $safeLabel",
      )

      val created = codeRef.get().await().toObject(PairingCode::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: PairingCode(id = code, code = code, familyId = uid, parentUid = uid, deviceLabel = safeLabel, expiresAt = expiresAt)
      Result.success(created)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, "pairing_codes")
      Result.failure(e)
    }
  }

  suspend fun revokePairingCode(code: String): Result<Unit> {
    requireUserId()
    val codeRef = db.collection("pairing_codes").document(code)
    return try {
      codeRef.update(
        mapOf(
          "status" to "revoked",
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, codeRef.path)
      Result.failure(e)
    }
  }

  suspend fun claimPairingCode(
    rawCode: String,
    deviceName: String,
    deviceModel: String,
    osVersion: String,
    batteryLevel: Int,
    isCharging: Boolean,
  ): Result<ChildDevice> {
    val childUid = requireUserId()
    val code = rawCode.trim().uppercase()
    if (!code.matches(Regex("^[A-Z0-9]{6,12}$"))) {
      return Result.failure(IllegalArgumentException("Pairing code must be 6-12 alphanumeric characters."))
    }

    val codeRef = db.collection("pairing_codes").document(code)
    return try {
      val codeSnap = codeRef.get().await()
      if (!codeSnap.exists()) {
        return Result.failure(IllegalArgumentException("Pairing code $code not found."))
      }
      val pairing = codeSnap.toObject(PairingCode::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: return Result.failure(IllegalStateException("Invalid pairing code payload."))

      if (pairing.status != "pending") {
        return Result.failure(IllegalStateException("This pairing code has already been ${pairing.status}."))
      }
      val exp = pairing.expiresAt
      if (exp != null && exp.toDate().time < System.currentTimeMillis()) {
        return Result.failure(IllegalStateException("This pairing code has expired. Ask your parent for a new code."))
      }

      val familyId = pairing.familyId
      val deviceId = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
      val safeName = deviceName.trim().ifBlank { pairing.deviceLabel }.take(60)
      val safeModel = deviceModel.trim().ifBlank { "Android Device" }.take(80)
      val safeOs = osVersion.trim().ifBlank { "Android" }.take(30)

      val deviceRef = db.collection("families").document(familyId).collection("devices").document(deviceId)
      val devicePayload = mapOf(
        "familyId" to familyId,
        "parentUid" to pairing.parentUid,
        "childUid" to childUid,
        "deviceName" to safeName,
        "deviceModel" to safeModel,
        "osVersion" to safeOs,
        "pairingCode" to code,
        "batteryLevel" to batteryLevel.coerceIn(0, 100).toLong(),
        "isCharging" to isCharging,
        "isOnline" to true,
        "locationConsentEnabled" to false,
        "dailyScreenTimeMinutes" to 0L,
        "screenTimeLimitMinutes" to 120L,
        "appUsageSummary" to emptyList<String>(),
        "activeSessionType" to SessionType.NONE.value,
        "lastActiveAt" to FieldValue.serverTimestamp(),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )

      deviceRef.set(devicePayload).await()

      codeRef.update(
        mapOf(
          "status" to "claimed",
          "claimedByUid" to childUid,
          "claimedDeviceId" to deviceId,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()

      db.collection("families").document(familyId).update(
        mapOf(
          "deviceCount" to FieldValue.increment(1L),
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()

      val linkPayload = mapOf(
        "userId" to childUid,
        "familyId" to familyId,
        "deviceId" to deviceId,
        "deviceName" to safeName,
        "parentName" to "Parent Guardian",
        "role" to "child",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      db.collection("users").document(childUid).collection("paired_links").document(deviceId)
        .set(linkPayload).await()

      appendAuditLog(
        familyId = familyId,
        actorRole = "child",
        childUid = childUid,
        deviceId = deviceId,
        deviceName = safeName,
        eventType = "device_paired",
        details = "Paired $safeName ($safeModel) with code $code",
      )

      val created = deviceRef.get().await().toObject(ChildDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: ChildDevice(
          id = deviceId,
          familyId = familyId,
          parentUid = pairing.parentUid,
          childUid = childUid,
          deviceName = safeName,
          deviceModel = safeModel,
          osVersion = safeOs,
          pairingCode = code,
          batteryLevel = batteryLevel.toLong(),
          isCharging = isCharging,
        )
      Result.success(created)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, codeRef.path)
      Result.failure(e)
    }
  }

  fun observeDevices(familyId: String, childUidFilter: String? = null): Flow<List<ChildDevice>> = flow {
    val uid = requireUserId()
    val base = db.collection("families").document(familyId).collection("devices")
    val query = if (childUidFilter != null && childUidFilter != familyId) {
      base.whereEqualTo("childUid", childUidFilter)
    } else {
      base
    }
    emitAll(
      query.snapshots()
        .map { snap ->
          snap.toObjects(ChildDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.lastActiveAt ?: it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, base.path, uid)
          throw err
        }
    )
  }

  fun observeSingleDevice(familyId: String, deviceId: String): Flow<ChildDevice?> = flow {
    val uid = requireUserId()
    val ref = db.collection("families").document(familyId).collection("devices").document(deviceId)
    emitAll(
      ref.snapshots()
        .map { it.toObject(ChildDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
        .catch { err ->
          unwrapAndLog(err, OperationType.GET, ref.path, uid)
          throw err
        }
    )
  }

  fun observePairedLinks(userId: String): Flow<List<PairedLink>> = flow {
    val uid = requireUserId()
    val ref = db.collection("users").document(userId).collection("paired_links")
    emitAll(
      ref.snapshots()
        .map { snap ->
          snap.toObjects(PairedLink::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, ref.path, uid)
          throw err
        }
    )
  }

  suspend fun updateDeviceSettings(
    familyId: String,
    deviceId: String,
    deviceName: String,
    screenTimeLimitMinutes: Long,
  ): Result<Unit> {
    requireUserId()
    val ref = db.collection("families").document(familyId).collection("devices").document(deviceId)
    return try {
      ref.update(
        mapOf(
          "deviceName" to deviceName.trim().ifBlank { "Child Device" }.take(60),
          "screenTimeLimitMinutes" to screenTimeLimitMinutes.coerceIn(15L, 1440L),
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, ref.path)
      Result.failure(e)
    }
  }

  suspend fun removeChildDevice(familyId: String, device: ChildDevice): Result<Unit> {
    requireUserId()
    val deviceRef = db.collection("families").document(familyId).collection("devices").document(device.id)
    val familyRef = db.collection("families").document(familyId)
    return try {
      deviceRef.delete().await()
      val familySnap = familyRef.get().await()
      val currentCount = familySnap.getLong("deviceCount") ?: 0L
      if (currentCount > 0L) {
        familyRef.update(
          mapOf(
            "deviceCount" to (currentCount - 1L),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        ).await()
      }
      appendAuditLog(
        familyId = familyId,
        actorRole = "parent",
        childUid = device.childUid,
        deviceId = device.id,
        deviceName = device.deviceName,
        eventType = "device_removed",
        details = "Removed child device ${device.deviceName}",
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, deviceRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateChildTelemetry(
    familyId: String,
    deviceId: String,
    batteryLevel: Int,
    isCharging: Boolean,
    isOnline: Boolean,
    locationConsentEnabled: Boolean,
    latitude: Double?,
    longitude: Double?,
    locationLabel: String?,
    dailyScreenTimeMinutes: Long,
    appUsageSummary: List<String>,
  ): Result<Unit> {
    requireUserId()
    val ref = db.collection("families").document(familyId).collection("devices").document(deviceId)
    val updates = mutableMapOf<String, Any>(
      "batteryLevel" to batteryLevel.coerceIn(0, 100).toLong(),
      "isCharging" to isCharging,
      "isOnline" to isOnline,
      "locationConsentEnabled" to locationConsentEnabled,
      "dailyScreenTimeMinutes" to dailyScreenTimeMinutes.coerceIn(0L, 1440L),
      "appUsageSummary" to appUsageSummary.take(10),
      "lastActiveAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (locationConsentEnabled && latitude != null && longitude != null) {
      updates["latitude"] = latitude
      updates["longitude"] = longitude
      if (!locationLabel.isNullOrBlank()) {
        updates["locationLabel"] = locationLabel.take(120)
      }
    }
    return try {
      ref.update(updates).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, ref.path)
      Result.failure(e)
    }
  }

  // --- CONSENT-BASED LIVE MONITORING SESSIONS (CAMERA, MIC, SCREEN) ---

  fun observeSessions(familyId: String, childUidFilter: String? = null): Flow<List<MonitoringSession>> = flow {
    val uid = requireUserId()
    val base = db.collection("families").document(familyId).collection("sessions")
    val query = if (childUidFilter != null && childUidFilter != familyId) {
      base.whereEqualTo("childUid", childUidFilter)
    } else {
      base
    }
    emitAll(
      query.snapshots()
        .map { snap ->
          snap.toObjects(MonitoringSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, base.path, uid)
          throw err
        }
    )
  }

  suspend fun requestMonitoringSession(
    familyId: String,
    device: ChildDevice,
    sessionType: SessionType,
    durationMinutes: Long = 10L,
    sdpOffer: String? = null,
  ): Result<MonitoringSession> {
    val uid = requireUserId()
    val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val safeDuration = durationMinutes.coerceIn(1L, 30L)
    val expiresAt = Timestamp(Date(System.currentTimeMillis() + safeDuration * 60_000L))
    val fingerprint = "DTLS-SHA256:${UUID.randomUUID().toString().uppercase().take(12)}"

    val payload = mutableMapOf<String, Any>(
      "familyId" to familyId,
      "parentUid" to uid,
      "childUid" to device.childUid,
      "deviceId" to device.id,
      "deviceName" to device.deviceName,
      "sessionType" to sessionType.value,
      "status" to SessionStatus.REQUESTED.value,
      "encryptionFingerprint" to fingerprint,
      "iceCandidates" to emptyList<String>(),
      "audioLevelDb" to 0L,
      "requestedDurationMinutes" to safeDuration,
      "expiresAt" to expiresAt,
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (!sdpOffer.isNullOrBlank()) {
      payload["sdpOffer"] = sdpOffer.take(20000)
    }

    val ref = db.collection("families").document(familyId).collection("sessions").document(sessionId)
    return try {
      ref.set(payload).await()
      appendAuditLog(
        familyId = familyId,
        actorRole = "parent",
        childUid = device.childUid,
        deviceId = device.id,
        deviceName = device.deviceName,
        eventType = "${sessionType.value}_requested",
        details = "Parent requested ${sessionType.label} (${safeDuration}m) — awaiting child consent",
      )
      val created = ref.get().await().toObject(MonitoringSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: MonitoringSession(
          id = sessionId,
          familyId = familyId,
          parentUid = uid,
          childUid = device.childUid,
          deviceId = device.id,
          deviceName = device.deviceName,
          sessionType = sessionType.value,
          status = SessionStatus.REQUESTED.value,
          encryptionFingerprint = fingerprint,
          sdpOffer = sdpOffer,
          requestedDurationMinutes = safeDuration,
          expiresAt = expiresAt,
        )
      Result.success(created)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, ref.path)
      Result.failure(e)
    }
  }

  suspend fun respondToSession(
    familyId: String,
    session: MonitoringSession,
    approve: Boolean,
    sdpAnswer: String? = null,
  ): Result<Unit> {
    requireUserId()
    val sessionRef = db.collection("families").document(familyId).collection("sessions").document(session.id)
    val deviceRef = db.collection("families").document(familyId).collection("devices").document(session.deviceId)
    val newStatus = if (approve) SessionStatus.ACTIVE.value else SessionStatus.DECLINED.value
    val updates = mutableMapOf<String, Any>(
      "status" to newStatus,
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (!approve) {
      updates["endedReason"] = "Declined by child user"
    }
    if (!sdpAnswer.isNullOrBlank()) {
      updates["sdpAnswer"] = sdpAnswer.take(20000)
    }

    return try {
      sessionRef.update(updates).await()
      if (approve) {
        deviceRef.update(
          mapOf(
            "activeSessionType" to session.sessionType,
            "lastActiveAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        ).await()
      }
      val eventType = if (approve) "${session.sessionType}_approved" else "${session.sessionType}_declined"
      val detail = if (approve) {
        "Child explicitly approved ${SessionType.fromValue(session.sessionType).label}"
      } else {
        "Child declined ${SessionType.fromValue(session.sessionType).label} request"
      }
      appendAuditLog(
        familyId = familyId,
        actorRole = "child",
        childUid = session.childUid,
        deviceId = session.deviceId,
        deviceName = session.deviceName,
        eventType = eventType,
        details = detail,
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, sessionRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateSessionStreamTelemetry(
    familyId: String,
    sessionId: String,
    previewFrameBase64: String? = null,
    audioLevelDb: Int? = null,
    sdpOffer: String? = null,
    sdpAnswer: String? = null,
    iceCandidates: List<String>? = null,
  ): Result<Unit> {
    requireUserId()
    val ref = db.collection("families").document(familyId).collection("sessions").document(sessionId)
    val updates = mutableMapOf<String, Any>(
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (previewFrameBase64 != null) {
      updates["previewFrameBase64"] = previewFrameBase64.take(120000)
    }
    if (audioLevelDb != null) {
      updates["audioLevelDb"] = audioLevelDb.coerceIn(0, 100).toLong()
    }
    if (sdpOffer != null) {
      updates["sdpOffer"] = sdpOffer.take(20000)
    }
    if (sdpAnswer != null) {
      updates["sdpAnswer"] = sdpAnswer.take(20000)
    }
    if (iceCandidates != null) {
      updates["iceCandidates"] = iceCandidates.take(30)
    }
    return try {
      ref.update(updates).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, ref.path)
      Result.failure(e)
    }
  }

  suspend fun endMonitoringSession(
    familyId: String,
    session: MonitoringSession,
    actorRole: String,
    reason: String,
    expired: Boolean = false,
  ): Result<Unit> {
    requireUserId()
    val sessionRef = db.collection("families").document(familyId).collection("sessions").document(session.id)
    val deviceRef = db.collection("families").document(familyId).collection("devices").document(session.deviceId)
    val targetStatus = if (expired) SessionStatus.EXPIRED.value else SessionStatus.ENDED.value
    return try {
      if (!session.isTerminal()) {
        sessionRef.update(
          mapOf(
            "status" to targetStatus,
            "endedReason" to reason.take(80),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        ).await()
      }
      deviceRef.update(
        mapOf(
          "activeSessionType" to SessionType.NONE.value,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
      appendAuditLog(
        familyId = familyId,
        actorRole = actorRole,
        childUid = session.childUid,
        deviceId = session.deviceId,
        deviceName = session.deviceName,
        eventType = if (expired) "session_expired" else "${session.sessionType}_ended",
        details = reason.take(300),
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, sessionRef.path)
      Result.failure(e)
    }
  }

  // --- PERMISSION-BASED SAF FILE ACCESS & MANAGEMENT ---

  fun observeSharedFiles(familyId: String, childUidFilter: String? = null): Flow<List<SharedFile>> = flow {
    val uid = requireUserId()
    val base = db.collection("families").document(familyId).collection("shared_files")
    val query = if (childUidFilter != null && childUidFilter != familyId) {
      base.whereEqualTo("childUid", childUidFilter)
    } else {
      base
    }
    emitAll(
      query.snapshots()
        .map { snap ->
          snap.toObjects(SharedFile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, base.path, uid)
          throw err
        }
    )
  }

  suspend fun shareAuthorizedFile(
    familyId: String,
    device: ChildDevice,
    uploaderRole: String,
    fileName: String,
    mimeType: String,
    sizeBytes: Long,
    category: FileCategory,
    thumbnailBase64: String? = null,
    textPreview: String? = null,
    localUriString: String? = null,
  ): Result<SharedFile> {
    val uid = requireUserId()
    val fileId = "file_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val safeName = fileName.trim().ifBlank { "shared_item" }.take(120)
    val safeMime = mimeType.trim().ifBlank { "application/octet-stream" }.take(80)

    val payload = mutableMapOf<String, Any>(
      "familyId" to familyId,
      "parentUid" to familyId,
      "childUid" to device.childUid,
      "deviceId" to device.id,
      "uploadedByUid" to uid,
      "uploaderRole" to uploaderRole,
      "fileName" to safeName,
      "mimeType" to safeMime,
      "sizeBytes" to sizeBytes.coerceAtLeast(0L),
      "category" to category.value,
      "consentGranted" to true,
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (!thumbnailBase64.isNullOrBlank()) {
      payload["thumbnailBase64"] = thumbnailBase64.take(120000)
    }
    if (!textPreview.isNullOrBlank()) {
      payload["textPreview"] = textPreview.take(8000)
    }
    if (!localUriString.isNullOrBlank()) {
      payload["localUriString"] = localUriString.take(500)
    }

    val ref = db.collection("families").document(familyId).collection("shared_files").document(fileId)
    return try {
      ref.set(payload).await()
      appendAuditLog(
        familyId = familyId,
        actorRole = uploaderRole,
        childUid = device.childUid,
        deviceId = device.id,
        deviceName = device.deviceName,
        eventType = "file_shared",
        details = "Authorized ${category.label} shared via SAF/Photo Picker: $safeName",
      )
      val created = ref.get().await().toObject(SharedFile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        ?: SharedFile(
          id = fileId,
          familyId = familyId,
          parentUid = familyId,
          childUid = device.childUid,
          deviceId = device.id,
          uploadedByUid = uid,
          uploaderRole = uploaderRole,
          fileName = safeName,
          mimeType = safeMime,
          sizeBytes = sizeBytes,
          category = category.value,
          consentGranted = true,
          thumbnailBase64 = thumbnailBase64,
          textPreview = textPreview,
          localUriString = localUriString,
        )
      Result.success(created)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, ref.path)
      Result.failure(e)
    }
  }

  suspend fun deleteSharedFile(familyId: String, file: SharedFile): Result<Unit> {
    requireUserId()
    val ref = db.collection("families").document(familyId).collection("shared_files").document(file.id)
    return try {
      ref.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, ref.path)
      Result.failure(e)
    }
  }

  // --- IMMUTABLE AUDIT LOGS ---

  fun observeAuditLogs(familyId: String, childUidFilter: String? = null): Flow<List<AuditLog>> = flow {
    val uid = requireUserId()
    val base = db.collection("families").document(familyId).collection("audit_logs")
    val query = if (childUidFilter != null && childUidFilter != familyId) {
      base.whereEqualTo("childUid", childUidFilter)
    } else {
      base
    }
    emitAll(
      query.snapshots()
        .map { snap ->
          snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { err ->
          unwrapAndLog(err, OperationType.LIST, base.path, uid)
          throw err
        }
    )
  }

  suspend fun appendAuditLog(
    familyId: String,
    actorRole: String,
    childUid: String,
    deviceId: String,
    deviceName: String,
    eventType: String,
    details: String,
  ): Result<Unit> {
    val uid = requireUserId()
    val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val ref = db.collection("families").document(familyId).collection("audit_logs").document(logId)
    val payload = mapOf(
      "familyId" to familyId,
      "actorUid" to uid,
      "actorRole" to actorRole,
      "childUid" to childUid,
      "deviceId" to deviceId,
      "deviceName" to deviceName.take(60),
      "eventType" to eventType.take(50),
      "details" to details.take(300),
      "createdAt" to FieldValue.serverTimestamp(),
    )
    return try {
      ref.set(payload).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, ref.path)
      Result.failure(e)
    }
  }

  private fun generateUniqueCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return buildString(6) {
      repeat(6) {
        append(alphabet[random.nextInt(alphabet.length)])
      }
    }
  }

  companion object {
    const val MAX_CHILD_DEVICES = 10L
    const val PAIRING_TTL_MS = 15 * 60 * 1000L
  }
}
