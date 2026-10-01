# Tizen 9.0 / tizen-youtube 시험

2026-09-30에 실제 공개 릴리스를 입력으로 검증했다. 사용자 휴대폰에서 TV 검색·Samsung 로그인 콜백 이후 기기 프로파일 단계의 오류가 보고됐다. 0.2.2는 프로파일 요청을 v1 API로 바로잡은 수정판이며 실제 발급·TV 설치 재시험을 기다린다.

## 휴대폰에서 시험

1. 최신 릴리스의 `wgt-installer-0.2.4-experimental.apk`를 휴대폰에 옮겨 기존 앱 위에 업데이트 설치한다. 기존 앱을 제거하거나 데이터를 지우지 않는다. Android 8.0 이상용 debug APK다.
2. 휴대폰과 TV를 같은 Wi-Fi에 연결한다. TV의 Developer Mode를 켜고 Host PC IP에 앱이 표시하는 **휴대폰 IP**를 입력한 후 TV를 재부팅한다.
3. 앱에서 **Use tizen-youtube / tizen-youtube 선택**을 누른다. `SushyDev/tizen-youtube`의 실제 최신 릴리스 목록을 조회한다. 이 버튼만으로 설치하거나 저장소 신뢰에 동의하지 않는다.
4. TV IP를 입력하거나 검색한다. Tizen 9.0 시험에서는 목록의 **5.5용**을 선택한다. 이번 검증 파일은 `tizen-youtube-1.4.0-tizen-5.5.wgt`다. 최신 릴리스가 바뀌면 이름·버전을 다시 확인한다.
5. 저장소를 확인한 후 배포자 신뢰에 체크하고 **Install on TV / TV에 설치**를 누른다. 0.2.3부터 기본 언어는 영어이며 상단에서 한국어를 선택할 수 있다.
6. 최초 실행에서는 브라우저에서 Samsung 계정 로그인을 진행하고 앱으로 돌아온다. 앱은 Partner 인증서를 요청한다. 인증서 발급과 설치는 이 휴대폰/TV에서 아직 검증되지 않았다.
7. 설치 완료 후 TV의 YouTube 앱 실행·영상 재생·TV 재부팅 후 실행을 각각 확인한다. **Host PC IP는 휴대폰 IP로 유지한다.**

실패하면 진행 기록의 마지막 단계·메시지, 휴대폰 Android 버전, TV 모델·펌웨어를 기록한다. 앱 데이터 삭제나 앱 제거는 저장된 Author 키를 잃게 하므로, 진단 중에는 하지 않는다. 기존 설치의 Author가 다르거나 설치 결과가 불명확하면 자동 삭제·재설치하지 않는다.

## 0.2.1 최초 검증 결과 (0.2.2 수정 결과는 TEST_REPORT.md 참조)

- 코어 테스트: 72 passed / 0 failed.
- `:app:assembleDebug`, `:app:lintDebug`: 성공. lint 0 errors / 9 warnings.
- minSdk 26 / targetSdk 36 / versionCode 3 / versionName 0.2.1-experimental.
- APK v2 서명 검증 성공. Android debug 서명이며 정식 배포 서명은 아니다.
- APK SHA-256: `32c00658561c311f161aba9977c9eefe5a13667d61b9c60997e9ac991d3db399`.
- `:core:releaseSmoke`: 실제 GitHub 조회·다운로드, 원본 파일 6개 전체의 바이트 보존, JDK XMLDSig 독립 검증, 루프백 모의 최초 설치·동일 버전 재설치·Author 재사용 통과.
- 각 WGT에서 TV로 보낸 파일은 WGT와 device-profile.xml 두 개이며 개인키를 보내지 않았다. 인증서는 합성 테스트용이고, WGT의 앱 코드는 실행하지 않았다.

검증한 릴리스: [v1.4.0](https://github.com/SushyDev/tizen-youtube/releases/tag/v1.4.0), release ID `391834742`.

| 대상 | asset ID | 크기 | package ID | SHA-256 |
| --- | --- | --- | --- | --- |
| 5.0 | 573639808 | 781366 bytes | tUb3Xq7L50 | c20eef17ddcbae5d9a33c509eda627a3dc61cbe5cf85155cc7c66160b09c5796 |
| 5.5+ | 573639815 | 702877 bytes | tUb3Xq7Lm9 | 35e68d91126d38759b907823dc283fb76b21fc841a5b5d7c432e41a7e1f8a87b |

Tizen 9.0에 대한 5.5용 선택은 upstream의 버전 안내에 따른 것이다. 이 검증은 Tizen 9.0의 설치 승인, Cobalt 실행, 재생 호환성을 입증하지 않는다.

## 참고 프로젝트 비교

- [TizenBrew 안내](https://github.com/reisxd/TizenBrew/blob/main/docs/README.md): TV에 설치하는 모듈 기반 환경과 Tizen 7 이상 Samsung 인증서 흐름을 참고했다.
- [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/blob/main/client/services/tizenbrew-installer-service/utils/PackageInstallation.js): SDB의 `shell:0 vd_appinstall <packageId> <path>` 경로가 현재 Kotlin 설치기와 일치한다. stream 종료만으로 성공 처리하는 방식은 가져오지 않고 명시적 설치 근거를 요구한다.
- [Tizen Homebrew](https://github.com/SushyDev/tizen-homebrew/blob/main/tools/installing.js): 고정 staging 경로와 profile/WGT 전송 순서를 대조했다. TV가 스스로 설치하기 위한 키 전달·127.0.0.1 전환은 휴대폰이 설치하는 현재 구조에는 적용하지 않는다.
- [tizen-youtube 안내](https://github.com/SushyDev/tizen-youtube/blob/main/docs/README.md): 5.5 이상용 선택, 자체 Partner 인증서를 통한 별도 설치 경로를 확인했다. WGT의 on-boot/auto-restart 서비스·권한·Cobalt metadata를 변경하지 않는다.

로컬 빌드 로그와 다운로드 파일은 ignored `build/`에 있고 Git에 포함하지 않는다. 테스트용 키와 서명된 모의 WGT는 배포 APK에 포함하지 않는다.
