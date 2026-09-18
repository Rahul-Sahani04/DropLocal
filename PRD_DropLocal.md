# DropLocal
## Product Requirements Document

**Version:** 1.0 MVP
**Platform:** Android → Android
**Primary goal:** Demonstrate a polished, offline, peer-to-peer file and text transfer experience between two Android phones.
**Target demo:** Two phones, one QR flow, one transfer, under 60 seconds.

---

## 1. Product Definition

DropLocal is an Android-only nearby sharing app that lets two devices discover, authenticate, connect, and exchange files or text without a cloud backend.

The experience should feel like a small, purpose-built AirDrop-style utility, but the implementation is based on Android's Nearby Connections APIs.

### Core value proposition

> “Drop files directly to someone nearby. No account. No cloud upload. No internet dependency.”

### MVP success criteria

- Two physical Android devices can discover one another.
- User explicitly selects the target device.
- Receiver sees an incoming-transfer confirmation before accepting.
- Files and text can be transferred.
- Transfer progress is visible on both devices.
- Received files land in a predictable user-selected or Downloads location.
- Sender sees speed, bytes transferred, and completion time.
- Authentication is built into the pairing flow.
- No app server is required.

---

# 2. Scope

## In scope

### Nearby discovery
- Advertising
- Scanning / discovery
- Nearby device list
- Human-readable device name
- Connection status

### Pairing
- Device selection
- Connection request
- Authentication digits / confirmation
- QR-assisted pairing
- Accept / reject

### Transfers
- Single file
- Multiple files as a queued batch
- Plain text
- Transfer progress
- Transfer speed
- ETA
- Completion time
- Cancel while sending when supported by connection state
- Retry failed transfer

### Receiver
- Incoming transfer modal
- File name
- File size
- File count
- Sender name
- Accept / reject
- Save location
- Open/share after completion

### History
- Last 10 local transfers
- Sent / received
- File name
- Size
- Time
- Status

## Explicitly out of MVP

- iOS support
- Windows/macOS client
- Internet relay
- Account system
- Cloud backup
- Public rooms
- Multi-hop mesh networking
- Background always-on discovery
- Folder sync
- Media transcoding
- Cross-device clipboard sync beyond explicit text sending

---

# 3. Product Architecture Decision

## Transport: Nearby Connections

Use Google's Nearby Connections Android API as the primary transport.

The API supports nearby discovery and connection establishment, lets apps choose a topology strategy, and supports both byte and file payloads. Authentication can be performed with a shared short verification code before the connection is accepted. citeturn623999search0turn601676search1

### Why this is suitable for the demo

- Native Android ecosystem
- No custom socket protocol required
- Nearby discovery is already handled
- File and byte payloads are built in
- Works without an app backend
- Provides connection lifecycle callbacks

The implementation still depends on Google Play Services and appropriate Android runtime permissions on supported devices. citeturn601676search0

---

# 4. Core User Journey

```text
Open DropLocal
      ↓
Choose SEND or RECEIVE
      ↓
Discover nearby devices
      ↓
Select target
      ↓
Authentication / QR confirmation
      ↓
Receiver accepts
      ↓
Transfer begins
      ↓
Live progress
      ↓
Transfer complete
      ↓
Open / Share / Send Again
```

For the fastest demo, default the home screen to one large action:

`SEND SOMETHING`

with a smaller:

`RECEIVE`

state underneath.

---

# 5. Screen Requirements

## Screen 01 — Home

### Visual

Large wordmark:

```text
DROP
LOCAL
```

Subtitle:

```text
Nearby. Private. Direct.
```

Center:

```text
[ SEND FILE ]

[ SEND TEXT ]
```

Bottom status:

```text
Not discoverable
```

A receiver can tap:

`MAKE THIS DEVICE VISIBLE`

### Optional quick action

`SCAN QR`

---

## Screen 02 — Nearby Devices

```text
SEND FILE

Nearby Devices

┌────────────────────────┐
│  ◉  Rahul's Phone      │
│     Android · Ready    │
│                  >     │
└────────────────────────┘

┌────────────────────────┐
│  ◉  Anika's Phone      │
│     Android · Ready    │
│                  >     │
└────────────────────────┘

Scanning...
```

### States

- Searching
- Device found
- Device lost
- Connecting
- Waiting for receiver
- Connected

Avoid saying “AirDrop.” The product should have its own identity.

---

## Screen 03 — Pairing

### Standard pairing

```text
CONNECTING TO

Rahul's Phone

COMPARE THIS CODE

  482 731

Does the code match on both devices?

[ CONNECT ]     [ CANCEL ]
```

The authentication flow should use the Nearby Connections authentication token / digits. Google explicitly recommends authentication because unauthenticated connections can expose devices to security vulnerabilities. citeturn601676search1

### QR pairing

Add a polished QR alternative:

```text
SCAN TO PAIR

[ QR CODE ]

Or enter 482 731

This code is only for this session.
```

Important implementation detail:

The QR is an **application-level pairing shortcut**, not a replacement for the Nearby Connections transport. The scanned session ID can be matched to the nearby endpoint before the normal authenticated connection is accepted.

---

## Screen 04 — Incoming Transfer

Receiver sees a full-screen confirmation card:

```text
INCOMING TRANSFER

Rahul wants to send

proposal.pdf
4.8 MB

PDF DOCUMENT

[ ACCEPT ]    [ REJECT ]
```

For multiple files:

```text
3 FILES
12.8 MB TOTAL
```

### Safety affordance

Always show:
- Sender device name
- File name(s)
- Total size
- Authentication status

Never auto-accept the first transfer in MVP.

---

## Screen 05 — Transfer

### Sender

```text
SENDING TO
Rahul's Phone

proposal.pdf

████████████████░░ 82%

3.9 / 4.8 MB

5.2 MB/s
~0.2 sec remaining
```

### Receiver

```text
RECEIVING FROM
Rahul's Phone

proposal.pdf

████████████████░░ 82%

Saving to Downloads
```

The progress bar should animate smoothly but be driven by actual payload progress callbacks.

---

## Screen 06 — Complete

```text
TRANSFER COMPLETE

✓ proposal.pdf

4.8 MB transferred
2.3 seconds

Local transfer
No cloud upload

[ OPEN FILE ]
[ SHARE ]
[ SEND ANOTHER ]
```

### Hero statistic

Make the transfer time the visual focus.

```text
2.3s
TRANSFER TIME
```

This makes the demo memorable.

---

# 6. Text Sharing

Text must be a first-class mini-feature because it makes the demo more obviously different from a plain file picker.

### Sender

```text
SEND TEXT

┌──────────────────────────────┐
│ Paste something to send...   │
│                              │
│                              │
└──────────────────────────────┘

286 characters

[ CHOOSE DEVICE ]
```

### Receiver

```text
TEXT RECEIVED

“Here is the staging build URL...”

[ COPY ] [ SHARE ]
```

Use a bytes payload for text rather than creating a temporary file.

---

# 7. QR Pairing Design

## Sender

```text
PAIR THIS DEVICE

        ██████████
        ██  ██  ██
        ██████████

        Scan with DropLocal

Session expires in 02:00
```

## Receiver

After scanning:

```text
DEVICE FOUND

Rahul's Phone

[ CONNECT ]
```

### QR payload

Use a signed/unguessable short-lived session object conceptually containing:

```json
{
  "v": 1,
  "app": "droplocal",
  "session": "random-session-id",
  "device": "Rahul's Phone",
  "expires": 1789737000
}
```

Do not put sensitive files or personal data in the QR payload.

---

# 8. Visual Design System

## Theme: “Night Transfer”

The design should feel like a premium consumer utility with a slightly futuristic edge.

Visual references:
- dark-mode file manager
- modern music player
- secure transfer utility
- soft aurora gradients

Avoid making it look like a corporate file manager.

### Color tokens

| Token | Hex | Use |
|---|---|---|
| Background | `#08090D` | Primary background |
| Surface | `#11131A` | Cards |
| Surface 2 | `#171A23` | Sheets / dialogs |
| Primary | `#8B7CFF` | Main CTA |
| Secondary | `#46E6C8` | Connected / success |
| Accent | `#74B9FF` | Progress / links |
| Warning | `#FFC970` | Waiting / attention |
| Error | `#FF6978` | Rejected / failed |
| Text | `#F8F9FC` | Primary |
| Muted | `#8991A3` | Secondary |

### Gradient

Hero gradient:

`#8B7CFF → #46E6C8`

Use only in hero moments, QR glow, and transfer-complete states.

### Typography

- Display: `Manrope` / `Space Grotesk`
- Body: `Inter`
- Transfer metrics: `JetBrains Mono`

### Shape

- 20–24dp rounded cards
- 14–18dp buttons
- Large circular device indicators
- Minimal borders
- Soft glow only around active state

---

# 9. Device Visualization

Use a simple abstract phone silhouette rather than OEM-specific phone renders.

States:

```text
DISCOVERING
   ↓
PULSING RING

CONNECTED
   ↓
SOLID LINK / SIGNAL

TRANSFERRING
   ↓
FLOWING DOTS BETWEEN DEVICES

COMPLETE
   ↓
CHECKMARK + PARTICLE BURST
```

This creates a strong demo moment without requiring custom 3D assets.

---

# 10. Motion Design

## Discovery

- Search pulse every 1.5s
- Nearby-device dots float slowly
- Device cards enter from 8–12dp vertical offset

## Pairing

- QR card scales from 0.96 → 1.0
- Authentication digits reveal with a slight stagger

## Transfer

- Progress bar is smooth, no fake jumps
- Small particle / waveform animation can travel from sender to receiver
- Transfer speed updates at 2–4 Hz, not every raw callback

## Complete

- Progress bar snaps to 100%
- Checkmark draws in
- Subtle success pulse
- Time statistic counts to the final measured duration

---

# 11. Information Architecture

```text
Home
 ├── Send File
 │    ├── Nearby Devices
 │    ├── Pairing
 │    ├── Incoming approval
 │    ├── Transfer
 │    └── Complete
 │
 ├── Send Text
 │    └── same connection flow
 │
 ├── Receive
 │    └── Device visible state
 │
 └── History
      └── Transfer detail
```

Keep navigation shallow. The entire product should be understandable without onboarding slides.

---

# 12. Technical Architecture

## Suggested stack

- Kotlin
- Jetpack Compose
- Material 3 base components with custom theme
- Google Play Services Nearby Connections
- Coroutines / Flow
- ViewModel
- AndroidX Activity Result APIs
- Android Storage Access Framework
- QR encoder / decoder library

### Suggested modules

```text
app/
 ├── ui/
 │    ├── home/
 │    ├── discovery/
 │    ├── pairing/
 │    ├── transfer/
 │    ├── history/
 │    └── components/
 ├── nearby/
 │    ├── NearbyManager
 │    ├── DiscoveryController
 │    ├── ConnectionController
 │    └── PayloadController
 ├── transfer/
 │    ├── TransferSession
 │    ├── TransferQueue
 │    └── TransferRepository
 ├── pairing/
 │    ├── SessionCode
 │    └── QrPayload
 └── storage/
      └── FileRepository
```

---

# 13. Nearby Connections Configuration

## Service identity

Use the application package name as the Nearby Connections `serviceId`, matching Google's recommended pattern. citeturn623999search0

## Strategy

For the two-phone MVP, use a one-to-one-friendly strategy and stop discovery once the target device is selected. Google's documentation notes that discovery can involve heavy radio operations and recommends stopping discovery when it is no longer needed. citeturn623999search0

## Connection lifecycle

```text
onEndpointFound
      ↓
show device
      ↓
requestConnection
      ↓
onConnectionInitiated
      ↓
show auth UI
      ↓
accept / reject
      ↓
onConnectionResult
      ↓
CONNECTED
```

The receiver should not accept automatically in the polished UX even though the underlying API permits automatic acceptance.

---

# 14. Payload Protocol

Use two logical payload types.

### TEXT

```json
{
  "type": "text",
  "id": "uuid",
  "text": "..."
}
```

### FILE_METADATA

Send metadata as a bytes payload before the file payload:

```json
{
  "type": "file",
  "id": "uuid",
  "name": "proposal.pdf",
  "size": 5033164,
  "mime": "application/pdf"
}
```

Then send the Nearby file payload.

Receiver maps payload ID → metadata so the saved file gets the correct name and MIME type.

### Multi-file

Queue:

```text
metadata 1 → file 1
metadata 2 → file 2
metadata 3 → file 3
```

MVP can limit the active transfer to one file at a time while presenting a batch queue.

---

# 15. File Selection

Use Android's Storage Access Framework rather than assuming broad filesystem access.

Recommended intents:

- `ACTION_OPEN_DOCUMENT`
- `ACTION_OPEN_DOCUMENT_TREE` only when folder selection is needed

Android documents these APIs as the standard user-driven way to choose files and directories. citeturn331536search2

### Supported MVP file categories

- PDF
- Images
- Video
- Audio
- ZIP / archives
- Documents
- Any generic file URI accessible to the user

Display a category icon using MIME type, with a generic fallback.

---

# 16. Permissions and Platform Constraints

Nearby Connections requires the appropriate Android permissions for the OS version and transport being used. Google's current Android guidance includes Bluetooth permissions, location-related permissions for older OS ranges, `NEARBY_WIFI_DEVICES` on newer versions, and notes an additional local-network permission starting with Android 17 / SDK 37 for applicable Wi-Fi LAN usage. citeturn601676search0

For the one-day MVP:

- Target a modern Android API.
- Test on two real Android phones.
- Do not promise every OEM / ROM combination.
- Build an in-app permission checklist.
- Explain why nearby permissions are needed.

A permission state screen can show:

```text
BLUETOOTH         ✓ Ready
NEARBY DEVICES    ✓ Ready
LOCATION          ! Required on this Android version
NOTIFICATIONS     ✓ Ready
```

Android 13+ also uses a runtime notification permission, so notification handling should be conditional by API level. citeturn623999search7

---

# 17. Security Requirements

### Mandatory

- Do not auto-accept unknown devices.
- Display the sender name and transfer metadata before acceptance.
- Use Nearby Connections authentication digits / token confirmation.
- Expire QR session data quickly.
- Never transmit the file before receiver acceptance.
- Keep no server copy.
- Delete temporary transfer state after completion or cancellation.

### Threat model for MVP

This is designed for a nearby-device transfer demo, not enterprise-grade file security.

The core protection is explicit user consent + Nearby authentication + short-lived pairing data.

---

# 18. Transfer History

Keep history locally with a lightweight datastore.

Example row:

```text
✓ proposal.pdf
Sent to Rahul's Phone
4.8 MB · 2.3s
18 Sep 2026 · 12:41
```

Do not store file contents in history.

Optional:

`Clear history`

---

# 19. Error States

### Device not found

```text
No nearby devices yet.

Make sure both phones have DropLocal open.

[ SCAN AGAIN ]
```

### Permission denied

```text
Nearby access is required to discover devices.

[ OPEN SETTINGS ]
```

### Connection failed

```text
Could not establish the connection.

Both devices should stay nearby with the app open.

[ RETRY ]
```

### Transfer failed

```text
TRANSFER INTERRUPTED

2.9 MB of 4.8 MB received

[ RETRY ]   [ CANCEL ]
```

---

# 20. Demo Script

### 45–60 second client demo

**Phone A**
1. Tap Send File.
2. Choose `proposal.pdf`.
3. Nearby Devices appears.
4. Tap Phone B.
5. Show authentication digits / QR.

**Phone B**
6. Incoming Transfer appears.
7. Tap Accept.

**Both**
8. Watch synchronized progress.
9. Show `4.8 MB · 2.3 seconds`.
10. Open the file.
11. Send a short text back.

The memorable line is:

> **No account. No cloud upload. Just device-to-device.**

Do not claim that the app uses “zero network technology.” It uses local radio/network capabilities under the hood. The accurate statement is that the MVP does not require an internet connection or application backend for the transfer.

---

# 21. MVP Delivery Plan

### 5–7 hour implementation target

**Hour 1:** Project setup, Compose theme, home, permissions.

**Hour 2:** Nearby advertising + discovery.

**Hour 3:** Connection lifecycle + authentication UI.

**Hour 4:** File picker + file payload transfer.

**Hour 5:** Transfer progress + receiver save flow.

**Hour 6:** Text payload + QR pairing.

**Hour 7:** History, animations, real-device testing, polishing.

For the strictest deadline, the fallback order is:

`Nearby discovery → authenticated connection → file transfer → progress UI → text → QR → history`

---

# 22. Portfolio Positioning

DropLocal demonstrates:

- Native Android development
- Peer-to-peer networking
- Nearby device discovery
- Connection lifecycle management
- Authentication
- File I/O
- Runtime permissions
- Android Storage Access Framework
- Real-time transfer state
- QR-based UX
- Offline-first architecture

Best portfolio framing:

> “I built an offline Android peer-to-peer transfer app using Nearby Connections, with authenticated device pairing, live transfer telemetry, and QR-assisted discovery.”

This tells a stronger engineering story than another CRUD or tracker app because the demo visibly involves **two devices communicating in real time**.

---

# 23. Competitive / Feasibility Context

This concept is technically credible. Open-source projects already implement nearby/offline sharing patterns using Nearby Connections or local Wi-Fi networking, including file and byte payloads. Examples include a Nearby Connections file-sharing plugin, LocalShare, and LocalSend. citeturn302679search0turn302679search2turn302679search3

Those projects are references for feasibility, not dependencies. DropLocal should keep its own simpler product scope and UX.

---

# 24. Technical References

- Nearby Connections overview / discovery: https://developers.google.com/nearby/connections/android/discover-devices
- Nearby Connections setup / permissions: https://developers.google.com/nearby/connections/android/get-started
- Nearby Connections connections / authentication: https://developers.google.com/nearby/connections/android/manage-connections
- Android shared document storage: https://developer.android.com/training/data-storage/shared/documents-files
- Android Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- Android 13 notification permission changes: https://developer.android.com/about/versions/13/behavior-changes-all
- Example Nearby Connections project: https://github.com/mannprerak2/nearby_connections
- Example local sharing project: https://github.com/defname/LocalShare
- LocalSend reference project: https://github.com/hero/localSend
