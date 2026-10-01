# Verification report — WGT Installer

## Current local verification — 0.3.9 / 2026-10-01

- Renamed the install button to Install after steps 1 and 2 / 1·2단계 완료 후 설치. Updated existing localization expectations and README.
- APK/test APK build and existing screenshot capture passed on API 35; inspected both language layouts. No new behavioral tests or physical TV installation.
- VersionCode 18 / 0.3.9-experimental. APK SHA-256: `7ba363d168bfe9be55c9b248a2bfd67fa21870d59869a71ea771b617fedfba94`.

## Previous local verification — 0.3.8 / 2026-10-01

- Renamed the section 2 action to Custom install / 직접 설치, including its accessibility label and tooltip. Updated README wording and screenshots.
- APK build and existing screenshot capture passed on API 35; inspected the new label. Copy-only change; no new behavioral tests or physical TV installation.
- VersionCode 17 / 0.3.8-experimental. APK SHA-256: `05fd781d157160dc1cc00e91614bf889544745ae9796190be1ce238cb32ea39e`.

## Previous local verification — 0.3.7 / 2026-10-01

- Corrected the icon target: the action beside section 2 now uses the installation arrow; restored the original TV launcher icon. Login prompt behavior from 0.3.6 is unchanged.
- APK build and existing screenshot capture passed on API 35; inspected the section icon. No new behavioral tests or physical TV installation.
- VersionCode 16 / 0.3.7-experimental. APK SHA-256: `45fe1d560f9c9de3feb06897306fe174f5b40681b51a556597ec8eb71ff64a23`.

## Previous local verification — 0.3.6 / 2026-10-01

- Replaced the launcher artwork with an original TV/download-arrow adaptive icon, including a monochrome layer. Removed the home subtitle and persistent footer login message.
- Samsung login now shows a localized prompt before launching the browser. Confirmation consumes the pending URL; cancellation requests installation stop. The prompt survives activity recreation and is dismissed when installation ends. URLs remain in memory, outside saved state/logs.
- Clean build/lint passed: **0 errors / 5 existing warnings**. Two new Android regression tests passed on API 35, covering both languages, blocked browser launch before confirmation, activity recreation, exactly one intercepted browser launch, and ending installation before confirmation. Existing screenshot capture also passed. Login was simulated with an intercepted HTTPS intent, without a live Samsung account.
- Inspected bilingual home/login screenshots and the installed adaptive icon in Android app settings. README home/options screenshots refreshed.
- VersionCode 15 / 0.3.6-experimental; signing certificate matches 0.3.5. APK SHA-256: `524a39be9b802753292f14465351529d6b91e0b11f8779c6cb6709b2c6fb9afc`. No physical TV install or actual Samsung sign-in repeated.

## Previous local verification — 0.3.5 / 2026-10-01

- Replaced the missing-network message with “Connect to the same network as your TV” / “TV와 같은 네트워크에 연결하세요”. Allow the message to wrap to two lines; an available IP stays on one line.
- APK build passed. VersionCode 14 / 0.3.5-experimental. SHA-256: `e0f372eb2322bccc69cf92cc0dee78a8396ea36e9a7b4aa85bf212ed48fb7e8f`.
- Copy/layout adjustment only; no new runtime or physical-TV test.

## Previous local verification — 0.3.4 / 2026-10-01

- Added short labels beside header icons: About / Guide / Apps, localized as 정보 / 가이드 / 앱. Kept the compact placement and accessibility descriptions.
- Build and lint passed: **0 errors / 5 existing warnings**. Existing screenshot capture passed on API 35; English/Korean home layouts inspected and README screenshots updated.
- VersionCode 13 / 0.3.4-experimental; same signing certificate. APK SHA-256: `bb23bc4cc1e1fbafde2114fb652e8ca71c90dd0094afe96573a57e4ae438beb5`.
- Presentation-only update; no new behavioral tests or physical TV installation.

## Previous local verification — 0.3.3 / 2026-10-01

- Moved About to an 18 dp header icon, TV setup help beside the TV heading, and optional sources beside the app heading. Each icon keeps a 48 dp touch target and localized accessibility label/tooltip. Removed the three separate button rows.
- Build and lint passed: **0 errors / 5 existing warnings**. Two focused Android tests plus bilingual screenshot capture passed on API 35. Verified optional-source visibility/recreation and guide return preserving imported WGT/consent. Inspected English/Korean layouts; refreshed README images.
- VersionCode 12 / 0.3.3-experimental; unchanged signing certificate. APK SHA-256: `f903e0e6bb60012d9c2f99c4f3fc5a55d6eb43357160e4192b5d4af1a0bed050`.
- No physical TV installation repeated.

## Previous local verification — 0.3.2 / 2026-10-01

- Added 20 dp vector icons to the main action buttons and setup help; icons share the button text state colors. Removed the extra purchase-year sentence from English/Korean guidance and README.
- APK build and Android lint passed: **0 errors / 5 existing warnings**. Native English/Korean home, optional-source and file-selection screenshots inspected on API 35; README home/options images refreshed.
- VersionCode 11 / 0.3.2-experimental. APK signature matches 0.3.1. SHA-256: `6890ba0b5d35e20d9c5e9598a8afd961e6f74e1af4dcf873adfa7c7955f6720c`.
- Presentation-only changes; no new behavioral tests or physical TV installation. Existing screenshot instrumentation passed.

## Previous local verification — 0.3.1 / 2026-10-01

- Replaced the guide's text chevron with a 24 dp vector back arrow, centered in a 48 dp image button. The icon is independent of the system font and mirrors for RTL layouts.
- Guide references in the app and README now show only the official Samsung TV device guide.
- English/Korean file selection guidance uses TV model years: 2020 or newer → 5.5 file; 2019 → 5.0 file, explicitly distinguishing model year from purchase year. The mapping was checked against Samsung's official model table.
- Two focused Android tests passed on API 35: both guide languages and returning through the actual back button with selected WGT/consent preserved. The latter verifies the image drawable, accessibility description and 48 dp touch width.
- `:app:assembleDebug :app:lintDebug`: pass, **0 errors / 5 existing warnings**. VersionCode 10 / 0.3.1-experimental; APK signature matches 0.3.0 for in-place updates. Native English/Korean guide and file-selection screens were inspected on the API 35 emulator.
- APK SHA-256: `c43e3db4930c12eed1a7be58c5c1b21f539bc2686cd513e3d700ba98950d6fde`.
- Installation/signing behavior is unchanged. No physical-device reproduction with the user's custom font or TV installation was performed.

## Previous local verification — 0.3.0 / 2026-10-01

- tizen-youtube is the default. Other repositories and local WGT files live in a collapsed optional section. The TV setup guide opens as a separate activity with original TV/remote/keypad/settings diagrams and English/Korean text.
- `:core:check`: **81 passed, 0 failed**. New coverage exercises local WGT install/update through the real signing/SDB code against a mock TV, hash tampering, missing consent, malformed input and GitHub/local ownership boundaries. Existing GitHub tests pass after extracting the common install pipeline.
- `:app:connectedDebugAndroidTest`: **15 passed** on Android 15 / API 35. Includes default/optional flows, activity recreation, localized guide, Android content-provider WGT import, guide return preserving selection/consent, and repeated-lookup regressions. The file-picker result is injected using a test-only content provider; this does not claim coverage of every third-party picker UI.
- `:app:assembleDebug :app:lintDebug`: pass; lint **0 errors / 5 existing warnings**. versionCode 9 / 0.3.0-experimental. APK signing certificate matches 0.2.6, allowing an in-place update.
- English/Korean native screenshots inspected at 393 dp / 100% font and 320 dp / 160% font. Text wraps and the main/guide screens scroll; original diagrams remain within their cards. README screenshots refreshed.
- APK SHA-256: `cff91db98f6a70815a323cba105afdf43f93190a86414bd1b0d21e66d9d1e087`.
- No Samsung account issuance or physical TV install/playback was repeated. Local WGT end-to-end evidence is a mock TV; Android evidence covers import and UI state. The prior physical-TV result must not be read as a new-version test.

## Previous local verification — 0.2.6 / 2026-10-01

- Replaced the long setup paragraphs with four numbered steps, a TV menu path, visual remote number keys and a prominent phone IP. Troubleshooting is separately collapsed; the whole guide remains collapsed on first open. English is still the default.
- **11 Android tests passed** on Android 15 / API 35, including the repeated-lookup crash regressions, both guide languages, keypad accessibility text and optional troubleshooting toggles.
- Native English and Korean screenshots inspected at 393 dp and 320 dp widths, including 160% font scale. Content wraps/scrolls without overlap; phone IP uses single-line autosizing. README guide screenshot refreshed. Additional visual evidence is in ignored `build/ui-verification/`.
- `:app:assembleDebug :app:lintDebug`: pass; lint **0 errors / 5 warnings**. APK versionCode 8 / 0.2.6-experimental; signature verified with the existing signing certificate. SHA-256: `1546b30659c48c472206739abd7249a7e5d9d22db184bc507e7814a861c649e3`.
- This is a guide presentation update. Core installation/signing logic is unchanged; no physical TV installation or Samsung issuance was repeated.

## Previous local verification — 0.2.5 / 2026-10-01

- Reproduced the reported repeated `Use tizen-youtube` crash with a selected file and checked publisher consent: `IndexOutOfBoundsException` in `MainActivity.render`. Clearing consent invoked its synchronous listener after the model was emptied but before old radio buttons were removed.
- Centralized release reset: remove stale file views before clearing consent. The shortcut also ignores repeated callbacks during an active operation. The same reset covers `Find app` and repository edits.
- The new regression failed against 0.2.4 and passed after the fix. All **11 Android tests passed** on Android 15 / API 35, including four new cases for repeated shortcut clicks with/without consent, repeated lookup and repository editing. Fixtures seed the completed lookup state without requiring live GitHub results.
- Manual emulator checks fetched the real tizen-youtube v1.4.0 release, repeated the shortcut, selected a file, checked consent and retried. The app remained open, returned the file list, cleared selection/consent, and produced no crash log.
- `:app:assembleDebug :app:lintDebug`: pass; lint **0 errors / 5 warnings**. Core installation/signing logic is unchanged; no physical TV installation was performed.
- APK versionCode 7 / 0.2.5-experimental; signature verified with the same signing certificate as 0.2.4. SHA-256: `dd41f595a9ea230da38ccc202da4438b2a92a9854ccd4fcbf802ff08355a63d9`.

## Previous local verification — 0.2.4 / 2026-10-01

- Removed the Android demo entry point, service branch and simulator dependency. The JVM simulator remains available only for development and core tests. Real installation/signing core logic is unchanged.
- The optional English/Korean TV guide starts collapsed, preserves its expanded state across activity recreation, and explains Apps / App Settings, the 12345 hidden menu, remote 123 keypad, phone host IP and restart steps. Guidance was checked against Apps2Samsung and Samsung Developer documentation.
- `:core:check`: **78 passed, 0 failed**. `:app:assembleDebug :app:lintDebug`: pass; lint **0 errors / 5 warnings**.
- `:app:connectedDebugAndroidTest`: **7 passed**, Android 15 / API 35 arm64 emulator. Includes English first-run default on a Korean configuration, persisted language, localized status/errors, input validation and preservation, optional guide behavior in both languages, and simulator class exclusion from the APK.
- Refreshed and inspected native English screenshots for the home screen, actual tizen-youtube v1.4.0 file selection and expanded TV guide. README follows the TV / app / install flow and keeps first-time setup collapsed.
- APK versionCode 6 / 0.2.4-experimental; Android APK signature verified with the same signing certificate as 0.2.3. SHA-256: `2c76a9dfa1ebeb4b9f4eb63f9b9ea361dd309f9e216190b043a86d404dff5bab`.
- No physical TV installation or Samsung certificate issuance was repeated for this UI/demo-removal update. Previous hardware verification limits still apply.

## Previous local verification — 0.2.3 / 2026-10-01

- English is the first-run default, independent of the phone locale. English/Korean selection persists, including across activity recreation; both translations ship in the APK.
- The main screen now focuses on TV selection, app/file selection and installation. Setup help and technical diagnostics are collapsed; the offline demo lives under About. User-facing status/errors and service notifications are localized; original error diagnostics remain available in technical details.
- `:core:check`: **78 passed, 0 failed**. `:app:assembleDebug :app:lintDebug`: pass; lint **0 errors / 5 warnings** (3 existing synchronous persistence warnings, 2 newer test-library version notices).
- `:app:connectedDebugAndroidTest`: **5 passed**, Android 15 / API 35 arm64 emulator. Covers English default with Korean system configuration, persisted language, status/error/demo translations, initial controls and invalid repository, and input preservation across language change/recreation.
- Native screenshots inspected at 393 dp and 320 dp widths, including 160% text scale. English and Korean screens, collapsed details, and offline demo completion were checked. Evidence is in ignored `build/ui-verification/`.
- The Android app fetched the actual tizen-youtube v1.4.0 file list. Multi-file selection remained explicit; selecting the 5.5 file, consent and a sample private IP enabled installation. Switching to English preserved the file selection and consent. No real installation was started.
- Offline demo completed through the native UI and foreground service on the emulator. It used synthetic certificates and the loopback TV, not a physical TV.
- APK versionCode 5 / 0.2.3-experimental; Android APK signature verified. SHA-256: `0ab6f64dd1bb0c9cbb3d8a12ef83903095d85ecdb83bafad78478619ebcf71da`.
- Real Samsung issuance, real TV installation/playback, phone Keystore continuity and notification-denial behavior remain unverified in this iteration.

## Previous local verification — 0.2.2 / 2026-09-30

- User screenshot: TV discovery, SDB/inventory gate and Samsung login callback reached the CERTIFICATES phase; device profile recognition then failed. No raw account response or credential was provided or recorded.
- Root cause: the installer requested `/apis/v3/distributors` twice. Samsung Certificate Extension 2.0.75 uses v1 for device-profile.xml, then v3 for the PEM certificate.
- Both requests now use the same distributor CSR/key; profile bytes are preserved and not rejected merely for containing embedded PEM. Invalid response/HTTP failure stops issuance without retries or raw response text in UI errors.
- Corrected the order-based issuer mock to route by exact endpoint; six additional regressions cover embedded PEM, wrong response type, malformed XML, DTD, and HTTP refusal.
- `:core:check :app:assembleDebug :app:lintDebug`: **78 passed, 0 failed**, APK built, lint **0 errors / 9 warnings**.
- APK versionCode 4 / 0.2.2-experimental, valid v2 signature and same signing certificate as 0.2.1; update installation preserves existing app data.
- APK SHA-256: `59395c98565cd5474c9a6383c59bcc16ab956a6771b4fafd67e32f7d346d4c36`.
- Corrected flow still needs the user's real Samsung/TV retest; no claim of completed live issuance or TV installation.


## Previous local verification — 0.2.1 / 2026-09-30

- Real JDK 17, Gradle 8.11.1, Android SDK 36: `:core:check :app:assembleDebug :app:lintDebug` passed.
- Core: 72 passed, 0 failed. Lint: 0 errors, 9 warnings (synchronous persistence and untranslated UI strings).
- Debug APK: versionCode 3 / 0.2.1-experimental; APK signature verified with Android apksigner.
- `:core:releaseSmoke`: real GitHub HTTP and both tizen-youtube v1.4.0 assets passed digest, byte-preservation, independent XMLDSig and loopback install/reinstall checks.
- No connected Android device; Android runtime, Samsung issuance and actual TV installation remain untested.
- [Reproduction details and APK SHA-256](TIZEN_YOUTUBE_TEST.md).

The sections below are historical 0.2 evidence; their unbuilt-APK statements describe the earlier environment.

## Historical report — 0.2

## Actually executed

**72 passed, 0 failed.** See [complete output](core-test-output.txt).

`./scripts/test-core.sh` compiled the JVM core, simulator and tests, then ran the
assertion-based test runner with loopback-only mock endpoints. It made no real
Samsung requests and connected to no physical TV.

An additional independent demo invocation completed:

```text
SIMULATION PASS: DemoWgt001 1.0.0; 2 non-private-key files; exactly one install command
```

[Full demo log](demo-output.txt). This invocation used the already compiled classes
and called `dev.readiz.wgtinstaller.simulator.DemoMainKt`. It exercised the same
`RepoInstaller` class that the Android service is wired to use. The standalone
`scripts/run-demo.sh` was subsequently executed too: fresh compilation plus the same
successful end-to-end offline demo. Its complete output is in `demo-output.txt`.

Environment: Kotlin compiler 1.9.0, JDK 21.0.11, `-jvm-target 17`, OpenSSL 3.5.5.
OpenSSL only supplies independent synthetic test certificates and CSR verification.
JDK XMLDSig independently validates both generated WGT signatures and references.

## Coverage

- Private IPv4/identifier/ZIP/JSON/XML input constraints and limits.
- RSA CSR fields, chains, key matching and exact distributor DUID SAN.
- WGT signature replacement, original manifest preservation (including Public mode),
  deterministic archive timestamps for reproducible source hashes.
- Random Samsung callback state, form decoding, real loopback HTTP callback behavior.
- TCP fragmentation, SDB checksums/handshake, negotiated payload size and ACK order.
- File transfer completion, cancellation and deadlines; fixed staging paths only.
- Generic GitHub shorthand/URL input, asset selection and stable pinned snapshots.
- Wrong repo/domain, size/hash mismatch, missing checksum, non-WGT assets,
  draft/prerelease responses, 404 ambiguity and 403/429 refusal.
- HTTP 401 + SDB inventory fallback; progress != completion; no replay on uncertainty.
- Same-app updates preserve Author; lost key, unknown ownership and source/package
  collision block mutation; a receipt write failure is not full success.
- Offline demo generates synthetic certificates and runs actual signing/transport.
  Its only remote-upload destinations are WGT and profile; no key handoff.

## Not executed / not claimed

- Android APK compile, Android lint, emulator or device UI tests.
- Native Android foreground service, Keystore, permission or lifecycle tests.
- Real Samsung sign-in, 2FA, mobile redirect or live certificate issuance.
- Live GitHub requests from the implemented app. Network responses in automated
  core/demo tests are mock data, distinct from repository source inspection.
- Real TV SDB authorization, WGT acceptance, launch, playback or version compatibility.
- GitHub Actions build and artifact verification are separate from this local test report.
  The repository was created by the user; current CI results must be read from Actions.

No Android SDK, sdkmanager or Gradle was present in this container. A request for
the Gradle distribution failed with `curl: (6) Could not resolve host: services.gradle.org`.
No APK was fabricated and no stub Android platform was used to claim a build.

## Interpretation

This is a functional, locally verified JVM core and offline transport/signing demo,
plus unbuilt native Android application source. A mock server does not reproduce
Samsung's certificate trust, authorization policies, platform bugs or package manager.
Use [HARDWARE_CHECKLIST.md](HARDWARE_CHECKLIST.md) before a release.

## Repository publication check — 2026-09-30

The source bundle was restored and `./scripts/test-core.sh` was executed again:
**72 passed, 0 failed**. The offline demo also passed using the newly compiled classes.
The publication environment still has no Android SDK or Gradle. GitHub Actions is
configured to perform the first Android build; this report does not claim CI success.
