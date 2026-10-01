# Source traceability

## 0.3.1 / Displayed guide reference

The guide screen and README link only to [Samsung Developer — TV device setup](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html).
The dated research records below document earlier implementation work.

Reviewed 2026-10-01: [Samsung Developer — TV Model Groups](https://developer.samsung.com/smarttv/develop/specifications/tv-model-groups.html) maps 2019 models to 5.0 and 2020 models to 5.5, with later models using later platforms. The installation hint now uses model years (2020 or newer → 5.5 file; 2019 → 5.0 file), combining this official mapping with the upstream package selection guidance recorded below. These are model years, not purchase years; no new device compatibility test is claimed.

## 0.3.0 / YouTube default and illustrated setup guide

Reviewed 2026-10-01: [070본드, 삼성TV 타이젠브류 설치법 (Ppomppu, 2024-12-31)](https://m.ppomppu.co.kr/new/bbs_view.php?id=money&no=517404).
The post was read in a browser after the text fetch was blocked. Its Apps →
12345 → Developer Mode → Host PC IP → restart sequence informed the dedicated
guide. Comments also describe entering App Settings on newer models. The
instructions were rewritten for this phone installer: Host PC IP stays the
phone's Wi-Fi address. The PC workflow, later 127.0.0.1 switch, old compatibility
claims and photographs were not copied.

The remote 123 / on-screen keypad explanation is also supported by the
Apps2Samsung FAQ linked below. Original Canvas geometry illustrates the TV menu,
remote/keypad and developer settings. English/Korean explanatory text remains
native and accessible; no source photographs or branded remote images ship.


## 2026-10-01 / Android installer positioning review

The earlier README credited Apps2Samsung only for setup guidance. This review
corrects that incomplete description: its Android app is direct prior work for
the core phone-based installation flow. This is a product comparison record,
separate from the implementation adaptation records below.

| Official source | Confirmed scope |
|---|---|
| [Apps2Samsung README](https://github.com/Apps2Samsung/Apps2Samsung#readme) | Android installation, TV discovery, Samsung certificates, catalog/custom packages and TV management. |
| [v2.7.7, 2026-08-21](https://github.com/Apps2Samsung/Apps2Samsung/releases/tag/v2.7.7) | Mobile Automatic/Public/Partner certificate selection and Partner signing support. Android is already listed as Stable here. This does not establish its first release date. |
| [v2.7.9, 2026-09-02](https://github.com/Apps2Samsung/Apps2Samsung/releases/tag/v2.7.9) | Shared SDB engine, executed in-process on Android; phone remote and debug console. |
| [v2.8.1 mobile project](https://github.com/Apps2Samsung/Apps2Samsung/blob/v2.8.1/Apps2Samsung.Mobile/Apps2Samsung.Mobile.csproj) | .NET 10 Android/MAUI and a shared core for certificate provisioning, network discovery and SDB. |
| [tizen-youtube installation guide](https://github.com/SushyDev/tizen-youtube/blob/main/docs/README.md#apps2samsung-with-a-partner-certificate) | Explicitly documents Apps2Samsung with the user's Samsung Partner certificate. There is no basis for saying its authors did not know this alternative. |

Other prior work reviewed for execution-model context:

- [avivbenchorin/TizenAppInstaller](https://github.com/avivbenchorin/TizenAppInstaller): Android SDB implementation for Samsung watches and other SDB devices, with packages placed in app resources. This review does not establish modern TV certificate automation.
- [reisxd/TizenBrewInstaller](https://github.com/reisxd/TizenBrewInstaller): documents an Android route using Termux and Node.js.
- [Suz41/Sideload-Tizen-by-Android](https://github.com/Suz41/Sideload-Tizen-by-Android): documents Termux/Python-based phone installation.
- Tizen Homebrew's TV-hosted flow remains an architectural alternative, with implementation references recorded below.

These additional precedents are acknowledged as related work, not newly claimed
code dependencies. The product direction is a focused GitHub release installer;
PC-free installation and an Android SDB engine are not novelty claims. This
review used official documentation, release notes and the mobile project file,
not a comparative run of both APKs on the same TV. Equivalent repository-entry
paths elsewhere in Apps2Samsung have not been ruled out. Ease of use, speed,
reliability and compatibility advantages remain unmeasured.

## 0.2.4 / Optional TV setup guide

- [Apps2Samsung FAQ](https://github.com/Apps2Samsung/Apps2Samsung/wiki/FAQ#-how-to-enable-developer-mode-on-your-tv): Apps, remote 123 key / on-screen keypad, Developer Mode and power-cycle troubleshooting.
- [Samsung Developer: TV Device](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html): newer TVs use Apps > App Settings before entering 12345; set the developer host address and restart.

Consulted 2026-10-01. The English/Korean instructions are written for this phone-based installer, using its Wi-Fi IP instead of a desktop address. No upstream code or images were copied.

이 프로젝트는 독립 Android 프로토타입입니다. 기존 upstream 또는 Readiz/readiz-tv 저장소를 수정하지 않았습니다.

## 이전 v0.1 프로토타입에서 이어받은 원본 추적 기록

아래 blob 목록은 제공된 v0.1 아티팩트의 기록입니다. 이번 v0.2 작업에서 전부 다시 조회한 목록이 아닙니다.

| Source | Observed blob SHA | Used for |
|---|---|---|
| SushyDev/tizen-homebrew `service/src/tv/adb.js` | `8ee135277c2e126a5ad52e0d54becbf0bb064fb9` | SDB frames, maxdata, ACK flow control |
| SushyDev/tizen-homebrew `tools/installing.js` | `bfff01240c6f23100767934032f43faf8ac5e3f5` | sync framing, staging paths, install sequence |
| SushyDev/tizen-homebrew `service/src/install/signature.js` | `1d462711670eaf18b7d227530e27125c63408865` | XMLDSig template, SHA-512, C14N convention |
| SushyDev/tizen-homebrew `tools/minting.js` | `7ea5efa84daab7c80b7f891c496868c70d79fd98` | browser callback and issuance flow |

| reisxd/tizen.js `src/samsungCertificateCreator.js` | `b9aec8cc8e9ea0152dfbf8b353cba6835cf08d8b` | Samsung CSR fields, CA retrieval, REST endpoints |

Source repositories:
- https://github.com/SushyDev/tizen-homebrew
- https://github.com/reisxd/tizen.js

No claim is made that mutable upstream repositories remain at these SHAs. v0.2 removed the private-key handoff and the Homebrew-specific bootstrap engine.

## Android primary documentation consulted

- AGP 8.10 compatibility: https://developer.android.com/build/releases/agp-8-10-0-release-notes
- Local network permissions: https://developer.android.com/privacy-and-security/local-network-permission
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types

The app targets 36. Official guidance says not to request ACCESS_LOCAL_NETWORK before targeting 37; update the permission flow before raising the target. Android 16's opt-in local-network restriction testing is not implemented by this prototype.

## Implementation differences from the original Homebrew/tizen.js references

These describe the adaptation at the time, not exclusive features or a comparison
against Apps2Samsung and every other installer.

- Native Kotlin/JCA rather than a Node/Bun runtime.
- Fresh random callback state, not the upstream constant.
- Explicit SHA-256 asset verification and cloud-host restrictions.
- At-rest encrypted Android key vault; no automatic author rotation.
- No uninstall/replace command.
- HTTP failure is not interpreted as absence; `applist` is a fallback.
- No private-key handoff; the production uploader only permits WGT and device-profile.xml staging.
- Positive installation evidence is required before recording completion; a lost result is not automatically replayed.

## v0.2에서 추가 확인

- GitHub connector: SushyDev/tizen-homebrew `service/src/install/sources.js`, observed blob `58ddb13f729993eebf5c75eecff95bff93b44d2e`: latest release and WGT asset selection.
- https://docs.github.com/en/rest/releases/releases — public release/asset metadata API.
- https://github.blog/changelog/2025-06-03-releases-now-expose-digests-for-release-assets/ — release asset SHA-256.
- https://developer.android.com/privacy-and-security/local-network-permission — target 36 vs 37 LAN permission boundary.

These observations do not constitute a live GitHub download from the Android app, nor a Samsung/TV interoperability test.

## 0.2.1 / 2026-09-30 비교 확인

- reisxd/TizenBrew `docs/README.md`, blob `2b31fc991d776b395647b63bea6dbdaa77a1a546`: 모듈 구조, 개발자 호스트, Tizen 7+ Samsung 인증서 안내.
- reisxd/TizenBrewInstaller `client/services/tizenbrew-installer-service/utils/PackageInstallation.js`, blob `fd14e731c48cfdfb26ef5da89b163b69f1cc9682`: SDB vd_appinstall 명령 비교.
- SushyDev/tizen-homebrew `tools/installing.js`, blob `bfff01240c6f23100767934032f43faf8ac5e3f5`: 고정 staging/profile/WGT 경로 재확인.
- SushyDev/tizen-youtube `docs/README.md`, blob `4c41652f1a8d26a9835064b655095c2eb8929ef2`: 5.5+ WGT 선택과 Partner 인증서 설치 경로.

실제 GitHub WGT를 JVM에서 다운로드하고 모의 TV에 설치한 결과는 [시험 기록](TIZEN_YOUTUBE_TEST.md)에 있다. Android 실행·Samsung 발급·실제 TV 호환성은 아직 미검증이다.

## 0.2.2 / Samsung 프로파일 API 교정

사용자 실기기 오류를 조사하며 [공식 Samsung Certificate Extension 2.0.75](https://download.tizen.org/sdk/extensions/tizen-certificate-extension_2.0.75.zip)를 내려받아 `javap -c -p`로 DistributorGenerator를 확인했다. ZIP SHA-256은 `49f1aa8697e1c98a70a47c4ed232a29538340167bd9cceb121c7bea755045303`이다.

- 공식 [extension_info.xml](https://download.tizen.org/sdk/tizenstudio/official/extension_info.xml)이 가리키는 확장을 사용했다.
- JAR: `org.tizen.common.cert_1.0.0.202510211036.jar`, class: `org.tizen.common.cert.util.DistributorGenerator`.
- 생성자 bytecode 113–122: `/apis/v1/distributors`와 `device-profile.xml`을 연결한다.
- `fetchCRT()` bytecode 0–31: 프로파일 요청 후 VD 모드에서 `/apis/v3/distributors`로 인증서를 요청한다.
- 기존 tizen.js `b9aec8cc8e9ea0152dfbf8b353cba6835cf08d8b`는 v3를 두 번 호출한다. 이 부분은 공식 모듈의 v1/v3 분리를 기준으로 교정했으며, 기존 mock도 요청 순서에 따라 응답 종류를 바꾸지 않도록 수정했다.

공식 ZIP/JAR/분석 출력은 ignored build/certificate-protocol/에만 두며 Git/APK에는 넣지 않는다. 사용자 토큰으로 API를 호출하거나 실제 인증서를 발급하지 않았다.
