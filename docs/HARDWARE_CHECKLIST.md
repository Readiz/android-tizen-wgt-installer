# Release gates (all hardware items pending)

## Android build / emulator
- [x] Real local Gradle build :app:assembleDebug and :app:lintDebug pass with Android SDK (2026-09-30, 0.2.1; 0 errors / 9 warnings).
- [x] Install generated APK and launch native UI in Android 15 emulator (0.2.3, 2026-10-01); phone runtime remains separate.
- [x] English/Korean UI, language persistence and input preservation across activity recreation; small-screen 160% text scale inspected (0.2.3).
- [x] Offline demo completes through native UI and foreground service on the emulator (0.2.3).
- The Android demo was removed in 0.2.4. The JVM simulator remains a developer-only test tool.
- [ ] Rotation/back navigation/cancel while listing assets or Samsung sign-in is safe.
- [ ] Foreground-service start restrictions, notification denial and timeout behavior.
- [ ] Keystore save/load, reboot continuity, tampered-record refusal and backup exclusion.

## Real Samsung TV (record phone model/Android, TV model/Tizen/firmware)
- [ ] Developer Mode host=phone Wi-Fi IP; restart; SDB handshake and exact DUID.
- [ ] Native phone browser Samsung authentication, 2FA and callback on localhost:4794.
- [ ] Samsung issues certificates accepting current mobile flow; verify CA and exact DUID.
- [ ] Public GitHub repo with one WGT, plus multi-WGT release selection.
- [ ] Package installs and launches; distinguish install from playback/feature success.
- [ ] Inspect transferred files: only WGT/profile, no key handoff.
- [ ] Second app on same TV reuses existing pair, no unnecessary new author issuance.
- [ ] Same app update preserves Author and application data.
- [ ] Existing differently signed app blocked; no uninstall/overwrite attempts.
- [ ] HTTP 401 fallback tested; complete lack of inventory is not mistaken for absence.
- [ ] Network drop in transfer vs after install command yields correct, non-replayed outcome.
- [ ] Phone IP change and TV originally set to 127.0.0.1 produce actionable guidance.
- [ ] Tizen 5.0, 5.5 and newer tested independently; do not generalize one successful model.

APK output is a build result, not evidence that Samsung sign-in or a TV accepted a WGT.
