# 고양 미터기 (Cat Meter) — Pleos Connect MVP

함께 탄 사람들과의 이동을 **차량 데이터로 기록하고, 고양이와 영수증으로 보여 주는** Pleos Connect 앱의 MVP입니다.

`docs/ideas/2026_11_05_ideas_summary.txt` 3항의 **세 기획 방향에 공통으로 묶이는 기능**만 구현했습니다.
방향별 기능(냥 요금, 실제 비용 분담, 조향·공조 반응 등)은 다음 회의에서 핵심 방향을 정한 뒤 이 위에 붙입니다.

## MVP 범위

| 취합본 공통 기능 (3항) | 구현 내용 |
|---|---|
| 가. 차량 데이터 기반 이동 기록 | 출발~도착 사이 거리·시간·주행/정차 시간·정차 횟수 기록 |
| 나. 내비게이션 옆 분할 화면 | 좁은 창: 고양이 + 거리·시간 / 넓은 창: 고양이 + 상세 내역 |
| 다. 도착 후 영수증 | 고양 영수증 화면, 이미지 저장(갤러리), 공유 시트 |
| 라. 고양이와 동승자 설정 | 이름·털 색 4종·소품 4종(모자·선글라스·리본·목도리), 동승자 이름(최대 7명) — 기기에 저장 |

**넣지 않은 것**: 냥풀(구간별 분할), 실제 결제·송금, 좌석 점유·구간별 비용 분담·정산 QR, 결제·송금, 조향각·공조·와이퍼 반응, Gleo 음성, 서버 연동.

### 합의 전 항목은 선택지로 두었습니다
- **주행 중 고양이 표현 (5-나)**: 2026-11-06 결정으로 달리기 모션을 뺐습니다. 고양이는 식빵 자세 정지 그림만 있고,
  이동 화면에서는 **정차 중에만** 보입니다. 주행 중에는 숨기고 냥 미터 숫자만 남깁니다.
  (근거: Android for Cars 품질 기준 SA-1 "animated elements" 금지, NHTSA 주의분산 가이드라인)
- **냥 미터 (임시 요율)**: 이동 화면의 거리·시간을 냥으로 보여 줍니다. 거리 100m마다 10냥, 시간 1분마다 10냥이고 합계도 함께 표시합니다.
  구간을 채울 때마다 한 칸씩 오르고 숫자 전환 효과는 없습니다. 기본요금·저속 시간요금·심야 할증, 원화 환산은 넣지 않았습니다. (`trip/NyangFare.kt`)
- **냥수증 요금 = 미터 + 주유비·충전비**: 미터(거리·시간 냥)에 거리 ÷ 연비(전비) × 단가로 계산한 에너지 비용을 더합니다. 1냥 = 1원으로 봅니다.
  연비·전비는 Pleos `Fuel`·`EvBattery` 누적 평균을 쓰고, 못 받으면 기본값(전기 5.0 km/kWh·347원/kWh, 휘발유 12 km/L·1,700원/L)을 씁니다.
  에뮬레이터 차량은 연비·전비 속성을 지원하지 않아(`ERROR_NOT_SUPPORTED_PROPERTY`) 전기차 기본값이 쓰입니다. (`trip/TripBill.kt`)
- **출퇴근 함께 타기 정산 (여객자동차법 제81조 제1항 제1호)**: 평일(공휴일 제외) 오전 7~9시·오후 6~8시에 출발·도착하고,
  내비 즐겨찾기의 집 ↔ 회사(반경 1km) 사이를 이동했고, 같은 시간대에 아직 정산하지 않았을 때만 도착 화면에 정산 QR(모의 결제)을 띄웁니다.
  동승자의 출퇴근 여부는 앱이 알 수 없어 정산 전에 체크박스로 확인받습니다. 그 밖의 이동은 결제 없이 냥만 표시하고 이유를 보여 줍니다.
  시각은 기기 시간대와 관계없이 한국 시간으로 판정합니다. 공휴일은 2026~2027년 목록을 앱에 넣었습니다(임시공휴일은 수동 추가). (`trip/CommuteRule.kt`)
- **내비 연동 (NaviHelper 3.0.5)**: 내비에서 경로를 시작하면 목적지·예상 거리·시간이 냥미터기에 자동으로 잡히고, 출발 전 예상 냥과 정산 가능 여부를 보여 줍니다.
  거리는 m, 시간은 초로 가정했습니다. 실제 목적지를 찍어 로그(`CatMeterNavi`)로 단위를 확인해야 합니다. (`car/NaviSource.kt`)
- **영수증 내용 (7-3)**: 공통 부분(고양이, 거리, 시간, 동승자)만 담았습니다. 냥·실제 경비 내역은 들어 있지 않습니다.

### 이동 기록 기준 (팀 운영 규칙, 플랫폼 정의 아님)
- 시작·종료: 정차 중 사용자가 **출발 / 도착**을 누른 시점입니다. 차량 트립 초기화와는 관계없습니다.
  주행 후 기어가 P로 바뀌면 "도착했나요?" 확인 카드를 띄웁니다.
- 거리: 계기판 누적 거리(`Odometer.getCurrentDistanceDriven`)가 실제로 늘어나는 동안은 출발 때와의 차이입니다. 누적 거리를 받지 못하거나 값이 멈춰 있으면(Pleos 에뮬레이터는 주행 중에도 `0.0` 고정) 차속을 시간에 대해 적분해 추정하고, 영수증에 어느 기준을 썼는지 표시합니다.
- 정차: 3km/h를 넘으면 이동, 1km/h 미만이면 정차로 봅니다. 신호 대기·정체·주차는 구분하지 않습니다.
- 주행 중(주행 제한 상태)에는 입력 UI를 숨깁니다. 설정·출발·도착·저장은 정차해서 제한이 풀렸을 때만 할 수 있습니다.

## 사용한 차량 데이터

| 데이터 | 경로 | 권한 |
|---|---|---|
| 차속 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (Vehicle SDK 2.3.2에는 차속 API 없음) | `android.car.permission.CAR_SPEED` (런타임 요청) |
| 주행 제한 상태 | AAOS `CarUxRestrictionsManager` | 없음 |
| 누적 주행 거리 | Pleos Vehicle `Odometer.getCurrentDistanceDriven` / `registerDistanceDriven` | `pleos.car.permission.CAR_DRIVE` |
| 전비·연비 | Pleos Vehicle `EvBattery.getCumulativeEvEfficiency` / `Fuel.getCumulativeFuelEconomy` | `pleos.car.permission.CAR_ENERGY` |
| 목적지·집·회사·현재 위치 | Pleos NaviHelper `getDestinationInfo` / `onRouteStarted` / `getBookmarkInfo`(HOME·OFFICE) / `getCurrentLocationInfo` | `pleos.car.permission.NAVI_ROUTE` |
| 기어(P 판단) | Pleos Vehicle `Gear.getCurrentGearSelectionState` / `registerGearSelection` | `pleos.car.permission.CAR_DRIVE` |

차량 제어 API는 쓰지 않습니다.

## 실행

1. Android Studio로 이 저장소를 엽니다. JDK는 Android Studio에 내장된 JBR을 씁니다.
2. **Pleos Connect 에뮬레이터**(system-image `android-34/pleos_car_emulator`)를 실행합니다.
3. `./gradlew installDebug`를 실행합니다. 터미널에서 빌드하려면 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`을 지정합니다.
4. 주행 값(속도·기어·누적 거리)은 **Pleos Connect Virtual Console**에서 넣습니다. 이 에뮬레이터는 user 빌드라 `adb shell cmd car_service inject-vhal-event`를 쓸 수 없습니다.
5. 분할 화면은 화면 오른쪽 끝의 `⋮` 핸들을 왼쪽으로 끌어서 엽니다.

단위 테스트: `./gradlew testDebugUnitTest`

## 구조

```
app/src/main/java/kr/catmeter/app/
  trip/TripTracker.kt       이동 기록 계산 (순수 Kotlin, 단위 테스트 대상)
  trip/Models.kt            고양이·소품·이동 기록 모델
  trip/NyangFare.kt         거리·시간 → 냥 환산 (순수 Kotlin, 단위 테스트 대상)
  car/AaosMonitor.kt        차속 + 주행 제한 상태 (AAOS Car API)
  car/PleosVehicleSource.kt 누적 거리 + 기어 (Pleos Vehicle SDK)
  data/CatMeterStore.kt     설정·동승자·기록 로컬 저장 (SharedPreferences, 서버 전송 없음)
  CatMeterSession.kt        앱 프로세스 단위 이동 세션 (창 전환·분할 화면 변경에도 기록 유지)
  ui/CatCanvas.kt           고양이 일러스트에 털 색 입히기 + 소품 겹치기
art/cat/                    고양이 일러스트 원본 SVG (README 참고)
tools/svg2vd.py             SVG → Android 벡터 이미지 변환
  ui/SetupScreen.kt         출발 전 설정
  ui/DriveScreen.kt         이동 중 (좁은 창·넓은 창 레이아웃)
  ui/ReceiptScreen.kt       고양 영수증 + 이미지 캡처
  ui/ReceiptExporter.kt     갤러리 저장(MediaStore) · 공유(FileProvider)
```

## 검증 결과 (2026-11-05, Pleos Connect 에뮬레이터 android-34, Virtual Console 미연결)

- ✅ 단위 테스트 8개 통과 (`TripTrackerTest`: 누적 거리 차이, 차속 적분 대체, 누적 거리 늦게 수신, 값 역행, 정차 히스테리시스, 시간 합계, 센서 공백, 고양이 상태)
- ✅ 앱 실행 시 차속 구독, 주행 제한 상태(`requiresDO=false`), Pleos Odometer(`0.0`)·Gear(`GEAR_PARK`) 수신 로그 확인
- ✅ 설정(소품·동승자) → 출발 → 도착 → 영수증 → 이미지 저장 흐름 확인. 저장 파일: `Pictures/CatMeter/cat_receipt_*.png` (사용자 10)
- ✅ 실제 Pleos 분할 화면(내비 + 고양 미터기)에서 좁은 창 레이아웃이 적용되는 것 확인
- ⚠️ **주행 상태(속도 > 0, D단)는 확인하지 못했습니다.** Virtual Console을 연결하지 않았고 VHAL 주입도 막혀 있습니다.
  주행 중 고양이 숨김·냥 증가, 주행 제한 시 입력 UI 숨김, 누적 거리 증가, P단 전환 후 도착 확인 카드는 Virtual Console로 다시 확인해야 합니다.
- ⚠️ 누적 거리 단위를 km로 가정했습니다. Virtual Console에서 값을 넣어 단위를 확인해야 합니다.
- ⚠️ 공유 버튼은 에뮬레이터에서 시스템 인쇄 앱(`com.android.bips`)만 공유 대상으로 떠서, 그 앱이 비정상 종료됐습니다. 우리 앱의 오류는 아닙니다. 실차 공유 대상은 확인이 필요합니다.
- ⚠️ 앱 프로세스가 종료되면 진행 중인 기록은 사라집니다. 주행 중 백그라운드(포그라운드 서비스) 기록이 허용되는지 확인한 뒤 붙일 예정입니다.

## 다음 회의에서 정할 것 (취합본 7항과 연결)

1. 핵심 방향 → 정해지면 `trip/`에 냥 요금 계산 또는 구간 비용 계산을, `ReceiptScreen`에 해당 내역을 추가합니다.
2. 냥 요율 → 기본요금·저속 시간요금·심야 할증을 넣을지, 냥수증(영수증)에 냥 내역을 넣을지 정합니다.
3. 탑승자 입력 → 지금은 이름만 받습니다. 좌석 점유를 연결하려면 `Seat` API와 `CAR_SEATS` 권한을 추가해야 합니다.
4. 첫 검증 → Virtual Console로 속도·기어·누적 거리를 넣고 위 ⚠️ 항목을 확인합니다.
