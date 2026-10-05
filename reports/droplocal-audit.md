# DropLocal — product, UI/UX and implementation audit

Date: 5 October 2026. Source revision: `eba7320` (current working tree, initially clean).

## Executive verdict

**Promising prototype, not yet a dependable MVP or demo-ready transfer utility.** The project has an appropriate native stack and recognisable product structure, but several central promises are not implemented correctly: readable dark UI, consent before transmission, reliable received-file saving, batch transfers, verified delivery and QR-assisted pairing.

This is an audit, not a repair. No application source was changed. Findings distinguish source/API-contract evidence from runtime observations and untested physical-device behaviour. Severity: **P1** = fix before trusting the core demo; **P2** = important correctness/usability issue; **P3** = polish or maintainability.

## Scope and evidence

- Reviewed README, the complete PRD, Gradle configuration, manifest, all production Kotlin files and both unit-test files.
- Traced sender/receiver authentication, file and text payloads, saving, retries, cancellation, history and navigation.
- Reviewed Compose layout, content colour, contrast, scaling, system insets, input state and recovery affordances. Web-only HTML/ARIA requirements were not applied to this native Android project.
- Runtime UI inspection uses Android API 36, the `DevDevice` emulator, 1080 × 2400 portrait and landscape, with normal and 2× font scaling. Runtime build provenance and final checks are recorded below.
- Small temporary JVM probes compiled the actual `QrPayload.kt` and `TransferSession.kt`; only Android's device-model field was replaced with a fixture. No test code was added to the application.
- No physical devices were connected. Successful offline transfers, real radio discovery, authentication synchronisation, transport speed and OEM compatibility are **not claimed as tested**.
- Signing credentials, keystores and private local configuration were not read or copied into this report.

## What is already good

- Kotlin + Compose + Nearby Connections is a sensible stack for the Android-only goal.
- One-to-one Nearby strategy and stopping discovery on target selection are appropriate.
- Actual Nearby authentication digits are exposed; connections are not automatically accepted.
- Files are selected with Storage Access Framework instead of broad file-read access.
- The code separates UI, transport, storage and transfer models into small, understandable files.
- Transfer updates use payload callbacks; receiver-side final processing waits for transport completion and local acceptance.
- Local history is capped at 10 records, excludes text/file contents, and application backup is disabled.
- Signing material is ignored by git; none was tracked in the inspected file list.
- Main buttons use standard Material components and large touch targets. The muted text colour has good contrast against the background (~6.3:1).

## High-priority findings

### A01 · P1 · Most standalone text is effectively unreadable in dark mode

**Evidence:** `app/src/main/java/com/droplocal/app/ui/theme/Theme.kt:35-38`; `app/src/main/java/com/droplocal/app/MainActivity.kt:21`; `app/src/main/java/com/droplocal/app/ui/home/HomeScreen.kt:37-38`.

`MaterialTheme` supplies the scheme but no root `Surface` or foreground content-colour provider. Standalone `Text` inherits a black foreground while the Android window background is `#08090D`. The home wordmark, discovery headings and instructions render almost black-on-black; the nominal contrast is ~1.06:1. Buttons/cards set their own content colour, explaining why some controls remain visible.

**Fix:** Wrap the application content in a full-screen Compose `Surface` with `background` and `onBackground`, then validate every screen, dialog and state. Do this before adding visual polish.

### A02 · P1 · Received-file access and Downloads saving do not obey the storage contract

**Evidence:** `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:176-183`; `app/src/main/java/com/droplocal/app/storage/FileRepository.kt:43-55`; `app/src/main/java/com/droplocal/app/ui/MainViewModel.kt:40-46`; `app/src/main/AndroidManifest.xml:5-24`.

The receiver relies on `asJavaFile()` and returns immediately if it is unavailable. Nearby's documented scoped-storage path is the payload's URI through `ContentResolver`, not another process's raw filesystem path. The save operation then directly copies to public Downloads; general documents on modern Android need a supported MediaStore/SAF path. On API 26–28 the manifest also lacks the legacy storage-write permission for that raw public-directory operation.

Worse, any save exception is swallowed and the source is substituted as the destination; the transfer is still marked `DONE`. The UI can therefore promise successful saving without a correctly named, durable user-accessible file.

**Fix:** Retain the received payload URI, stream it through `ContentResolver`, publish to MediaStore Downloads on API 29+ or a user-selected SAF destination, and handle legacy versions explicitly. Record a usable output URI and only declare receive completion after successful persistence. Surface saving failures instead of converting them into success.

### A03 · P1 · Per-file acceptance happens after transmission has already started

**Evidence:** `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:284-297,176-191,300-305`; PRD §17.

`sendFile()` sends metadata and immediately sends the file. The receiver's acceptance dialog appears only when the file payload starts arriving. Acceptance merely gates the final copy; it does not authorise transmission. Small files may finish before the user decides, and rejecting afterward cannot undo receipt. This contradicts “Never transmit the file before receiver acceptance.” Nearby itself may already have stored the incoming payload.

**Fix:** Add an offer → explicit accept/reject response → payload-send protocol. Show file/batch metadata before sending any file bytes. Keep connection acceptance and transfer consent as separate actions.

### A04 · P1 · Metadata/file association drops or mislabels payloads

**Evidence:** `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:158-179,289-293`.

The receiver takes the first metadata entry from a FIFO when a FILE arrives. Metadata does not contain the Nearby payload ID. Google's API guarantees order within a payload type, **not across BYTES and FILE types**. A FILE arriving before its metadata is discarded by `firstOrNull() ?: return`; its updates then have no transfer mapping. After a lost/failed metadata payload, later files can be assigned earlier metadata. Retrying uses the same application transfer ID without clearing old associations.

**Fix:** Create the file payload first, include its payload ID in the offer/metadata, and maintain independent metadata and payload maps keyed by that ID. Reconcile either arrival order and validate endpoint, unique transfer ID, MIME and size.

### A05 · P1 · Multi-file selection is not a real queue and approval is overwritten

**Evidence:** `app/src/main/java/com/droplocal/app/ui/MainViewModel.kt:59-77`; `app/src/main/java/com/droplocal/app/ui/NavGraph.kt:168`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:188-191`; `app/src/main/java/com/droplocal/app/transfer/TransferSession.kt:31-43`.

Every selected file is launched without waiting for the previous file's completion or receiver decision. `TransferQueue.active()` does not schedule work; the screen displays `queue.lastOrNull()`. A small final file can show “complete” while an earlier large file is still running. Each incoming file replaces the single `_incomingFile` value, so earlier approvals can disappear and those files remain stranded without a user action. Cancelling the displayed last file does not explicitly cancel all other batch sessions.

**Fix:** Implement one active transfer and an explicit pending queue, or introduce a single batch offer and batch state. Display file X of N, total size and aggregate result, and make cancel-all semantics explicit. The temporary JVM probe confirmed that the active and displayed file can differ.

### A06 · P1 · Text sender reports completion on dispatch, not delivery

**Evidence:** `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:269-281,198-199`.

The success listener on `sendPayload()` marks text complete, but Google's contract says this means accepted by the local Nearby stack, not necessarily transmitted or delivered. The text payload ID is never mapped into `payloadToTransfer`, so subsequent transmission failure is ignored. A disconnected/interrupted receiver can leave a false-success entry and screen. The UI also has no encoded-payload size limit for large pasted text; enforce the library's byte limit including JSON/UTF-8 overhead rather than a guessed character limit.

**Fix:** Track the bytes payload ID and finish on its SUCCESS callback; optionally require a receiver acknowledgement for user-visible delivery. Handle FAILURE/CANCELED and expose an encoded-byte limit before dispatch.

### A07 · P1 · Retrying a failed transfer creates duplicate History keys

**Evidence:** `app/src/main/java/com/droplocal/app/storage/HistoryStore.kt:43-54`; `app/src/main/java/com/droplocal/app/transfer/TransferRepository.kt:18-33`; `app/src/main/java/com/droplocal/app/ui/history/HistoryScreen.kt:37`.

Failure records a session; retry preserves its ID; completion prepends another record with the same ID. `LazyColumn` uses that ID as a unique key. Opening History after a failed-then-successful retry can throw a duplicate-key exception. Repeated failure or completion callbacks also generate duplicates. This is a source-confirmed reachable path, not a claimed reproduced transport crash.

**Fix:** Upsert history by transfer ID, or create unique attempt IDs and key by those. Snapshot the final session before launching the history write; currently the asynchronous job rereads mutable queue state.

### A08 · P1 · File copying blocks the UI and cancellation

**Evidence:** `app/src/main/java/com/droplocal/app/ui/NavGraph.kt:55-59`; `app/src/main/java/com/droplocal/app/ui/MainViewModel.kt:53-76,40-47`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:52,357`; `app/src/main/java/com/droplocal/app/storage/FileRepository.kt:35-39,54`.

File inspection and full SAF-to-cache copies run synchronously from the Activity Result callback. Incoming files are also copied synchronously through a callback invoked on `Dispatchers.Main`. For large video files, a slow document provider or low storage this blocks rendering, progress and cancel input and risks ANRs. Transfer navigation occurs only after sender copies finish.

**Fix:** Use lifecycle-owned coroutines with `Dispatchers.IO` for queries and file I/O, show Preparing/Saving states, propagate errors, and support cooperative cancellation. Nearby supports file descriptors, potentially avoiding the extra full sender copy.

## Functional and lifecycle gaps

### A09 · P2 · Pairing launches the file picker before connection success, even for text

**Evidence:** `app/src/main/java/com/droplocal/app/ui/NavGraph.kt:118-136`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:253-255,284-287`; `app/src/main/java/com/droplocal/app/ui/pairing/PairingScreen.kt:50`.

CONNECT is enabled even before an authentication token/pending endpoint exists. It invokes asynchronous acceptance and immediately launches the file picker. Selecting a file before both sides finish authentication fails with “Not connected.” The same handler runs for the Send Text route, unexpectedly opening file selection. If connection success occurs while that picker is foregrounded, the pending text can send anyway and create competing navigation.

**Fix:** Model send intent (files or text), disable confirmation until a fresh authentication challenge exists, enter a waiting state after local approval, and continue only after successful `onConnectionResult`. Follow the PRD's pick-content → choose-device order where practical.

### A10 · P2 · Permission/startup errors silently produce false scanning/visibility states

**Evidence:** `app/src/main/java/com/droplocal/app/MainActivity.kt:12-18`; `app/src/main/java/com/droplocal/app/ui/Permissions.kt:7-27`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:223-255`; `app/src/main/java/com/droplocal/app/ui/components/Dialogs.kt:59`.

Permission results are discarded. Discovery, advertising and connection tasks have no failure listeners; the state is changed before the operation succeeds. Denied access, unavailable radios/Play Services or repeated operations can leave an indefinite spinner or a false “visible” claim. The permission checklist exists but is never rendered. Camera and notifications are requested at startup despite no wired scanner or notification feature. On Android 12/12L the location branch requests FINE without COARSE, which also needs review against Android's joint runtime request requirement.

**Fix:** Separate mandatory transport permissions from optional feature permissions; request just in time, observe results and expose Settings recovery. Change operation state on successful task completion and show actionable failures/timeouts. Test denied/permanently denied access and radio/Play Services unavailability on the supported OS matrix.

### A11 · P2 · QR pairing is display-only, not an implemented feature

**Evidence:** `app/src/main/java/com/droplocal/app/ui/pairing/PairingScreen.kt:46-47`; `app/src/main/java/com/droplocal/app/ui/MainViewModel.kt:51`; `app/src/main/java/com/droplocal/app/pairing/QrPayload.kt:10-35`; application-wide scanner/parser search.

There is a QR image and decoder dependency, but no scanner screen/Activity Result integration or mapping from QR session to discovered endpoint. The session is never advertised or exchanged. Expiry is not enforced and “02:00” is static. `toJson()` interpolates strings without escaping, and parsing splits on commas rather than using JSON. JVM probes confirmed failure for a comma-containing device name, invalid JSON for a quote-containing name, and acceptance of an unsupported version, empty session and expired payload. The 8-hex-character session is only 32 bits; do not treat it as an authentication credential. The unused `SessionCode` surrogate is not the actual shared Nearby challenge.

**Fix:** Either remove the QR promise from the MVP UI/docs or implement scanner → schema/expiry validation → active endpoint match → normal Nearby authentication. Use real JSON encoding and a full random session identifier; show a live countdown and renew/disable expired sessions.

### A12 · P2 · System Back and configuration changes bypass transport ownership

**Evidence:** `app/src/main/java/com/droplocal/app/ui/NavGraph.kt:115,129,164,183-186`; `app/src/main/java/com/droplocal/app/MainActivity.kt:25-27`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:240-267`.

Cleanup is attached to explicit on-screen buttons, not Android's system Back. System Back from Discovery returns Home while its status still says “Scanning…”. On pairing/transfer routes the same pattern can leave pending authentication or live work behind. `stopAll()` stops advertising/discovery but does not disconnect endpoints or reject pending connections, while forcing state to IDLE. Conversely Activity destruction (including rotation) invokes `stopAll()` even though the transport object survives in the Application, desynchronising UI and actual connection state.

**Fix:** Unify system and in-app Back handling, define cancel/leave behaviour for active work, and scope transport ownership explicitly. Keep scan, advertisement and connection states separate; do not use a single misleading “stop all” operation. Do not treat rotation as user-requested disconnect.

### A13 · P2 · Cancel/disconnect does not reliably settle transfers or release temporary data

**Evidence:** `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:111-116,215-218,308-337`; `app/src/main/java/com/droplocal/app/transfer/TransferRepository.kt:14-37`.

Disconnect clears only the connection flag; it does not settle active/queued sessions. Rejection/cancellation removes file references without deleting the actual temporary payload. Sender cached files, outgoing retry sources and success mappings are never released, and the in-memory queue grows without bounds. A late progress callback can turn a cancelled/failed session back into RUNNING; CANCELED callbacks use `fail()`, and local `cancel()` does not record history. Cancelling the current transfer does not explicitly clear the incoming dialog. Retry on receiver/text failures is presented but unsupported by `retryTransfer()`.

**Fix:** Enforce terminal-state transitions, settle every affected session on disconnect, separate cancellation from failure and persist its result. Dismiss related offers, delete owned temporary files/URIs on safe terminal paths, bound completed queue state, and expose Retry only where implemented. Keep retry caches only for an explicit bounded period.

### A14 · P2 · Received filenames and metadata are trusted as filesystem paths

**Evidence:** `app/src/main/java/com/droplocal/app/storage/FileRepository.kt:44-54`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:158-170`.

A paired peer controls the name, ID, MIME and declared size. The name is joined directly to Downloads without canonical containment/basename validation. A JVM probe showed `../Documents/probe.txt` resolves outside Downloads. Scoped-storage failures currently restrict practical writes, but this remains a path-boundary bug, not permission to claim arbitrary-device-file access. Empty/duplicate IDs, negative/false sizes, oversized metadata and a payload/metadata mismatch are also unchecked.

**Fix:** Generate a local storage identity, sanitise the display name to a safe basename, reject traversal/separators and invalid lengths, and validate protocol fields/uniqueness and actual payload size. Preserve the original user-facing name separately where appropriate. Do not merely add broader storage permission to make raw paths work.

### A15 · P2 · Open/share and completion actions are missing

**Evidence:** `app/src/main/java/com/droplocal/app/ui/transfer/TransferScreen.kt:65-71`; `app/src/main/java/com/droplocal/app/ui/MainViewModel.kt:31-32,44`; `app/src/main/java/com/droplocal/app/ui/components/Dialogs.kt:38-50`; `app/src/main/java/com/droplocal/app/ui/history/HistoryScreen.kt:38-57`.

Received-file completion exposes only HOME; the saved-file flow has no UI consumer. There is no ACTION_VIEW, ACTION_SEND or usable output-URI flow. Text offers Copy/Close, not Share, and truncates its preview to 500 characters without indicating hidden content or exposing a full selectable view. History items have no detail/open action. These are explicit PRD outcomes, not optional cosmetic improvements.

**Fix:** Retain a persisted URI/MIME for received files and wire Open/Share with URI grants and missing-handler recovery. Add text Share/full preview and appropriate receiver-specific completion actions. History can indicate a deleted/unavailable destination rather than pretending every file remains accessible.

### A16 · P2 · Unknown and empty file sizes produce misleading progress

**Evidence:** `app/src/main/java/com/droplocal/app/storage/FileRepository.kt:16-31`; `app/src/main/java/com/droplocal/app/transfer/TransferSession.kt:23`; `app/src/main/java/com/droplocal/app/nearby/NearbyManager.kt:201-209`; `app/src/main/java/com/droplocal/app/ui/transfer/TransferScreen.kt:55-63`.

Unknown SAF size is coerced to zero, Nearby's `update.totalBytes` is ignored, and zero-sized sessions always show 0% even when DONE. `complete()` overwrites bytes with declared size, discarding a more accurate transferred count. Small text/files are formatted as “0.0 MB”. “Saving to Downloads” appears on sender and text screens too. Speed is an average from session creation (including preparation and approval), not an isolated transport measurement.

**Fix:** Represent unknown size explicitly; use indeterminate progress until actual totals are known and 100% for successful completion. Choose B/KB/MB/GB appropriately, make copy/status direction-aware, and distinguish preparation, transfer and saving times. Avoid fake precision and stale ETA on terminal states.

## UI/UX and accessibility

### A17 · P2 · Insets, landscape and large text can obscure essential content

**Evidence:** `app/src/main/java/com/droplocal/app/ui/home/HomeScreen.kt:32-52`; `app/src/main/java/com/droplocal/app/ui/discovery/DiscoveryScreen.kt:32`; `app/src/main/java/com/droplocal/app/ui/text/SendTextScreen.kt:29-35`; equivalent fixed Columns on pairing/transfer.

Screens use fixed padding and heights with no Scaffold/system-inset handling or vertical scrolling for content-heavy screens. On API 36 the discovery/text title enters the status-bar region. Landscape Home loses History/status below the usable layout; at 2× font scaling the long Receive label visibly clips to “RECEIVE — MAKE THIS”. Long peer/file names and longer error messages further consume space. Pairing's fixed QR/code/buttons need small-window validation.

**Fix:** Use safe-drawing insets/Scaffold, responsive content widths, scrollable bodies, heightIn rather than forced button heights for multi-line content, and concise Receive labels with explanatory supporting text. Test landscape, split-screen, keyboard visibility and 200% text size.

### A18 · P2 · Primary/secondary button text fails normal-text contrast

**Evidence:** `app/src/main/java/com/droplocal/app/ui/theme/Theme.kt:12-13,28-29`; `app/src/main/java/com/droplocal/app/ui/home/HomeScreen.kt:46-49`.

White on primary `#8B7CFF` is ~3.27:1, below the 4.5:1 normal-text target. Send Text overrides only its container colour; it retains the button's default onPrimary white instead of the theme's dark onSecondary. White on mint `#46E6C8` is ~1.57:1. Dark `#06231D` on that mint would be ~10.6:1.

**Fix:** Set correct content-colour pairs and adjust primary foreground/background contrast. Preserve native focus/pressed/disabled semantics. Verify actual rendered normal-size text rather than assuming all bright colours are accessible.

### A19 · P2 · Text draft/pending intent is lost on recreation

**Evidence:** `app/src/main/java/com/droplocal/app/ui/text/SendTextScreen.kt:28`; `app/src/main/java/com/droplocal/app/ui/NavGraph.kt:51`.

Draft text and pending send content use `remember`, not saved state. Runtime reproduction: enter the synthetic `DropLocal-audit-draft`, rotate, and the editor returns to “0 characters”. Pending text during device selection can similarly disappear on Activity recreation. Leaving the editor provides no discard warning.

**Fix:** Put the draft and pending send intent in a ViewModel with SavedStateHandle or `rememberSaveable` as appropriate, clear only on deliberate discard/success, and guard leaving nonempty unsent content.

## Feature completeness against the PRD

“Present” below describes implementation, not an unperformed real-device acceptance test.

| Capability | Assessment | Main gap |
|---|---|---|
| Native Home / Send / Receive / History routes | Present; visually impaired | Foreground colours, insets, responsive layout |
| Nearby advertise/discover/device selection | Present, unverified on physical phones | No permission/task failure recovery; stale lifecycle state |
| Nearby authentication digits | Present | Premature continuation, stale challenge handling and weak waiting/error UX |
| Pick and send a single file | Present with major risks | Early send, main-thread copying, connection race |
| Receive consent | Partial | Approval follows transmission, not precedes it |
| Durable Downloads save | Not reliably fulfilled | Scoped storage/URI handling and false-success fallback |
| Multiple files as a queued batch | Not fulfilled | Sends overlap; only one approval and last session displayed |
| Send/receive text | Partial | Dispatch mistaken for delivery; draft loss; file picker in pairing flow |
| Progress / speed / ETA | Present, incomplete | Unknown sizes, zero-byte success, misleading timing/status copy |
| Cancel | Partial | No consistent terminal-state/batch/cleanup semantics |
| Retry | Partial | Connected sender file only; repeated IDs/history issues |
| QR-assisted pairing | Not implemented end-to-end | QR generation only; no scanner/matching/expiry flow |
| File Open / Share | Missing | No result URI/action integration |
| Text Copy / Share | Copy present, Share missing | Truncated preview without full text view |
| Last-10 local history | Present with correctness gap | Retry key duplication; cancellations not recorded |
| Permissions checklist and recovery | Component only, not integrated | Results ignored; unnecessary permission prompts |
| No application backend/cloud copy | Consistent with inspected architecture | Does not establish “no telemetry” for Google Play Services |

## Design and product improvements after correctness fixes

These are lower-priority recommendations, not additional P1 findings:

- **Information hierarchy:** simplify Home to Send file, Send text and Receive. Put visibility/connected state in a persistent, understandable status area with a Stop/Disconnect action. Current Receive wording implies a single action but requires another tap on the next screen.
- **Correct context:** Discovery always says SEND FILE, even when invoked by Send Text (`DiscoveryScreen.kt:33`). Use the actual sending intent, selected-content summary and a connecting/waiting state.
- **Empty/error states:** History has no explicit “No transfers yet” state. Failures should say what the user can do, not just a transport status number. Scanning needs a timeout, permissions/radio diagnosis and a usable retry path.
- **Destructive controls:** add confirmation or undo for Clear history (`HistoryScreen.kt:60`, `NavGraph.kt:193`), and disable it when history is empty. History failure/cancellation should be named, not represented by an ambiguous bullet.
- **Accessible semantics:** give the text editor a persistent label (placeholder alone disappears once populated), mark important headings and pairing digits appropriately, and make async connection/error announcements available to TalkBack. Native buttons already provide better semantics than custom gesture-only controls. TalkBack itself was not tested.
- **Visual polish:** the current UI is basic Material layout, not yet the PRD's premium “Night Transfer” presentation. Palette is present; custom typography, 20–24dp cards, device visualisation, discovery motion and a distinct completion hero are absent. Add these only after the transfer path is dependable, respecting system animation settings.
- **Localisation:** almost all UI copy is hardcoded Kotlin; dates/metrics force US formatting. Move copy to string/plural resources and use locale-aware dates/numbers and byte-size formatting.
- **Toolchain:** AGP 8.5.2 emits a warning that it was tested only through compileSdk 34, while the project compiles/targets 36. Plan a supported AGP/Gradle/Kotlin combination; do not blindly upgrade every dependency just because lint suggests a newer one. Deprecations include `asJavaFile()` and the old progress-indicator overload.
- **Privacy packaging:** retain the no-backup intention but explicitly configure Android 12+ data-extraction/device-transfer rules; lint highlights the manifest gap. No secret-scanning or dependency-vulnerability scan was run, so this audit is not a security certification.

## Checks actually run

### Build, tests and lint

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

- Initial attempt timed out after 120 seconds while preparing the environment; this was not an application build failure.
- Retried with an unbounded background timeout: **BUILD SUCCESSFUL**, 6m 23s, 54 executed tasks.
- Debug APK generated: `app/build/outputs/apk/debug/app-debug.apk`.
- **7 tests passed, 0 failures/errors/skips**: four QR/session-code tests and three progress/queue tests.
- **Lint: 0 errors, 33 warning records.** Of these, 27 dependency-version and 3 AGP-version warnings repeat across analysed variants; the remaining warnings concern an API-specific manifest attribute, data-extraction rules and an unnecessary SDK guard. This is not 33 distinct product bugs.
- Original detailed reports: `app/build/reports/lint-results-debug.html` and `app/build/reports/tests/testDebugUnitTest/index.html`.
- Local JDK 17 was selected per command; no global JDK/provider/project setting was changed.

### Runtime UI smoke test

The emulator initially had an older installed build. After Gradle finished, the **newly generated APK from the reviewed source was installed with `adb install -r`**, retaining app data. All retained report screenshots were refreshed using this newly built APK. No two-device transfer success is inferred from emulator UI checks.

Confirmed: application cold launch; Home → Send Text; editing a synthetic draft; draft loss on rotation; portrait/landscape Home; 2× font scaling; Home → Discovery; system Back returning Home with “Scanning…” still displayed; Receive entry screen. No user history was cleared and no files/text were transferred to another device. Emulator font scale and rotation settings were restored.

### Temporary source-level probes

| Probe using actual project models | Observed result |
|---|---|
| QR device name containing a comma round-trips | False |
| QR device name containing a quote produces valid JSON | No: quote is unescaped |
| Parser rejects unsupported version, empty session and expired input | No: returns a payload |
| Successful zero-byte session progress | 0.0 |
| First running large file vs final completed small file | Active = large; UI's lastOrNull selection = small |
| `../Documents/probe.txt` joined to Downloads | Canonical path escapes Downloads |

These bounded probes do not simulate Nearby Connections or prove full-file persistence. They were kept in approved temporary storage rather than added as permanent application tests.

## Screenshot evidence

Files are in `reports/droplocal-audit/` and show the freshly built debug app:

- [Home, normal font](droplocal-audit/home-baseline.png): black wordmark and low-contrast button text.
- [Home, 2× font](droplocal-audit/home-large-text.png): Receive action label visibly clipped.
- [Home, landscape](droplocal-audit/home-landscape.png): History/status disappear below usable content.
- [Discovery](droplocal-audit/discovery-baseline.png): black instructions and title under the status bar.
- [System Back result](droplocal-audit/system-back-scanning.png): Home still reports “Scanning…”.
- [Text editor](droplocal-audit/text-baseline.png): heading/inset problem and placeholder-only label.
- [Text editor after rotation](droplocal-audit/text-rotated.png): draft resets to empty/0 characters.
- [Receive entry](droplocal-audit/receive-baseline.png): core CTA available, heading almost invisible.

## Validation still required

The seven existing tests exercise happy-path QR parsing, code formatting, progress arithmetic and selecting a queue item. They do **not** cover storage, transport callbacks, offers/consent, cancellation, retries/history, permission denial, navigation or Compose layout. No Android instrumentation/UI test suite or CI workflow was found in the tracked files.

Before declaring this demo-ready, add/run the following acceptance matrix:

1. **Two physical phones:** successful file/text transfer in airplane/offline conditions with necessary local radios enabled; matching Nearby digits; mismatched code/reject flow; byte-for-byte destination checksum.
2. **Storage OS matrix:** API 26–28 legacy path, Android 10+, and current API 36 scoped storage; PDF/image/video/ZIP; duplicated filenames; no-handler Open action; full disk and denied/unavailable source/destination.
3. **Protocol:** metadata before and after FILE, failed metadata, duplicate/malformed IDs, unknown size, zero-byte file, large Unicode text, receiver approval delayed until after an offer, and reject without file transmission.
4. **Batch:** large first file + small final file, three separate approvals or one batch approval, rejection, cancel-all, retry failed item, truthful aggregate success.
5. **Lifecycle:** system and on-screen Back, home/background/resume, rotation during discovery/authentication/preparation/transfer, receiver disconnect, sender disconnect and process death. Explicitly define unsupported background behaviour.
6. **Recovery/history:** denied/permanently denied permissions, Bluetooth/Wi-Fi off, unavailable Play Services, connection timeout, failed → retry → success followed by History, cancellation records and stale callbacks after cancellation.
7. **Accessibility:** TalkBack, keyboard/focus traversal, 200% text, small landscape/split-screen, long filenames/peer names/errors, empty history, persistent editor label and announced connection/errors.
8. **QR if retained:** live scanner, active endpoint matching, stale/expired/foreign/malformed codes, session renewal, and proof that the QR shortcut never bypasses Nearby authentication.

## Recommended implementation order

### 1. Restore a trustworthy single-file demo

- A01/A17/A18: root foreground/background, safe insets and readable scalable controls.
- A02/A08: correct URI-based receive/persistence and background I/O; no false save success.
- A03/A04/A09/A10: explicit offer acceptance, payload-ID mapping, connection gating and permission/task recovery.
- A06/A07/A12/A13: delivery-based completion, stable history, terminal-state/lifecycle/cleanup semantics.

**Exit criterion:** send one file, approve it before any file bytes arrive, save it durably, compare hashes and open it; repeat after a failed attempt and a disconnect without crashing or false completion.

### 2. Complete the actual MVP features

- A05: real queued batch and aggregate progress/approval/cancellation.
- A15/A16/A19: Open/Share, correct telemetry and durable text draft/send intent.
- A11: implement QR end-to-end or clearly remove it from the MVP promise.
- A14: validate all peer-controlled metadata before persistence.

### 3. Polish and harden

- Add regression tests and a minimal build/test/lint CI gate.
- Improve empty/error states, TalkBack semantics and localisation.
- Modernise the toolchain deliberately, then add the requested typography/motion/device visuals.

## Bottom line

**19 prioritised findings: 8 P1 and 11 P2.** The debug app builds and its small existing test suite passes, but that evidence is not enough to establish the PRD's transfer guarantees. Prioritise correctness and trust over decorative redesign. With a stable consent/payload/storage state machine, the current small native codebase is a reasonable foundation to finish rather than replace.

## External contract references

- [Nearby exchange data](https://developers.google.com/nearby/connections/android/exchange-data): payload ordering, completion callbacks, scoped-storage URI access and file descriptors.
- [ConnectionsClient API](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient): `sendPayload` task success is dispatch acceptance, not delivery.
- [Android shared storage](https://developer.android.com/training/data-storage/shared/media): MediaStore Downloads, app ownership, legacy storage and SAF constraints.
