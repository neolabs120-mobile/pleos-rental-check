# 반납 장소 안내 · 차량·Flutter 0.2.8 · 2026-11-02

예약 운영자가 등록한 반납 장소를 차량과 Flutter에 표시합니다. 차량 앱은 **Pleos FusedLocation 1.1.3**의 실제 위치를 받아 직선거리 참고값을 보여주고, 이용자가 목적지를 확인하면 **NaviHelper 3.0.5**로 길 안내를 요청합니다. 장소가 없는 기존 예약은 그대로 이용합니다. 임의의 반납 지점을 기본값으로 넣지 않습니다.

## 이용 흐름

1. 운영자가 예약 발급 때 장소명·주소·위도·경도·안내문을 지정합니다. 시험 장소는 `isTest=true`로 표시하며 `isDemo` 예약에만 허용합니다.
2. 이메일 로그인 → QR/전체 링크 확인 → 예약 차량 명시 승인 → 같은 VIN 인수 저장을 거칩니다. 소유자 예약 목록과 승인된 차량 연결에만 장소가 전달됩니다.
3. 차량의 이용 중·반납 화면에서 장소를 확인합니다. 차량 위치 권한이 없으면 권한 버튼을 제공하고 거리 대신 확인 불가를 표시합니다. Flutter는 같은 장소와 차량 내비 이용 안내를 표시하며 차량 SDK를 호출하지 않습니다.
4. 차량을 P에 두고 ‘반납 장소 길 안내’ → 목적지 확인을 진행합니다. 요청 직전에 실제 Vehicle SDK의 기어와 VIN을 다시 읽습니다. 연결·예약·목적지가 바뀌거나 P가 아니면 새 경로 요청을 보내지 않습니다. 기존 내비 목적지가 변경될 수 있음을 확인창에 알립니다.
5. Navigation은 경로 요청으로 스스로 전경에 올 수 있습니다. 실제 목적지와 안내 상태를 확인한 뒤 차량 앱으로 복귀합니다. 별도 ‘내비 화면 열기’도 제공합니다.
6. 직선거리 150m 이내는 ‘좌표상 가까워요’라는 참고 안내입니다. 지정 주차구역은 직접 확인합니다. 가까워져도 자동 도착·반납 처리하지 않으며 위치 오류로 기존 반납을 차단하지 않습니다.
7. 기존 창문·좌석 재조회 → 점유 좌석 화면·음성 → 반납 점검 저장 → 사진 3종·직접 확인·최종 접수를 유지합니다. 반납 점검 저장 후 위치·내비 구독을 종료합니다.

## 신호와 수명주기

- SDK 자체 수신 시각이 60초 이내인 유효 좌표만 거리 계산에 사용합니다. null·오류·오래된 값·범위 밖 좌표는 정상값으로 바꾸지 않습니다. 직선거리이며 도로 거리·주차구역 확인·실차 GPS 정확도가 아닙니다.
- `FUSED_LOCATION`은 실제 AVD에서 런타임 권한이 필요했습니다. 명시적 허용/거부를 처리하며 Android 휴대폰 GPS·백그라운드 위치 권한을 추가하지 않았습니다.
- 내비 권한은 `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`입니다. 반환 코드만으로 안내 성공을 표시하지 않고 실제 목적지 좌표와 경로 완료 또는 실제 안내 중 상태를 함께 확인합니다.
- AVD 서비스 3.0.4는 `onRoutingCompleted` 없이 `onRouteStarted`/`onRouteEnded`와 `ROUTE_GUIDING`을 보냈습니다. 요청 이름·목적지·실제 안내 상태를 확인하도록 호환 처리했습니다. 빈 기본 목적지나 이전 경로 취소를 성공으로 취급하지 않습니다.
- Navigation 왕복 후 실제 활성 목적지를 읽어 다시 확인합니다. 백그라운드·예약 변경·연결 해제·반납 저장에서 현재 좌표를 지우고 콜백을 해제하며 늦은 콜백을 버립니다. 다른 앱의 내비 경로를 임의 취소하지 않습니다.
- 현재 차량 좌표는 서버·반납 기록에 전송하거나 영구 저장하지 않습니다. 서버가 보관하는 것은 운영자가 지정한 목적지입니다. 기존 DB에는 nullable `bookings.return_location` 열만 추가합니다.

## 실제 검증 범위

읽기 전용 Pleos 2.1.0 ARM64 AVD **5556**, 분리 서버·시험 계정·설정에서 실제 프로덕션 컨트롤러·Compose·SDK를 실행했습니다. 원본 AVD 5554는 꺼진 상태로 보존했습니다. 공식 예제 좌표인 서울시 강남구 남부순환로 2621 / 위도 37.48544722·경도 127.03636666은 **실제 반납 거점이 아닌 시험 장소**입니다. AVD `geo fix`를 SDK가 실제 수신한 시험이며 실차 위치 정확도 검증은 아닙니다.

| 실제 통합 확인 | 결과 |
|---|---|
| 승인 예약의 정확한 시험 장소 수신 | 통과 |
| 위치 권한 거부 시 좌표·거리 확인 불가 | 통과 |
| 허용 후 실제 FusedLocation 새 값 | 통과: 먼 지점 약 1.48km |
| 요청한 내비 목적지·실제 안내 중 확인 | 통과 |
| D 조건에서 새 경로 요청 없음 | 통과 |
| 가까운 지점 수신·자동 반납 없음 | 통과: 모의 좌표상 약 0.16m, ACTIVE 유지 |
| 백그라운드 좌표 제거·SDK 연결 종료 | 통과 |
| 전경 복귀 후 실제 SDK 재수신 | 통과 |
| 별도 내비 화면 열기·실제 Activity 전경 | 통과 |
| 반납 점검 저장·서버 동기화·구독 종료 | 통과 |
| 서버 기록에 현재 좌표 업로드 없음 | 통과 |

Android **Debug 86 / Release 84** 전부 통과, Debug·Release·시험 APK 빌드 성공. Lint **오류 0 / 경고 24**. 서버 **42**개 통과(기존 nullable migration/소유권/잘못된 좌표 포함). Flutter **42**개 통과·선택 통합 2개 제외·분석 문제 0. 실제 Flutter 웹에서 동일한 분리 예약의 장소·시험 표시와 반납 사진 진입을 확인했습니다. 두 서명된 Release APK의 설치·첫 실행·프로세스·크래시 없음을 별도 확인했습니다.

이번 새 통합 시험은 **인수부터 반납 점검 저장까지**입니다. 사진 전송·직접 확인·최종 접수·TTS 전체 실재생의 선행 근거는 0.2.6/0.2.5 기록이며 이번에 다시 끝까지 수행했다고 주장하지 않습니다. 실차·정상 VIN Release 전체 흐름·실폰 QR/촬영·iOS·실제 주차구역은 미검증입니다. Release의 실제 VIN·HTTPS 검증을 유지합니다.

## 시험 예약 발급

서버의 운영자 키 `RENTAL_DEVICE_TOKEN`을 안전하게 설정하고 기존 실행 안내로 별도 서버·계정을 준비합니다. 키를 명령 인자나 저장소에 넣지 않습니다.

```sh
python server/issue_booking.py --base-url http://127.0.0.1:8788 \
  --email 본인_시험계정 --vin TEST0000000000001 --vehicle-name 안내시험차량 --demo \
  --return-name '시험 반납 장소 · 42dot 인근' --return-address '서울시 강남구 남부순환로 2621' \
  --return-latitude 37.48544722 --return-longitude 127.03636666 \
  --return-instructions '실제 반납 거점이 아닌 SDK 시험 장소입니다. 지정 구역은 직접 확인하세요.' --return-test
```

이후 기존 QR 승인과 명시적 Debug AVD 테스트 VIN 절차를 따릅니다. 새 API 필드가 없는 예약도 호환됩니다. SDK 단독 진단 `ReturnNavigationProbeRunner`와 분리 설정의 실제 컨트롤러 시험 `ReturnNavigationFlowRunner`는 androidTest에만 있고 Release에는 포함되지 않습니다. 자동 러너를 실행할 때는 원본 AVD·사용자 DB 대신 분리 시험 환경을 사용합니다.

## 배포와 다시 빌드

같은 [제출 Release](https://github.com/neolabs120-mobile/pleos-rental-check/releases/tag/v0.2.6-submission)에 다음을 추가합니다. 기존 파일과 영상은 보존하며 새 영상은 최종 게시 요청 때만 올립니다.

| 파일 | 버전·크기 | SHA-256 |
|---|---|---|
| `rentcheck_vehicle_0.2.8_release.apk` | 0.2.8+12 · 43,304,277바이트 | `1633beca02cbf06787ad52a4542cf6ecb707c1c0e4d99af869e66eace3c8a960` |
| `rentcheck_mobile_0.2.8_release.apk` | 0.2.8+12 · 70,555,691바이트 | `1f14d4e5bc5d212d28e025788535065081c85b9fe031e0174565c2e746332837` |

기존 Release 인증서 SHA-256 `35d839af51ea4d192240a18140bd4c0b911fa04682fe644ad5802896b9825fc8`로 서명했고 debuggable=false·16KB 정렬을 확인했습니다. 새 소스는 `rentcheck_source_0.2.8.zip`, 지문은 `SHA256SUMS_0.2.8.txt`, 안내는 `RELEASE_README_0.2.8.md`입니다. 기존 태그의 자동 Source code ZIP은 이전 0.2.6 소스입니다. 최신 ZIP 또는 main을 사용하세요.

```sh
./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease lintDebug
cd mobile
flutter pub get
flutter analyze
flutter test
flutter build apk --release --build-name=0.2.8 --build-number=12
```

Release는 기존 [서명 절차](RELEASE_SUBMISSION_0.2.6.md#다시-빌드하기)를 따릅니다. 휴대폰 Release에는 별도로 준비한 HTTPS 서버가 필요하며 `https://rental.invalid`는 운영 서버가 아닙니다. 기존 Debug 데이터를 지우며 Release로 교체하지 말고 분리 환경을 사용합니다.

공식 근거: [FusedLocation](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [NaviHelper](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro). 공식 Maven에서 제공되는 버전을 고정했으며 SDK를 수정하거나 차량 쓰기 API를 추가하지 않았습니다.
