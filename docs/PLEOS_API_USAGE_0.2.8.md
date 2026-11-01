# Pleos API 활용 현황 · 2026-11-02 · 차량 앱 0.2.8

현재 평가는 **반납·소지품 시나리오의 차량 데이터 활용은 폭넓지만, Pleos 전체 SDK를 최대한 활용한 상태는 아니다**. 핵심 신호를 표시하는 데서 그치지 않고 창문 연습·열림 저장 차단·공간 메모·점유 좌석 안내에 연결했다. 0.2.5에는 Pleos TextToSpeech 한국어 ON_DEVICE 소지품 안내를 추가해 실제 AVD 재생과 출력 PCM을 확인했다. 0.2.8에는 FusedLocation·NaviHelper의 반납 장소 안내를 추가하고 실제 AVD 11조건을 통과했다. 차량 쓰기/원격 제어는 사용하지 않는다. [새 구현·실제 시험](RETURN_LOCATION_0.2.8.md)

## 수치의 범위

| 기준 | 현재 구현 |
|---|---|
| 연결한 SDK 계열 | 4계열: Vehicle 2.3.2 + TextToSpeech 3.3.1 + FusedLocation 1.1.3 + NaviHelper 3.0.5 |
| TTS 출력 | ON_DEVICE/ko-KR, TTS_SERVICE 일반 권한; 실제 onStart/onDone·비무음 PCM 확인 |
| Vehicle SDK 내부 접근 인터페이스 | 10개: CarInfo, Gear, Fuel, EvBattery, Odometer, Door, Window, Seat, Tire, Safety |
| 조회 신호 종류 | 11종: 문 위치와 문 잠금을 별도 집계 |
| 선행 0.2.5 AVD 스냅샷에서 수신된 신호 종류 | 위 11종 중 8종 |
| 선행 0.2.5 AVD에서 제한된 신호 종류 | VIN 미수신, 연료 미지원 오류, 경고등 권한 거부의 3종 |
| 변화 구독 | 문 위치·창문 위치·좌석 점유의 3종, 전경 수명주기에서만 |
| 선언한 Pleos 읽기 권한 | CAR_INFO/ENERGY/DRIVE/DOORS/WINDOWS/SEATS/SAFETY의 7종 |

이 수치는 우리 앱에 연결한 종류와 이번 실제 기록의 수신 여부다. Pleos 전체 API 활용률이 아니다. 스냅샷 48개 행에는 문/창문 닫힘의 파생값, 좌석의 SDK Boolean·원시값·정상화 값, 테스트 VIN이 함께 있으므로 행 개수를 센서 개수나 전체 API 사용률로 제시하면 안 된다. getGear는 고정 SDK 2.3.2의 구형 인터페이스를 사용하므로 최신 공식 도메인 목록과 단순 분모 비교도 하지 않는다.

## 연결한 기능과 실제 검증

| API 인터페이스·신호 | 앱에서 사용하는 곳 | 현재 확인한 상태 |
|---|---|---|
| Window · 창문 위치 | 반납 시작·저장 재조회, 열린 창문 위치 안내와 저장 차단, 90초 닫힘→열림→닫힘 연습 | 실제 AVD 조회·변화 콜백·열림/닫힘 판정·전체 반납 흐름 통과 |
| Door · 문/트렁크/후드 위치 | 인수·반납 점검, 전경에서 열림을 관측한 공간 기억 | 실제 조회, 뒷문·트렁크 변화 콜백과 메모 유지·반납 고정 통과 |
| Door · 잠금 | 점검 기록·상태 비교 | 이번 스냅샷 지원 2개 영역 수신; 잠금/해제 전환 전체를 이번에 시험한 것은 아님 |
| Seat · 점유 | 5개 지원 좌석 표시, 반납 때 점유 좌석 위치와 소지품 재확인 안내 | 실제 원시 정수 판정·콜백·반납 안내 통과. Console 제어 4곳의 변화 검증, 뒤 가운데는 조회 확인 |
| Gear · 기어 | 창문 연습과 길 안내 요청 직전 P 확인 | 실제 P 수신 및 전체 시험의 D/P 수신. 실제 연습 중 D/R 이탈·90초 대기는 별도 미검증 |
| EvBattery · SOC | 인수/반납 충전율 및 증감 비교 | 실제 100% 수신, 기존 전체 시험의 SOC 변경·복원 확인 |
| Odometer · 누적거리 | 인수/반납 주행거리 차이 | 실제 km 수신, 기존 전체 시험의 +3km 차이 확인. 이번 예약은 0km 차이 |
| Tire · 저압 기준 | 차량이 제공한 저압 판단 기준값을 기록 | 이번 스냅샷 4바퀴 기준 수신. 현재 측정 공기압이 아니며 실제 저압 판단 기능으로 쓰지 않음 |
| CarInfo · VIN | 같은 차량 비교, 예약/QR 차량 일치 검증 | 실제 SDK VIN은 UNAVAILABLE. 알려진 Debug AVD의 명시적 테스트 VIN만 대체; 실차/Release 검증 유지 |
| Fuel · 연료 비율 | 인수/반납 잔량·증감 | getter 연결됨. 현재 EV AVD에서 ERROR_NOT_SUPPORTED_PROPERTY; 0/정상으로 대체하지 않음 |
| Safety · Telltales | 지정 경고 표시와 TPMS, 새 경고 비교 | getter 연결됐으나 현재 서비스가 권한 거부. 수신 성공이나 ‘경고 없음’으로 설명하지 않음 |

‘8종 수신’은 Gear, EvBattery, Odometer, Door 위치, Door 잠금, Window 위치, Seat 점유, Tire 저압 기준을 뜻한다. Door 한 객체에서 두 신호를 읽으므로 인터페이스 개수와 신호 종류 수는 다르다.

## 단순 조회를 넘어 활용하는 부분

- 지원 여부·영역·범위 조회로 실제 반환 영역을 따라가고, 미지원·오류·시간 초과를 항목별로 보존한다. 기어·경고 getter는 고정 SDK의 공개 API/권한 불일치에 맞춘 예외 경로를 사용한다.
- 창문은 WINDOW_POS 0 기반 계약에서만 닫힘을 판정한다. 열린 값을 직접 확인 체크로 덮어쓰지 못한다.
- 문/트렁크 열림 관측과 이용자의 짐 위치 직접 표시는 분리한다. 열림만으로 물건 존재를 추정하지 않는다.
- 좌석은 SDK 2.3.2의 손실성 Boolean 변환을 그대로 뒤집지 않는다. 같은 Vehicle Service의 읽기 전용 AIDL 1.1.1 원시 정수와 정상화 값을 분리해 안내에 사용한다. 고수준 SDK만으로 처리했다고 제출 자료에 쓰지 않는다.
- 인수/반납 값은 시간·VIN·단위·누적거리 감소를 검사한 뒤 비교하고, 전경 구독 해제·늦은 콜백 차단·반납 메모 고정을 구현했다.

## 연결한 SDK와 남은 후보

공식 Pleos Connect 문서는 Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation의 다섯 SDK 계열을 소개한다. 이 프로젝트는 Vehicle·TextToSpeech·FusedLocation·NaviHelper의 4계열을 연결했다. SpeechToText는 미사용이다. 이 계열 수를 전체 API 활용률로 환산하지 않는다. [공식 SDK 구성](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/), [Vehicle SDK 구조와 도메인](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro).

| SDK/기능 | 앱의 활용·후보 | 상태 |
|---|---|---|
| TextToSpeech | 점유 좌석 위치의 소지품 음성 안내·다시 듣기·중지 | 0.2.5 구현. 실제 AVD 10개 조건·출력 PCM 통과. [근거](VALIDATION.md) |
| FusedLocation + NaviHelper | 예약별 반납 장소·새 차량 위치 직선거리·명시적 길 안내·실제 내비 목적지 확인 | 0.2.8 구현. 시험 장소·모의 GPS의 실제 SDK 수신, 권한/전경/반납 저장 등 11조건 통과. 실차 정확도·실제 주차구역은 미검증 |
| SpeechToText | 주차 중 음성 메모나 확인 의사 입력 | 미구현. 오인식·명시적 확인 흐름을 먼저 설계해야 함 |
| Vehicle의 나머지 도메인·set API | 공조·조명·시트 조절 등 차량 조작 | 현재 미사용. 사용자의 실제 문제와 맞는 범위/권한을 확인한 뒤 검토할 영역 |

공식 TTS는 ON_DEVICE 모드와 서버를 사용하는 모드를 제공한다. 선행 0.2.5는 로컬 한국어 음성으로 점유 좌석을 읽으며, 같은 좌석 중복 방지·창문 우선·전경 취소를 적용했다. [공식 TTS 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro). 위치·내비의 공식 문서는 [FusedLocation](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [NaviHelper](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)다. 실제 연결 결과는 0.2.8 기록을 따른다. 현재 좌표는 서버에 보내지 않으며 자동 도착·반납으로 판정하지 않는다.

## 구분해야 하는 한계

계정·예약·QR 승인·사진·접수는 자체 서버/Flutter 기능이다. 차량 앱이 Pleos Vehicle SDK로 읽은 기록을 이 흐름에 전달한다. Flutter가 Vehicle SDK를 직접 호출하거나 Pleos 클라우드 Vehicle Data API/OAuth에 연결하는 구조가 아니다.

좌석 신호는 사람과 짐을 구분하지 못한다. 비점유도 소지품 없음의 증명이 아니며 사진·직접 확인을 계속 요구한다. 문·좌석 관측은 전경에 한정되고 휴대폰 최종 제출 버튼은 차량 SDK를 다시 조회하지 않는다. 실차·정상 VIN·실폰 카메라는 미검증이다. SDK와 같은 서비스에 대한 좌석 AIDL 호환 어댑터의 장기 호환성과 앱 심사 허용 범위도 별도 확인할 항목이다.

