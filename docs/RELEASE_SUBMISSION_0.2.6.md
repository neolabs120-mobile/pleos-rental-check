# 0.2.6 제출 영상·Release APK · 2026-11-01

https://github.com/neolabs120-mobile/pleos-rental-check/releases/tag/v0.2.6-submission

## 배포물

| 파일 | 용도 | 확인한 범위 |
| --- | --- | --- |
| rentcheck_vehicle_0.2.6_release.apk | Kotlin/Compose 차량 앱 | Release 80개·Lint 오류 0·배포 서명·실제 Pleos AVD 설치·시작 |
| rentcheck_mobile_0.2.6_release.apk | Flutter Android 휴대폰 앱 | Flutter 35개 통과/선택 통합 2개 제외·분석 문제 0·Release 빌드·배포 서명·AVD 설치·시작 |
| rentcheck_demo_0.2.6_3min.mp4 | 3분 자막형 시연 | 1920×1080/30fps/H.264/AAC·전체 디코딩 검사·실제 Pleos 음성 포함 |

두 APK의 versionName은 0.2.6, versionCode는 10이며 debuggable=false, cleartext HTTP 허용 없음입니다. Release 인증서 SHA-256은 `35d839af51ea4d192240a18140bd4c0b911fa04682fe644ad5802896b9825fc8`입니다. 비공개 서명 키와 비밀번호는 저장소와 릴리스에 포함하지 않습니다. 이전 Debug 키와 APK는 유지했습니다.

Release 설치 시험은 원본 AVD를 종료한 뒤 같은 Pleos AVD를 `-read-only -port 5556`으로 열어 임시 실행본에서만 수행했습니다. 원본에 설치된 Debug 앱과 설정 39개 파일은 전후 바이트가 같습니다. 일반 Android 실폰에서의 전체 사진/QR 시험은 포함하지 않았습니다.

## 시연 순서

- 0:00–0:15: 카셰어링 초보자와 반납 전 확인 소개.
- 0:15–0:45: 로그인된 체험 계정의 예약 자동 표시, 차량 QR 링크·명시적 승인, 실제 인수 저장.
- 0:45–1:00: 실제 이용 중 화면·뒤 오른쪽 좌석 점유.
- 1:00–1:45: 뒤 오른쪽 창문 30% 안내, 0%로 닫고 재조회, 실제 한국어 Pleos TTS 전체 안내, 다시 듣기·중지.
- 1:45–2:45: 반납 저장, 웹 사진 3종 선택·미리보기·전송, 직접 확인 3종·시험 메모 저장, 최종 확인·접수.
- 2:45–3:00: 차량과 웹의 같은 접수 번호.

영상은 실제 에뮬레이터 화면 녹화와 실제 웹 화면 캡처를 확대·편집한 자막형 영상입니다. 차량 조작은 androidTest에서 실제 MainActivity의 ViewModel 이벤트를 실행했고 웹은 실제 브라우저 UI로 조작했습니다. 카메라 QR 촬영을 완료한 것으로 표현하지 않습니다. 이전 WAV나 합성 음성이 아닌 이번 AVD의 인증된 streamAudio 출력으로 실제 Pleos 안내를 녹음했습니다. 호스트 마이크는 사용하지 않았습니다. 출력 스트림이 비활성 믹서의 공백을 생략하므로 첫 완결 발화만 화면의 실제 재생 시작에 맞춰 편집했습니다. 앱 처리 시간이나 실차 검증을 의미하지 않습니다.

새 체험 예약의 사진 3장·직접 확인 3종과 최종 번호 일치를 확인했습니다. 계정·예약·점검·사진 등 기존 행은 모두 보존했습니다. 정상 VIN 미수신, 사람과 짐 구분 불가, 실제 업체 반납·정산 별도라는 한계를 유지합니다.

## 실행 조건

차량 Release에는 테스트 VIN 상수와 대체 구현, 시연 드라이버가 포함되지 않습니다. 정상 VIN을 제공하는 Pleos 환경 및 HTTPS 서버 연결이 필요합니다. 현재 ARM64 AVD의 invalid VIN에서는 QR 진행이 차단되는 것이 기존 검증 정책입니다. AVD에서 전체 과정을 체험하려면 별도로 보존한 Debug 체험 APK와 서버 opt-in·테스트 예약을 사용합니다.

휴대폰 Release도 HTTPS 서버를 요구합니다. 설정에서 서버 주소를 지정한 후 예약 이메일로 로그인합니다. 기본 `https://rental.invalid`는 실제 운영 서버가 아닙니다. 운영자 키, 이메일 발송 설정, 데이터베이스는 각자의 서버에서 별도로 준비합니다.

## 다시 빌드하기

JDK 17, Android SDK, Flutter와 각 의존성을 준비합니다. 활성 앱 소스는 최초 공개 커밋 44017cf와 같고 이번 추가는 문서와 androidTest 촬영 드라이버입니다.

```sh
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
cd mobile
flutter pub get
flutter analyze
flutter test
flutter build apk --release --build-name=0.2.6 --build-number=10
```

각 unsigned APK를 본인이 관리하는 별도 배포 키로 서명합니다. 키와 비밀번호를 소스나 명령 기록에 입력하지 않습니다. Android build-tools의 zipalign을 먼저 실행하고 apksigner로 서명·검증합니다. 서명 후 APK 바이트를 다시 수정하지 않습니다.

```sh
zipalign -P 16 -f 4 INPUT.apk aligned.apk
apksigner sign --ks "$RENTAL_RELEASE_KEYSTORE" --ks-key-alias "$RENTAL_RELEASE_ALIAS" --ks-pass env:RENTAL_RELEASE_PASSWORD --key-pass env:RENTAL_RELEASE_PASSWORD --out OUTPUT_release.apk aligned.apk
apksigner verify --verbose --print-certs OUTPUT_release.apk
```
