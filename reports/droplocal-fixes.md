# DropLocal audit remediation

Implemented against the audit in `reports/droplocal-audit.md`. Application version: **1.1.0-mvp / code 2**. Original audit evidence is retained unchanged.

## Delivered changes

| Audit findings | Implementation |
|---|---|
| A01, A17, A18 | Root dark Surface/foreground, accessible foreground pairs, safe system/IME insets, responsive scrolling, expandable touch targets, clear headings/live status and rounded cards |
| A02, A08 | URI-based receive persistence to MediaStore Downloads on API 29+, legacy Downloads/FileProvider support on API 26–28, rollback on failed/cancelled writes, actual bytes and background file I/O |
| A03, A04, A05 | Versioned bounded metadata offers with Nearby payload IDs, explicit receiver acceptance before file sending, one active sender file, FIFO incoming decisions, batch identity/count/size and true sender batch view |
| A06 | Text delivery finishes on payload SUCCESS, not local task acceptance; UTF-8/JSON byte limits, queued received-text dialogs and text remaining readable after disconnect |
| A07 | Upsert/deduplicate last-10 history, immutable ordered final-state snapshots, cancellation records and persisted output URIs |
| A09, A10 | Content selected before discovery, fresh auth gating, wait for both-device connection success, just-in-time permissions/readiness/Settings recovery and task failure/timeouts |
| A11 | Real ZXing scanner, strict expiring QR JSON, exact advertised session match, live countdown/renewal and normal Nearby code verification after QR selection |
| A12, A13 | Unified in-app/system Back, explicit cancellation/disconnection, no rotation teardown, terminal/generation guards, owned temp cleanup and bounded retry sources |
| A14 | Safe canonical basenames, bounded strict protocol fields/identity/size/MIME validation; malformed/unconsented file payloads rejected |
| A15 | Received-file Open/Share with content URI grants, full selectable received text with Copy/Share, inline history/result actions and useful empty/error states |
| A16 | Unknown/zero sizes handled, B–EiB locale formatting, correct sender/receiver statuses and honest session-average metrics |
| A19 | Controlled text draft and pending text in SavedStateHandle, rotation preservation and keep/discard-before-leaving dialog |

Further fixes: process-specific owned cache prefixes/orphan cleanup, received text dismissal no longer rejects unrelated file offers, history I/O failures are surfaced, optional camera/notification startup prompts removed, screen-awake policy during visible reception/active work, explicit backup exclusions, and an API-36-compatible AGP/Gradle pair (8.10.1/8.11.1).

## Behaviour and compatibility

- Update **both phones**; legacy MVP messages/advertisements are not accepted as the new consent protocol. Use matching debug/release variants.
- Receive makes the phone visible after required access is granted. Sender can select files and discover a receiver or scan its QR.
- QR is a discovery shortcut only. Both devices still compare the real Nearby digits and explicitly confirm.
- Each file is separately offered and approved. Receiver shows its batch position/count and known total; future files are not falsely marked received. Sender batch completion includes every staged item.
- File sender DONE requires both payload delivery and a correct receiver save acknowledgement. Persistence errors are failures, not fallback success.
- Results are saved under `Downloads/DropLocal`; clearing history or cancelling work does not delete already published files.
- Cancel all disconnects, ending peer-side queued work as well. Failed sender retry sources are retained for at most five minutes, three files and 512 MiB; other failures require reselecting/reconnecting.
- Discovery/advertising stop when the Activity leaves the foreground, not on rotation. Keep both apps open; no background-service/process-death transfer guarantee was added.

## Verification

Final combined command:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:connectedDebugAndroidTest --console=plain
```

**BUILD SUCCESSFUL** (1m 1s on the final incremental run).

- **56 JVM tests passed**, zero failures/errors/skips: protocol consent/state/IDs/batches/validation, QR parsing/expiry, terminal-safe queue/repository, history codec/upsert, file safety/cancellable copying, formatting, permissions and saved-state draft models.
- **16 Android emulator tests passed**, zero failures/skips on API 36: three real MediaStore persistence/rollback/safe-path tests; seven real-manager/fake-radio callback tests; five actual Activity/Compose navigation/draft/history/system-Back tests; one bitmap encode/decode test proving Unicode QR JSON survives the real QR image path.
- **Lint: zero errors, 18 warnings.** Remaining records are dependency/plugin update suggestions and optional KTX/modifier/manifest-attribute style recommendations. No lint baseline or global error suppression was introduced.
- `git diff --check` passed. The new `.github/workflows/android.yml` parses as YAML; its build/unit-test/lint gate has **not** been executed on GitHub here.
- Current APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Detailed reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, `app/build/reports/lint-results-debug.html`.

Verification found and fixed three integration issues before completion: a lint-detected expression/indentation ambiguity, Android ICU requiring escaped closing braces in strict JSON regexes (JVM tests alone missed this startup crash), and NavHost overriding a parent BackHandler. Back guards are now destination-owned and read live model state; device regression tests cover both unsent-text protection and stopping discovery on system Back.

Manual UI checks during the integration pass: readable normal/200% text Home, scrollable landscape including reachable History/status, labelled text input with character/byte counts, denied nearby access → readiness dialog → retry/grant → discovery, correct Send Text context in discovery, native system-Back draft protection, and just-in-time camera access opening the scanner and returning safely on cancellation. The camera was emulated; no actual peer QR match or physical-peer file/text transmission was attempted. Screenshots are under `reports/droplocal-fixed-ui/`; the original screenshots remain under `reports/droplocal-audit/`.

Visual evidence: [Home](droplocal-fixed-ui/home.png), [200% text](droplocal-fixed-ui/home-large-text.png), [scrollable landscape](droplocal-fixed-ui/home-landscape-scrolled.png), [text editor](droplocal-fixed-ui/text.png), [permission recovery](droplocal-fixed-ui/permission-recovery.png), [text discovery](droplocal-fixed-ui/discovery.png), [native Back guard](droplocal-fixed-ui/back-guard.png).

Instrumentation targets a disposable emulator/debug app. Its runner reinstalls/uninstalls the target and test packages; README now warns against running this suite on a device holding valuable debug draft/history state. Storage tests use only generated synthetic files and remove their published rows.

## Remaining validation / deliberately limited scope

- **Two physical phones are still required** to validate actual BLE/Wi-Fi discovery, mutual auth, QR camera scan → real endpoint match, Google Nearby source-URI grants, interrupted radio transfers, actual throughput and byte-for-byte destination hashes.
- API 26–28 legacy storage/permissions and other OEM/Android versions are implemented but were not device-tested here. Real TalkBack, keyboard/focus traversal and multiple file-viewer/share targets also need acceptance testing.
- Fake-radio tests validate application callbacks/protocol gates, not radio transport. Emulator MediaStore tests validate real publication, not a physical Nearby download.
- The app remains an English MVP. Dates/numbers/units are locale-aware; full translated resources, custom bundled fonts and decorative discovery/completion motion remain optional polish rather than falsely claimed complete features.
- No background transfer service, iOS/desktop client, folder sync or internet relay was added; these remain outside the PRD's MVP.

Next acceptance step: install the same updated variant on two phones and follow README's demo; test approve/reject, a three-file batch, cancel/disconnect/retry, QR renewal, text copy/share and opening a saved PDF. Compare source/destination hashes before declaring the transport production-ready.
