# DropLocal — offline P2P file + text transfer (Android)

MVP per `PRD_DropLocal.md`: Nearby Connections, authenticated pairing + QR shortcut,
file/text payloads, live progress, Downloads save, last-10 history. No backend.

## Build
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Demo (2 phones)
1. Phone B: RECEIVE → MAKE THIS DEVICE VISIBLE
2. Phone A: SEND FILE → pick → tap Phone B → compare code → CONNECT
3. Phone B: Accept → watch progress → open from Downloads

> No account. No cloud upload. Just device-to-device.
