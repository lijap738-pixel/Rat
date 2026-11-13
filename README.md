# SafeNest — Consent-Based Android Parental Control Application

SafeNest is a Kotlin + Jetpack Compose Android application implementing transparent, permission-based parental monitoring and family device management with **Parent Guardian Console** and **Child Protected Device** modes.

## Key Features & Privacy Architecture

1. **Parent Guardian Console (Up to 10 Child Devices)**
   - Google Sign-In authentication via Jetpack Credential Manager.
   - Real-time dashboard showing device name, model, battery percentage/charging status, online/offline indicator, last active time, and daily screen time.
   - One-time 6-character pairing codes (15-minute expiration) supporting up to 10 connected child devices per family.

2. **Consent-Gated Live Sessions (WebRTC + Foreground Services)**
   - **Live Camera**: Parent requests a session; child receives an explicit Approve/Decline dialog and persistent foreground notification (`FOREGROUND_SERVICE_CAMERA`). Both sides show a visible **LIVE CAMERA ACTIVE** banner with DTLS-SHA256 fingerprint and countdown timer.
   - **Live Microphone**: Real-time consent-based audio telemetry (`FOREGROUND_SERVICE_MICROPHONE`) with live dB visualizer, persistent notification, and zero secret recording.
   - **Screen Mirroring**: Uses Android `MediaProjection` API (`FOREGROUND_SERVICE_MEDIA_PROJECTION`) requiring explicit Android system screen-capture consent on every session, with instant Stop Sharing controls in both app UI and notification.

3. **Permission-Based File Manager (SAF & Photo Picker)**
   - Uses zero-permission Android Photo Picker (`PickVisualMedia`) and Storage Access Framework (`OpenDocument` / `CreateDocument`) to browse, share, and export approved photos, videos, and documents without broad storage permissions.

4. **Device Activity, Telemetry & Consent Location**
   - Battery telemetry via `BatteryManager`.
   - Daily screen time and top app usage via `UsageStatsManager` (with Usage Access settings helper).
   - Optional consent-controlled location sharing via `FusedLocationProviderClient`.

5. **Security & Audit Logs**
   - Hardened zero-trust `firestore.rules` with role-based access control (`parentUid` / `childUid`), 10-device cap enforcement, session terminal state locking, and immutable append-only `/audit_logs`.

## Firestore Database Structure

- `/families/{familyId}` — Parent family root document (`maxDevices = 10`, `deviceCount`).
- `/pairing_codes/{code}` — Expiring 6-char pairing codes (`get` by exact code, `list` parent-only).
- `/families/{familyId}/devices/{deviceId}` — Paired child device profiles, battery, screen time, usage summary, consent location.
- `/families/{familyId}/sessions/{sessionId}` — Consent-gated WebRTC signaling (`sdpOffer`, `sdpAnswer`, `iceCandidates`, `expiresAt`, `encryptionFingerprint`).
- `/families/{familyId}/shared_files/{fileId}` — SAF/Photo Picker shared media and documents.
- `/families/{familyId}/audit_logs/{logId}` — Append-only immutable audit trail (`allow update, delete: if false`).
- `/users/{userId}/paired_links/{linkId}` — Child account's saved family/device bindings.

## Build & Installation Instructions

1. Open the project in **Android Studio** (AGP 9.x / Kotlin 2.2+ / JDK 17+).
2. Ensure `app/google-services.json` and `app/src/main/res/values/firebase_applet_config.xml` are present for your Firebase project.
3. (Optional for Push Notifications) Enable Firebase Cloud Messaging in the Firebase Console if you wish to add background FCM triggers alongside SafeNest's real-time Firestore session listeners.
4. Build and install on Parent and Child devices:
   ```bash
   gradle :app:assembleDebug
   ```
5. Sign in with Google on the Parent device, select **Parent Guardian Console**, generate a pairing code, then sign in on up to 10 Child devices, select **Child Protected Device**, and enter the pairing code.
