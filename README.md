# 렌트체크

처음 빌린 차를 반납할 때는 차량 상태도 확인하고 짐도 챙겨야 합니다. 렌트체크는 이 과정을 차량 화면과 휴대폰에서 이어서 진행하는 카셰어링 이용자용 앱입니다.

차량에서는 창문 상태와 좌석 점유 신호를 확인하고, 이용자가 남긴 짐 메모를 다시 보여줍니다. 휴대폰에서는 사진과 직접 확인 내역을 저장하고 점검 접수를 마칩니다.

## 이용 흐름

1. **차량 연결과 인수** — 예약에 사용한 이메일로 로그인합니다. 차량 QR을 확인하고 연결을 승인한 뒤 인수 상태를 저장합니다. 카메라가 없으면 QR의 전체 링크를 입력할 수 있습니다.
2. **짐 메모** — P 상태에서 “트렁크에 캐리어를 뒀어”처럼 말합니다. 인식된 장소와 물건을 확인하거나 수정한 뒤 저장합니다. 직접 입력도 가능합니다.
3. **반납 장소로 이동** — 예약에 등록된 장소를 확인하고 Pleos 내비로 길 안내를 받습니다. 주차장 입구·층·구역 안내도 확인합니다. 배터리는 인수 때 잔량을 기준으로 부족분과 등록된 충전 경유지를 안내합니다. 장소 근처에서 새 위치와 P 상태가 확인되면 반납 준비를 제안합니다.
4. **차량에서 반납 점검** — 열린 창문을 먼저 확인하고, 저장한 짐 메모와 점유 좌석을 화면·음성으로 안내합니다. 물건별 ‘챙겼어요’를 표시하고 반납 점검을 저장합니다.
5. **휴대폰에서 마무리** — 창문·소지품·외관 사진과 직접 확인 내역을 저장한 뒤 제출합니다. 차량에서 못 마친 물건 확인도 이어갑니다. 사진 구도와 인수·반납 비교를 보고, 전송 전 내용은 기기에 임시 저장할 수 있습니다. 서버가 접수한 뒤 번호가 표시됩니다.

좌석 신호는 사람과 짐을 구분하지 못합니다. 비점유 좌석과 메모하지 않은 공간도 직접 살펴봐야 합니다. 문 열림 관측, 직접 표시한 공간, 물건 메모는 서로 구분해 보관합니다.

## APK와 시연 영상

현재 차량 앱과 Flutter 앱은 **0.4.3+18**입니다. [제출 자료 Release](https://github.com/neolabs120-mobile/pleos-rental-check/releases/tag/v0.2.6-submission)에서 APK와 소스를 받을 수 있습니다.

| 파일 | 용도 |
|---|---|
| [차량용 APK](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_vehicle_0.4.3_release.apk) | Kotlin·Compose 앱. Android 9 이상과 Pleos Connect 환경이 필요합니다. |
| [휴대폰용 APK](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_mobile_0.4.3_release.apk) | Flutter 앱. Android 7 이상에서 사용하며, 설정에 실행할 HTTPS 서버 주소를 입력합니다. |
| [소스 코드 ZIP](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_source_0.4.3.zip) | 0.4.3 소스입니다. 저장소의 main에서도 확인할 수 있습니다. |
| [파일 지문](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/SHA256SUMS_0.4.3.txt) | 내려받은 APK·소스·안내문의 SHA-256을 확인할 때 사용합니다. |

두 APK는 기존 배포 키로 서명했습니다. Release 태그는 제출한 링크를 유지하기 위해 `v0.2.6-submission`을 사용합니다. GitHub가 자동으로 제공하는 **Source code ZIP은 당시 0.2.6 소스**이므로, 최신 버전은 위 ZIP이나 main을 이용해 주세요. 이전 APK와 0.2.6 영상도 같은 Release에 남겨두었습니다.

[![렌트체크 대여·반납 시나리오 영상](docs/demo-cover-0.4.3.jpg)](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_scenario_demo_0.4.3.mp4)

**[대여·반납 시나리오 영상 보기 (0.4.3 · 2분 8초)](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_scenario_demo_0.4.3.mp4)** — 카셰어링을 처음 이용하는 가상 인물 “지수 씨”가 가입·예약 확인, 차량 연결, 인수, 반납 점검(끄고 내리기·소지품 안내), 사진·직접 확인, 최종 접수까지 진행하는 과정을 13장면으로 담았습니다.

영상은 실제 렌트체크 앱 화면 캡처에 한국어 자막과 컴퓨터 합성 음성 내레이션을 더해 만들었습니다. 차량 쪽 기록은 시연용 합성 데이터이고 차량 화면은 에뮬레이터의 표시 검증 화면이며, 실차 검증 결과가 아닙니다. 휴대폰 화면은 [대여·반납 시나리오 시험](docs/FLUTTER_SCENARIO_0.4.3.md)에서 캡처했습니다.

이전 영상: [3분 시연 (0.2.6)](https://github.com/neolabs120-mobile/pleos-rental-check/releases/download/v0.2.6-submission/rentcheck_demo_0.2.6_3min.mp4) — 실제 Pleos 에뮬레이터의 창문·좌석·음성 안내와 Flutter 웹의 사진 등록·최종 접수 과정을 녹화했습니다.

## Pleos 연동과 프로젝트 구성

차량 SDK는 Kotlin 앱에서 호출하고, Flutter 앱은 서버를 통해 예약과 점검 기록을 받습니다.

| SDK | 사용하는 곳 |
|---|---|
| Vehicle 2.3.2 | VIN·기어·창문·문·좌석·배터리·주행거리 등 차량 상태 조회, 인수·반납 비교, 창문 조작 연습, 반납 전 조명·방향지시등·와이퍼·열선 확인 |
| SpeechToText 3.3.1 | 이용자가 시작한 한국어 음성 짐 메모 입력. `ON_DEVICE` 모드 사용 |
| TextToSpeech 3.3.1 | 도착 후 반납 준비 제안, 켜져 있는 항목·짐 메모·점유 좌석의 한국어 안내. `ON_DEVICE` 모드 사용 |
| FusedLocation 1.1.3 | 차량의 새 위치와 반납 장소 사이 직선거리, 근처인지 확인하는 참고 정보 |
| NaviHelper 3.0.5 | 사용자가 승인한 반납 목적지로 길 안내, 충전 경유 요청, 실제 목적지·경유지·안내 상태 확인 |

좌석 점유는 SDK 2.3.2의 Boolean 변환에서 정보가 사라지는 문제 때문에 같은 Vehicle Service의 `vehicle-aidl 1.1.1` 원시값을 함께 읽습니다. [API 활용 현황](docs/PLEOS_API_USAGE_2026-11-01.md)에 실제 수신 여부와 제한 사항을 정리했습니다.

```text
app/       Kotlin·Compose 차량 앱
mobile/    Flutter 휴대폰 앱과 웹
server/    FastAPI 서버: 계정, 예약, 사진, 점검 기록
scripts/   에뮬레이터 통합 시험 도구
docs/      기능별 구현·검증 기록
```

## 직접 실행하기

Android Studio, JDK 17, Android SDK 35, Flutter 3.38 이상, Python 환경을 준비합니다. SDK·서명 키·실제 계정 데이터는 저장소에 포함하지 않습니다.

차량 앱은 저장소 루트를 Android Studio로 열거나 다음 명령으로 빌드합니다. 본인의 Debug 키를 사용하며, Release 서명 방법은 [배포 안내](docs/RELEASE_SUBMISSION_0.2.6.md)를 참고하세요.

```sh
./gradlew assembleDebug
```

아래는 **새 로컬 시험 환경**을 만드는 예시입니다. 저장소 루트에서 서버 환경과 시험용 키를 준비합니다.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements-lock.txt
export RENTAL_DEVICE_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
export RENTAL_MAIL_MODE=file
export PUBLIC_BASE_URL=http://127.0.0.1:8788
```

Flutter 웹은 `mobile/`에서 빌드합니다.

```sh
cd mobile
flutter pub get
flutter build web --debug --no-web-resources-cdn --base-href /mobile/ --dart-define=RENTAL_SERVER_URL=http://127.0.0.1:8788
```

저장소 루트로 돌아와 같은 셸에서 서버를 실행합니다.

```sh
cd ..
python server/main.py --port 8788 --data-dir server/data --allow-emulator-test-vin --flutter-web-dir mobile/build/web
```

웹 주소는 `http://127.0.0.1:8788/mobile/`입니다. 사용할 에뮬레이터에만 포트 연결을 설정하고, 차량 앱에도 같은 서버 주소와 시험용 운영자 키를 입력합니다.

```sh
adb -s <Pleos_AVD_시리얼> reverse tcp:8788 tcp:8788
```

파일 메일 모드에서는 확인번호가 `server/data/mail-outbox/`에 생성됩니다. 웹에서 가입·이메일 확인을 마친 뒤 같은 이메일로 예약을 발급합니다.

```sh
python server/issue_booking.py --base-url http://127.0.0.1:8788 --email 본인_시험계정 --vin TEST0000000000001 --vehicle-name 체험차량 --demo
```

주차장 단계·충전 경유지 등록은 [반납 마무리 문서](docs/RETURN_COMPLETION_0.4.0.md#운영자-안내-등록)를 참고하세요. 기본 반납 장소를 포함한 예약 발급 방법은 [반납 장소 안내 문서](docs/RETURN_LOCATION_0.2.8.md#시험-예약-발급)에 있습니다. 현재 ARM64 에뮬레이터의 정상 VIN 미수신 문제로, SDK 연동 시험에서는 알려진 Pleos AVD의 Debug 빌드에서만 명시적으로 테스트 VIN을 켭니다. Release의 실제 VIN 검증은 유지합니다.

휴대폰 Release에는 별도로 준비한 HTTPS 서버가 필요합니다. 기본값 `https://rental.invalid`는 예시 주소이며, 공개 운영 서버를 제공하지 않습니다. 기존 Debug 앱과 Release 앱은 서명이 다르므로 데이터가 있는 Debug 앱을 지우며 교체하지 말고 분리된 기기나 시험 환경을 사용해 주세요.

## 검증 범위

휴대폰 0.4.3+18은 가상 대여·반납 시나리오 시험에서 찾은 표시 문제를 고쳤습니다. 반납 전 주행 거리는 “반납 후 계산”으로, 전기차는 연료 대신 인수·반납 배터리를 보여 줍니다. 안내 문구를 카셰어링 업체 기준으로 맞추고, 합성 시연 데이터 접수는 제출 결과에도 표시합니다. 차량 앱은 버전만 맞췄습니다.

Flutter 56개(선택 2개 제외)·분석 문제 0, Android Debug 108개·Release 106개·Lint 오류 0. 분리 시험 서버에서 가입부터 최종 접수까지 21단계 시나리오를 실제 Flutter 화면으로 진행해 통과했습니다.

[대여·반납 시나리오 기록](docs/FLUTTER_SCENARIO_0.4.3.md)

차량 0.4.2+17은 반납 전 **끄고 내리기 점검**을 추가했습니다. 반납을 시작·저장할 때 전조등·상향등·미등·실내등·독서등, 방향지시등, 와이퍼, 유리 열선, 핸들·사이드미러 열선을 Pleos Vehicle SDK로 새로 읽고, 켜져 있으면 창문 확인 다음에 화면과 Pleos 음성으로 알려 줍니다. 이용자는 끈 뒤 다시 확인하거나 켜진 채로 진행할 수 있으며, 앱은 차량을 조작하지 않습니다.

Android Debug 108개·Release 106개, Flutter 53개(선택 2개 제외), 분석 문제·Lint 오류 0. 빈 데이터 Pleos AVD에서 Virtual Console로 켠 8가지 신호를 실제 SDK로 읽어 12개 항목을 감지하고, 끈 뒤 0개로 돌아오는 것을 확인했습니다. 휴대폰 앱은 버전만 맞췄습니다.

[끄고 내리기 점검 기록](docs/SWITCH_OFF_0.4.2.md)

차량·Flutter 0.4.1+16은 기능과 판정은 그대로 두고 화면을 다듬었습니다. 차량 앱은 진행 단계 표시, 카드·제목, 배터리 기준(큰 숫자·부족 배지·기준 막대), 상태 아이콘, 소지품 확인 팝업 배치를 정리했습니다. 휴대폰 앱은 차량과 같은 청록 테마, 시작 화면 카드, 배터리 안내, 제출 결과 화면을 다듬었습니다.

Android Debug 100개·Release 98개, Flutter 53개(선택 2개 제외), 분석 문제·Lint 오류 0. 화면은 표시용 예시 단계와 위젯 테스트 렌더로 검토했으며 실제 SDK·서버 흐름은 0.4.0 검증을 이어갑니다.

[화면 개선 기록](docs/UI_POLISH_0.4.1.md)

차량·Flutter 0.4.0+15는 반납 마무리를 보완했습니다. 물건별 ‘챙겼어요’를 차량에서 확인하고 휴대폰으로 이어가며, 운영자가 등록한 주차장 입구·층·구역·주차면 안내를 봅니다. 사진 구도 가이드와 같은 항목의 인수·반납 비교, 계정·서버·기록별 암호화 임시 저장을 제공합니다. 배터리는 인수 때 실제 수신한 퍼센티지를 기준으로 부족분을 안내합니다.

Android Debug 100개·Release 98개, 서버 51개, Flutter 53개(선택 2개 제외), 분석 문제·Lint 오류0/기존 경고24. 읽기 전용 Pleos AVD의 새 반납 통합 11조건과 실제 웹 PNG/JPEG/WebP 선택·재시작 복구·직접 확인·최종 접수를 확인했습니다. 시험 배터리는 인수80%→반납72%, 기준80%·부족8%p였습니다.

충전 경유 요청은 실제 내비 경로에 전달되지만 해당 AVD의 경유지 조회 좌표는0,0입니다. 최종 목적지는 맞아도 충전 경유지는 앱에서 검증 완료로 표시하지 않고 내비에서 직접 확인하도록 안내합니다. 실차·정상 VIN Release 전체 흐름·Fold8 실물 촬영과 실제 단말 통신 단절은 미검증입니다. 새 영상은 올리지 않습니다.

음성 인식·도착 제안은0.3.0의 실제13조건 검증을 이어갑니다. 이번 웹 시험에서는 전송 전 사진·체크의 페이지 재시작 복원과 최종 접수를 확인했고, 오프라인/중단 복구는 자동 검사로 확인했습니다.

가까운 위치나 P 상태만으로 지정 주차구역과 반납 완료를 판단하지 않습니다. 음성 원본과 현재 차량 좌표는 서버에 보관하지 않으며, 이용자가 확인해 저장한 물건 메모만 반납 기록에 포함합니다. 앱의 점검 접수는 실제 사업자의 반납 처리·요금 정산과 별도입니다.

[0.4.0 반납 마무리](docs/RETURN_COMPLETION_0.4.0.md) · [0.3.0 구현·시험 기록](docs/VOICE_ARRIVAL_0.3.0.md) · [전체 검증 이력](docs/VALIDATION.md) · [차량 UI 0.2.9](docs/NATIVE_UI_0.2.9.md) · [반납 장소 0.2.8](docs/RETURN_LOCATION_0.2.8.md)
