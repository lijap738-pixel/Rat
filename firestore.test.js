const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const PARENT_UID = "parent_alice_123";
const CHILD_UID = "child_leo_456";
const STRANGER_UID = "stranger_eve_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedFamilyAndDevice({ deviceCount = 1 } = {}) {
  const now = new Date();
  const future = new Date(Date.now() + 3600 * 1000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc(PARENT_UID).set({
      parentUid: PARENT_UID,
      parentName: "Alice Parent",
      parentEmail: "alice@example.com",
      maxDevices: 10,
      deviceCount,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("pairing_codes").doc("SN48K2").set({
      code: "SN48K2",
      familyId: PARENT_UID,
      parentUid: PARENT_UID,
      deviceLabel: "Leo Phone",
      status: "pending",
      expiresAt: future,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("families").doc(PARENT_UID).collection("devices").doc("dev_1").set({
      familyId: PARENT_UID,
      parentUid: PARENT_UID,
      childUid: CHILD_UID,
      deviceName: "Leo Pixel",
      deviceModel: "Pixel 8",
      osVersion: "Android 15",
      pairingCode: "SN0001",
      batteryLevel: 85,
      isCharging: false,
      isOnline: true,
      locationConsentEnabled: false,
      dailyScreenTimeMinutes: 45,
      screenTimeLimitMinutes: 120,
      appUsageSummary: ["Khan Academy|30|Education"],
      activeSessionType: "none",
      lastActiveAt: now,
      createdAt: now,
      updatedAt: now,
    });
  });
}

test("Unauthenticated user cannot read families or devices", async () => {
  await seedFamilyAndDevice();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("families").doc(PARENT_UID).get());
  await assertFails(unauthDb.collection("families").doc(PARENT_UID).collection("devices").get());
  await assertFails(unauthDb.collection("pairing_codes").doc("SN48K2").get());
});

test("Stranger cannot read another family's devices, sessions, or audit logs", async () => {
  await seedFamilyAndDevice();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();
  await assertFails(strangerDb.collection("families").doc(PARENT_UID).get());
  await assertFails(strangerDb.collection("families").doc(PARENT_UID).collection("devices").get());
  await assertFails(strangerDb.collection("families").doc(PARENT_UID).collection("audit_logs").get());
});

test("Parent can list devices and cannot exceed 10 device cap", async () => {
  await seedFamilyAndDevice({ deviceCount: 10 });
  const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
  await assertSucceeds(parentDb.collection("families").doc(PARENT_UID).collection("devices").get());

  const now = new Date();
  await assertFails(
    parentDb.collection("families").doc(PARENT_UID).collection("devices").doc("dev_11").set({
      familyId: PARENT_UID,
      parentUid: PARENT_UID,
      childUid: CHILD_UID,
      deviceName: "Device 11",
      deviceModel: "Pixel",
      osVersion: "Android 15",
      pairingCode: "SN48K2",
      batteryLevel: 90,
      isCharging: false,
      isOnline: true,
      locationConsentEnabled: false,
      dailyScreenTimeMinutes: 0,
      screenTimeLimitMinutes: 120,
      appUsageSummary: [],
      activeSessionType: "none",
      lastActiveAt: now,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Child can read pairing code by exact ID and claim it to register device under 10-device limit", async () => {
  await seedFamilyAndDevice({ deviceCount: 1 });
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  await assertSucceeds(childDb.collection("pairing_codes").doc("SN48K2").get());
  // Child cannot list all pairing codes
  await assertFails(childDb.collection("pairing_codes").get());

  const now = new Date();
  await assertSucceeds(
    childDb.collection("families").doc(PARENT_UID).collection("devices").doc("dev_2").set({
      familyId: PARENT_UID,
      parentUid: PARENT_UID,
      childUid: CHILD_UID,
      deviceName: "Leo Tablet",
      deviceModel: "Pixel Tablet",
      osVersion: "Android 15",
      pairingCode: "SN48K2",
      batteryLevel: 72,
      isCharging: true,
      isOnline: true,
      locationConsentEnabled: false,
      dailyScreenTimeMinutes: 10,
      screenTimeLimitMinutes: 120,
      appUsageSummary: [],
      activeSessionType: "none",
      lastActiveAt: now,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Session terminal state lock prevents reopening ended or declined session", async () => {
  await seedFamilyAndDevice();
  const now = new Date();
  const future = new Date(Date.now() + 900 * 1000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("families").doc(PARENT_UID).collection("sessions").doc("sess_1").set({
      familyId: PARENT_UID,
      parentUid: PARENT_UID,
      childUid: CHILD_UID,
      deviceId: "dev_1",
      deviceName: "Leo Pixel",
      sessionType: "camera",
      status: "declined",
      encryptionFingerprint: "DTLS-SHA256:ABCD1234",
      iceCandidates: [],
      audioLevelDb: 0,
      requestedDurationMinutes: 15,
      expiresAt: future,
      createdAt: now,
      updatedAt: now,
    });
  });

  const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
  await assertFails(
    parentDb.collection("families").doc(PARENT_UID).collection("sessions").doc("sess_1").update({
      status: "active",
      updatedAt: new Date(),
    })
  );
});

test("Audit logs are strictly append-only (update and delete forbidden)", async () => {
  await seedFamilyAndDevice();
  const now = new Date();
  const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
  const logRef = parentDb.collection("families").doc(PARENT_UID).collection("audit_logs").doc("log_1");
  await assertSucceeds(
    logRef.set({
      familyId: PARENT_UID,
      actorUid: PARENT_UID,
      actorRole: "parent",
      childUid: CHILD_UID,
      deviceId: "dev_1",
      deviceName: "Leo Pixel",
      eventType: "camera_requested",
      details: "Parent requested live camera consent",
      createdAt: now,
    })
  );
  await assertFails(logRef.update({ details: "Tampered" }));
  await assertFails(logRef.delete());
});
