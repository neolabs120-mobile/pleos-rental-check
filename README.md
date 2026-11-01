# 렌트체크

처음 카셰어링을 이용하는 사람의 계정 연결·인수·이용·반납 점검을 돕습니다. 차량 신호를 좌석 위치 안내와 한국어 음성으로 연결하고 사진·직접 확인·접수 기록을 보관합니다.

## 구성

- app: Kotlin/Compose 차량 앱 0.2.6. Pleos Vehicle 2.3.2, vehicle-aidl 1.1.1, TextToSpeech 3.3.1.
- mobile: Flutter 이용자 앱과 웹. 계정, 예약, QR 승인, 사진, 직접 확인, 접수.
- server: FastAPI 서버와 사진·계정 처리. requirements-lock.txt 포함.
- scripts, app/src/test, app/src/androidTest, mobile/test, server/test_*.py: 단위·통합 검증.

차량 SDK는 Kotlin 앱에서 호출합니다. Flutter가 차량 SDK를 직접 호출하지 않습니다. 좌석 원시값 1은 비점유, 2는 점유이며 알 수 없는 값은 확인 불가입니다. 사람과 짐은 구분하지 못합니다.

## APK

Releases의 v0.2.6에서 검증된 차량 Debug 체험 APK를 받습니다. 일반 Android 휴대폰만으로 Pleos 차량 SDK 전체를 시연할 수는 없습니다. 공식 Pleos 전용 AVD가 필요합니다. Flutter 휴대폰 APK는 이 릴리스에 포함하지 않으며 웹으로 시험했습니다.

실제 AVD의 정상 SDK VIN 미수신 문제는 남아 있습니다. 알려진 Pleos ARM64 AVD의 Debug에서 사용자가 스위치를 켜고, 서버 opt-in과 테스트 예약을 함께 사용한 경우만 TEST0000000000001을 허용합니다. 다른 차량 신호는 실제 SDK에서 읽습니다. Release와 실제 차량 VIN 검증은 유지합니다.

## 새 환경 실행

Android Studio, JDK 17 이상, Android SDK 35, Flutter 3.38 이상과 Python을 준비합니다. Gradle·Flutter·Python 의존성은 각 저장소에서 받습니다. 서명 키·SDK·캐시·실제 데이터는 배포하지 않습니다.

차량 앱은 저장소 루트를 Android Studio로 열거나 ./gradlew assembleDebug로 빌드합니다. 기본 Debug 서명 키 또는 본인 키를 사용합니다. 동봉 APK의 기존 개인 서명 키는 포함하지 않았습니다. Android 단위/빌드 검사는 ./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease lintDebug입니다.

아래는 기존 서버와 분리한 로컬 시험 포트 8788 예시입니다. 저장소 루트에서 Python 가상환경을 만들고 server/requirements-lock.txt를 설치합니다.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements-lock.txt
export RENTAL_DEVICE_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
export RENTAL_MAIL_MODE=file
export PUBLIC_BASE_URL=http://127.0.0.1:8788
```

mobile에서 flutter pub get 후 웹을 만듭니다.

```sh
flutter build web --debug --no-web-resources-cdn --base-href /mobile/ --dart-define=RENTAL_SERVER_URL=http://127.0.0.1:8788
```

서버는 저장소 루트에서 다음과 같이 실행합니다.

```sh
python server/main.py --port 8788 --data-dir server/data --allow-emulator-test-vin --flutter-web-dir mobile/build/web
```

웹은 http://127.0.0.1:8788/mobile/입니다. Android adb reverse tcp:8788 tcp:8788에 해당 Pleos AVD 시리얼을 명시하고 차량 설정에도 같은 서버 주소와 새로 생성한 운영자 키를 입력합니다. 실제 운영자 키와 계정 비밀번호는 저장소에 넣지 않습니다.

파일 메일 모드는 로컬 시험용입니다. 가입 확인번호는 새 server/data/mail-outbox에 생성되며 외부로 메일을 보내지 않습니다. 계정을 만들고 이메일을 확인한 뒤 같은 셸의 운영자 키로 테스트 예약을 발급합니다.

```sh
python server/issue_booking.py --base-url http://127.0.0.1:8788 --email 본인_시험계정 --vin TEST0000000000001 --vehicle-name 체험차량 --demo
```

로그인 → 자동 예약 표시 → 차량 QR 생성·휴대폰 명시 승인 → 인수 저장 → 이용 중 → 반납 시 창문/좌석 새 조회 → 소지품 화면·음성 안내 → 반납 저장 → 사진 3종·직접 확인·메모 → 최종 접수 순서입니다. 카메라가 없으면 차량 QR의 전체 링크를 웹에 입력합니다. 알려진 열린 창문은 직접 확인으로 넘길 수 없습니다. 좌석 안내는 확인 후 닫고 계속 진행할 수 있습니다.

## 검증

0.2.6 Android Debug 82/Release 80, 빌드와 Lint 오류 0. 밝은 화면·음성 팝업·연결·620dp/큰 글자·야간 5조건을 확인했습니다. 최신 실제 체험에서 정상 계정 연결·인수·반납 저장 후 Flutter 웹 사진 3장·직접 확인 3종·최종 접수와 차량/웹/서버 번호 일치를 확인했습니다. TTS 10조건과 출력 오디오는 선행 0.2.5 실제 AVD 검증 근거입니다. 자세한 범위는 docs/VALIDATION.md를 따릅니다.

실차·정상 VIN 전체 통합·실폰 QR/촬영·iOS·제출용 Release 실행 경로는 추가 검증 대상입니다. 앱의 점검 접수는 실제 사업자의 반납·요금 정산과 별도입니다. 자동 손상·책임 판정과 원격 차량 제어는 제공하지 않습니다.

자동 통합 스크립트는 경로 인자를 지정해 사용합니다. macOS 기본 adb 경로는 현재 사용자 홈을 기준으로 하고 Flutter는 PATH에서 찾습니다. 본체 소스는 그대로이며 호스트 시험 스크립트의 도구 기본 경로만 배포 환경에 맞췄습니다.
