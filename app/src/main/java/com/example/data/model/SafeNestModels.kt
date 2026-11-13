package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class DeviceRole(val value: String) {
  UNSELECTED("unselected"),
  PARENT("parent"),
  CHILD("child"),
}

enum class SessionType(val value: String, val label: String) {
  NONE("none", "Idle"),
  CAMERA("camera", "Live Camera"),
  MICROPHONE("microphone", "Live Microphone"),
  SCREEN("screen", "Screen Mirroring");

  companion object {
    fun fromValue(value: String): SessionType = entries.firstOrNull { it.value == value } ?: NONE
  }
}

enum class SessionStatus(val value: String) {
  REQUESTED("requested"),
  ACTIVE("active"),
  DECLINED("declined"),
  ENDED("ended"),
  EXPIRED("expired");

  companion object {
    fun fromValue(value: String): SessionStatus = entries.firstOrNull { it.value == value } ?: ENDED
  }
}

enum class FileCategory(val value: String, val label: String) {
  PHOTO("photo", "Photos"),
  VIDEO("video", "Videos"),
  DOCUMENT("document", "Documents");

  companion object {
    fun fromValue(value: String): FileCategory = entries.firstOrNull { it.value == value } ?: DOCUMENT
  }
}

@IgnoreExtraProperties
data class FamilyAccount(
  @DocumentId val id: String = "",
  val parentUid: String = "",
  val parentName: String = "",
  val parentEmail: String = "",
  val maxDevices: Long = 10L,
  val deviceCount: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class PairingCode(
  @DocumentId val id: String = "",
  val code: String = "",
  val familyId: String = "",
  val parentUid: String = "",
  val deviceLabel: String = "",
  val status: String = "pending",
  val claimedByUid: String? = null,
  val claimedDeviceId: String? = null,
  val expiresAt: Timestamp? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class AppUsageStat(
  val appName: String,
  val minutesUsed: Long,
  val category: String,
) {
  fun serialize(): String = "${appName.replace("|", " ")}|$minutesUsed|${category.replace("|", " ")}"

  companion object {
    fun parse(encoded: String): AppUsageStat? {
      val parts = encoded.split("|")
      if (parts.size < 3) return null
      return AppUsageStat(
        appName = parts[0],
        minutesUsed = parts[1].toLongOrNull() ?: 0L,
        category = parts[2],
      )
    }
  }
}

@IgnoreExtraProperties
data class ChildDevice(
  @DocumentId val id: String = "",
  val familyId: String = "",
  val parentUid: String = "",
  val childUid: String = "",
  val deviceName: String = "",
  val deviceModel: String = "",
  val osVersion: String = "",
  val pairingCode: String = "",
  val batteryLevel: Long = 100L,
  val isCharging: Boolean = false,
  val isOnline: Boolean = true,
  val locationConsentEnabled: Boolean = false,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val locationLabel: String? = null,
  val dailyScreenTimeMinutes: Long = 0L,
  val screenTimeLimitMinutes: Long = 120L,
  val appUsageSummary: List<String> = emptyList(),
  val activeSessionType: String = SessionType.NONE.value,
  val lastActiveAt: Timestamp? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  fun parsedUsageStats(): List<AppUsageStat> = appUsageSummary.mapNotNull { AppUsageStat.parse(it) }
}

@IgnoreExtraProperties
data class PairedLink(
  @DocumentId val id: String = "",
  val userId: String = "",
  val familyId: String = "",
  val deviceId: String = "",
  val deviceName: String = "",
  val parentName: String = "",
  val role: String = "child",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class MonitoringSession(
  @DocumentId val id: String = "",
  val familyId: String = "",
  val parentUid: String = "",
  val childUid: String = "",
  val deviceId: String = "",
  val deviceName: String = "",
  val sessionType: String = SessionType.CAMERA.value,
  val status: String = SessionStatus.REQUESTED.value,
  val encryptionFingerprint: String = "",
  val sdpOffer: String? = null,
  val sdpAnswer: String? = null,
  val iceCandidates: List<String> = emptyList(),
  val previewFrameBase64: String? = null,
  val audioLevelDb: Long = 0L,
  val requestedDurationMinutes: Long = 15L,
  val expiresAt: Timestamp? = null,
  val endedReason: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  fun isTerminal(): Boolean =
    status in
      listOf(
        SessionStatus.DECLINED.value,
        SessionStatus.ENDED.value,
        SessionStatus.EXPIRED.value,
      )
}

@IgnoreExtraProperties
data class SharedFile(
  @DocumentId val id: String = "",
  val familyId: String = "",
  val parentUid: String = "",
  val childUid: String = "",
  val deviceId: String = "",
  val uploadedByUid: String = "",
  val uploaderRole: String = "child",
  val fileName: String = "",
  val mimeType: String = "",
  val sizeBytes: Long = 0L,
  val category: String = FileCategory.DOCUMENT.value,
  val consentGranted: Boolean = true,
  val thumbnailBase64: String? = null,
  val textPreview: String? = null,
  val localUriString: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class AuditLog(
  @DocumentId val id: String = "",
  val familyId: String = "",
  val actorUid: String = "",
  val actorRole: String = "parent",
  val childUid: String = "",
  val deviceId: String = "",
  val deviceName: String = "",
  val eventType: String = "",
  val details: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)
