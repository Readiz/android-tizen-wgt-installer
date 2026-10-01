# Build

Configured versions: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.11.1, JDK 17,
compile/targetSdk 36, minSdk 26, Build Tools 35.0.0. AGP 8.10 supports API 36:
https://developer.android.com/build/releases/agp-8-10-0-release-notes

APK outputs remain untracked; no Android SDK, stub platform JAR or fabricated wrapper is shipped.
Use installed Gradle, Android Studio, or the included GitHub Actions workflow.
The absence of a wrapper is explicit; CI installs the specified Gradle distribution.

```sh
gradle --no-daemon :core:check :app:assembleDebug :app:lintDebug
# APK after success: app/build/outputs/apk/debug/app-debug.apk
```

Android UI/localization checks (requires an emulator or a dedicated test device):

```sh
gradle --no-daemon :app:connectedDebugAndroidTest
```

These tests exercise English defaults on a Korean configuration, persisted language selection,
translated status/errors, input validation, input preservation across language changes/recreation,
the separate illustrated TV guide in both languages, the YouTube default and optional sources, local document import, exclusion of simulator classes from the APK,
and repeated app lookup / repository edits after file selection and publisher consent.
AndroidX dependencies are test-only. Both translations are bundled together for offline language switching.

Hardware-free development:

```sh
./scripts/test-core.sh
./scripts/run-demo.sh
```

The test script requires Kotlin, JDK and OpenSSL on the development machine.
Those are NOT Android user/runtime requirements. OpenSSL only generates/verifies
synthetic test certificates; production signing uses JCA.

Target 36 behavior: app uses INTERNET permission for local networking. On a future
migration to targetSdk 37+, implement ACCESS_LOCAL_NETWORK permission flow and tests:
https://developer.android.com/privacy-and-security/local-network-permission
Do not label the current target-36 prototype as fully tested on Android 17.

Live release smoke check (explicit network opt-in, excluded from ordinary `check`):

```sh
gradle --no-daemon :core:releaseSmoke
```

This downloads the current public SushyDev/tizen-youtube release through production
SafeHttp/GitHubReleases, then uses synthetic certificates and a loopback-only TV.
It checks each WGT, preserves every non-signature payload byte, independently
validates XMLDSig, and exercises first install plus same-version reinstall with
the original Author. It never executes the downloaded application or contacts
Samsung/a physical TV. JSON evidence: `core/build/reports/tizen-youtube-smoke.json`.
See [the recorded 0.2.1 result](TIZEN_YOUTUBE_TEST.md).
