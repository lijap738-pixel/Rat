package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.FileCategory
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeNestRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun parentAndChildPairingFlow_succeedsAndStreamsInRealtime() = runBlocking {
    val parentUid = signInTestUser("parent_${System.nanoTime()}@test.com")
    val parentRepo = SafeNestRepository(firestore, auth)

    val family = withTimeout(TIMEOUT_MS) {
      parentRepo.ensureFamilyExists("Alice Guardian", "alice@test.com").getOrThrow()
    }
    assertEquals(parentUid, family.parentUid)

    val pairingCode = withTimeout(TIMEOUT_MS) {
      parentRepo.generatePairingCode("Leo Pixel").getOrThrow()
    }
    assertEquals("pending", pairingCode.status)

    // Child signs in with separate Google account and claims pairing code
    val childUid = signInTestUser("child_${System.nanoTime()}@test.com")
    val childRepo = SafeNestRepository(firestore, auth)

    val pairedDevice = withTimeout(TIMEOUT_MS) {
      childRepo.claimPairingCode(
        rawCode = pairingCode.code,
        deviceName = "Leo Pixel 8",
        deviceModel = "Pixel 8",
        osVersion = "Android 15",
        batteryLevel = 88,
        isCharging = false,
      ).getOrThrow()
    }
    assertEquals(childUid, pairedDevice.childUid)
    assertEquals(parentUid, pairedDevice.familyId)

    // Parent observes paired device in real time
    signInTestUser("parent_${System.nanoTime()}@test.com") // Switch to a stranger first to check isolation
    var exceptionThrown: FirebaseFirestoreException? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        SafeNestRepository(firestore, auth).observeDevices(parentUid).first()
      }
    } catch (e: Exception) {
      exceptionThrown = e.unwrapFirestoreException()
    }
    assertNotNull(exceptionThrown)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exceptionThrown?.code)
  }

  @Test
  fun consentSessionAndAuditLog_enforcesChildApprovalAndImmutability() = runBlocking {
    val parentEmail = "parent_sess_${System.nanoTime()}@test.com"
    val childEmail = "child_sess_${System.nanoTime()}@test.com"

    val parentUid = signInTestUser(parentEmail)
    val parentRepo = SafeNestRepository(firestore, auth)
    withTimeout(TIMEOUT_MS) { parentRepo.ensureFamilyExists("Parent", parentEmail).getOrThrow() }
    val code = withTimeout(TIMEOUT_MS) { parentRepo.generatePairingCode("Maya Phone").getOrThrow() }

    val childUid = signInTestUser(childEmail)
    val childRepo = SafeNestRepository(firestore, auth)
    val device = withTimeout(TIMEOUT_MS) {
      childRepo.claimPairingCode(code.code, "Maya Phone", "Pixel 9", "Android 15", 92, true).getOrThrow()
    }

    // Parent requests live camera session
    signInTestUser(parentEmail)
    val session = withTimeout(TIMEOUT_MS) {
      parentRepo.requestMonitoringSession(parentUid, device, SessionType.CAMERA, 10L, "v=0\r\n").getOrThrow()
    }
    assertEquals(SessionStatus.REQUESTED.value, session.status)

    // Child approves session and shares a SAF file
    signInTestUser(childEmail)
    withTimeout(TIMEOUT_MS) {
      childRepo.respondToSession(parentUid, session, approve = true, sdpAnswer = "v=0\r\n").getOrThrow()
      childRepo.shareAuthorizedFile(
        familyId = parentUid,
        device = device,
        uploaderRole = "child",
        fileName = "homework_scan.pdf",
        mimeType = "application/pdf",
        sizeBytes = 24500L,
        category = FileCategory.DOCUMENT,
        textPreview = "Algebra Worksheet",
      ).getOrThrow()
    }

    val logs = withTimeout(FLOW_TIMEOUT_MS) {
      childRepo.observeAuditLogs(parentUid, childUid).first { it.isNotEmpty() }
    }
    assertTrue(logs.any { it.eventType == "camera_approved" })
    assertTrue(logs.any { it.eventType == "file_shared" })
  }

  @Test
  fun unauthenticatedCaller_rejectedWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = SafeNestRepository(firestore, auth)
    var caught: Exception? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeDevices("some_family").first() }
    } catch (e: Exception) {
      caught = e
    }
    assertTrue(caught is IllegalStateException || caught?.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
  }

  private companion object {
    const val TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}
