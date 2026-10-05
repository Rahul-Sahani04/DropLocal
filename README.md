# DropLocal — offline P2P file + text transfer (Android)

Android-only MVP: Nearby Connections, explicit authenticated pairing, consent-first
queued file offers, receiver QR discovery, file/text payloads, live progress,
URI-based Downloads saving, Open/Share, and last-10 local history. No application backend.

Install the updated build on **both phones**. The previous MVP's payload protocol
is not compatible with the new consent-first protocol. Debug and release builds use
different service identities, so use the same build variant on both phones.

## Build
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an Android device/emulator connected:
JAVA_HOME=/path/to/jdk17 ./gradlew :app:connectedDebugAndroidTest
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

Use a disposable emulator/test device for instrumentation: Gradle's test runner
reinstalls and cleans up the debug app, including its private draft/history state.

## Demo (2 phones)
1. Phone B: Receive → grant nearby access. The phone becomes visible and shows its QR.
2. Phone A: Send files → select up to 32 → choose Phone B, or scan Phone B's QR.
3. Both: compare the **Nearby verification code**, then tap Codes match on both phones.
4. Phone B: review each file offer's name, size and batch count → Accept file.
5. Both: watch progress. Sender completion waits for receiver persistence confirmation.
6. Phone B: Open/Share the result. Files are saved under Downloads/DropLocal.
7. Text: Send text → type/paste → Choose device or Send on the existing connection.

QR only selects an active receiver session; it never bypasses Nearby authentication.
Camera permission is requested only for scanning. Nearby access is requested only when
connecting/receiving; Android 8–9 also needs storage permission for Downloads writes.
If access is denied, use the in-app readiness dialog to retry or open app settings.

The app keeps the screen awake during visible receiving/active work. Discovery/advertising
stop when the Activity leaves the foreground (but not on rotation). There is no background
service or guarantee of transfer continuation after the app is killed. Keep both apps open.

Cancel all also disconnects the peer, so its queued work cannot start another offer.
Failed sender files may be retried on the same connection for up to five minutes;
retention is limited to three files/512 MiB. Reconnect and reselect for other failures.
Completed/received files are never deleted by Clear history or Cancel all.

> No account. No cloud upload. Just device-to-device.

Audit: `reports/droplocal-audit.md`. Implementation/verification: `reports/droplocal-fixes.md`.
