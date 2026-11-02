# 음성 짐 메모와 도착 후 반납 안내 · 0.3.0

음성으로 짐을 메모하고 반납 장소 근처에서 확인 순서를 안내하는 차량·Flutter 0.3.0입니다. 차량 VIN·P 상태를 실제로 다시 읽고, 이용자가 확인한 장소·물건만 같은 예약의 메모로 저장합니다. 반납 점검을 저장하면 메모도 고정해 서버와 Flutter에 전달합니다.

도착 제안은 150m 안의 서로 다른 새 위치 2개(15초 이내)와 VIN/P 조회(8초 이내)를 요구합니다. ‘반납 준비 시작’을 선택하면 다시 확인하고 기존 반납 점검으로 이어집니다. 창문 → 저장한 짐 메모·점유 좌석 → 사진·직접 확인 순서를 유지하며 자동 저장·반납은 하지 않습니다.

Android Debug95/Release93, 서버45, Flutter43(선택2개 제외), 분석/Lint 오류0·기존 경고24. 읽기 전용 AVD와 별도 계정·DB에서 실제 SDK13조건을 통과했습니다. 한국어 생성 PCM을 공식 ON_DEVICE 입력 API로 보내 최종 인식을 확인했으며 호스트 마이크·실차 인식률 시험은 아닙니다. 이번 통합은 인수부터 반납 저장·동기화까지이고 사진·최종 접수 전체는 선행0.2.6 근거입니다.

## 짐 메모

차량의 ‘말로 짐 메모·직접 입력’을 엽니다. ‘말하기 시작’에서 실제 P/VIN을 다시 확인한 뒤 Pleos SpeechToText3.3.1 ON_DEVICE/ko-KR를 시작합니다. 인식한 장소·물건 후보를 이용자가 고치거나 선택하고 ‘확인하고 저장’을 누릅니다. 직접 입력도 같은 저장 검사를 거칩니다. 장소별 메모1개, 총5개, 물건 이름40자 이내입니다.

음성 서비스는 마이크 종료 이벤트 뒤 최종 텍스트를 보내기도 합니다. 종료만으로 성공 처리하거나 후보를 저장하지 않고 최종 결과를 기다립니다. 최종 결과 후에도 저장은 사용자 선택입니다. 20초 제한·취소·전경 이탈·계정/예약 변경·P 미확인 시 인식을 중단합니다. 음성 실패는 직접 입력으로 이어갈 수 있습니다. 원본 음성과 인식 원문은 서버·환경설정에 저장하지 않습니다.

메모의 수정·삭제는 문 열림 관측과 직접 공간 표시를 변경하지 않습니다. 저장 실패 시 이전 메모를 유지합니다. 반납 시 점유 좌석이 없어도 저장한 물건을 화면과 TTS로 다시 알려주고, 열린 창문 안내가 먼저 나오도록 했습니다. 메모는 물건 감지·분실 없음의 판정이 아닙니다.

## 도착 상황 안내

차량 앱이 열린 동안 예약의 반납 장소와 새 FusedLocation 위치를 사용합니다. 첫 근접 위치를 받은 뒤 VIN/P를 다시 조회해야 제안합니다. 가까운 위치를 반복 수신하고 실제 P/VIN을 확인하면 반납 준비를 화면·TTS로 제안합니다. 제안을 보여주는 동안에도 기록은 ACTIVE이며 반납 관측을 쓰지 않습니다. ‘나중에’를 누르면 같은 세션에서는 다시 제안하지 않습니다. 전경 이탈·기어/위치 미확인에서는 안내를 숨기며, 닫지 않은 제안은 새 위치와 P 확인 후 음성 반복 없이 다시 표시할 수 있습니다. 같은 Controller 세션·예약·목적지의 반복을 제어하고, 프로세스를 다시 시작하면 새 위치 확인 후 다시 제안할 수 있습니다.

수락할 때 위치·연결된 예약·P/VIN을 다시 확인합니다. 위치 권한 거부나 미수신 상태에서도 기존 수동 반납 버튼은 사용할 수 있습니다. GPS 근접과 P는 지정 주차구역의 증거가 아닙니다. 현재 차량 좌표는 서버로 보내지 않습니다. 기존 NaviHelper 경로 확인·명시적 목적지 승인도 유지합니다.

## 실제 SDK 시험

Pleos2.1.0 ARM64 AVD5556의 읽기 전용 실행본, Debug 테스트 VIN·모의 GPS·명시된 시험 장소, 독립 서버/계정/설정에서 검증했습니다. 원본 예약을 덮어쓰지 않았습니다.

| 확인한 항목 | 결과 |
|---|---|
| 정상 QR 소유자 승인·인수 | 통과 |
| 새 D 기어에서 음성 인식 시작 차단 | 통과 |
| 생성 한국어 PCM의 실제 Pleos 최종 인식·자동 저장 없음 | 통과 |
| 후보 수정·명시적 저장 | 통과 |
| 오디오/원문 비저장 | 통과 |
| 같은 예약으로 Controller 재생성 후 메모 복원 | 통과 |
| 취소 뒤 늦은 결과가 저장되지 않음 | 통과 |
| 가까운 위치라도 D에서는 제안·반납하지 않음 | 통과 |
| 새 위치/P 제안과 실제 TTS 시작·완료 | 통과 |
| 안내 닫기 중복 방지·전경 이탈/복귀 | 통과 |
| 명시적 반납 시작·열린 창문 우선 | 통과 |
| 비점유 좌석에서도 저장한 캐리어 메모 TTS 시작·완료 | 통과 |
| 반납 저장·서버 동기화·확인한 메모만 전달 | 통과 |

에뮬레이터의 내비 초기화가 화면을 가져오면 실제 차량 시험 화면으로 복귀한 뒤 검증했습니다. 마지막 시험은 13개 조건과 음성 후보·도착 제안·창문 우선·짐 메모 음성의 실제 팝업 4개를 함께 확인했습니다. 경로를 임의로 요청해 성공 상태를 만들지 않았습니다. 음성 실차 인식률·실차 위치 정확도·정상 VIN Release 전체 흐름·실폰 QR/촬영은 미검증입니다. 사진/직접 확인 누락 차단은 서버·Flutter 회귀 검사로 확인했고, 이번 실제 SDK 시험에서 사진·최종 접수 전체를 반복하지 않았습니다.

## 빌드와 APK

[기존 제출 Release](https://github.com/neolabs120-mobile/pleos-rental-check/releases/tag/v0.2.6-submission)에 최신 APK·소스·지문을 제공합니다. 이전 APK·영상과 태그를 유지하며 새 영상은 올리지 않았습니다.

| APK | 버전·크기 | SHA-256 |
|---|---|---|
| `rentcheck_vehicle_0.3.0_release.apk` | 0.3.0+14 · 43,566,421바이트 | `c1a791ccf0700334c05761827d06cfdba91fdb9abc02408524db0b0ef634b809` |
| `rentcheck_mobile_0.3.0_release.apk` | 0.3.0+14 · 70,621,227바이트 | `bcfe8f57fdc32ed81e19106604de666565be2afabe969e6ff2d8f91b761bbe9b` |

두 Release APK의 실제 설치·첫 실행·버전14·프로세스 실행·치명적 오류 없음도 읽기 전용 AVD에서 확인했습니다. 두 APK는 기존 Release 키로 서명하고 debuggable=false·16KB 정렬·차량 Release의 테스트 VIN/진단 러너 제외를 확인했습니다. 휴대폰 Release에는 별도 HTTPS 서버가 필요하며 실제 VIN 검증은 유지됩니다. 키·계정·사용자 데이터는 공개하지 않습니다.

```sh
./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease lintDebug
cd mobile
flutter pub get
flutter analyze
flutter test
flutter build apk --release
```

실제 SDK 시험 러너는 androidTest의 `VoiceArrivalFlowRunner`입니다. 시험 전용 APK를 만들 때 `-PrentalInstrumentationRunner=kr.taeuk.pleos.rentalcheck.VoiceArrivalFlowRunner`를 지정합니다. 독립 서버·계정·설정 prefix와 명시적 시험 목적지를 사용하고 원본 AVD를 지우거나 Release로 교체하지 않습니다.

공식 문서: [음성 인식](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro), [음성 결과 이벤트](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [PCM 입력](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data), [FusedLocation](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro).
