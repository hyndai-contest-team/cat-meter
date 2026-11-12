# 고양 미터기 (Cat Meter) — Pleos Connect MVP

함께 탄 사람들과의 이동을 **차량 데이터로 기록하고, 고양이와 영수증으로 보여 주는** Pleos Connect 앱의 MVP입니다.

`docs/ideas/2026_11_05_ideas_summary.md` 3항의 **세 기획 방향에 공통으로 묶이는 기능**만 구현했습니다.
방향별 기능(냥 요금, 실제 비용 분담, 조향·공조 반응 등)은 다음 회의에서 핵심 방향을 정한 뒤 이 위에 붙입니다.

## MVP 범위

| 취합본 공통 기능 (3항) | 구현 내용 |
|---|---|
| 가. 차량 데이터 기반 이동 기록 | 출발~도착 사이 거리·시간·주행/정차 시간·정차 횟수 기록 |
| 나. 내비게이션 옆 분할 화면 | 좁은 창: 고양이 + 거리·시간 / 넓은 창: 고양이 + 상세 내역 |
| 다. 도착 후 영수증 | 고양 영수증 화면, 이미지 저장(갤러리), 공유 시트 |
| 라. 고양이와 동승자 설정 | 이름·털 색 4종·소품 4종(모자·선글라스·리본·목도리), 동승자 이름(최대 7명) — 기기에 저장 |

**넣지 않은 것**: 냥풀(구간별 분할), 실제 결제·송금(자동결제는 테스트만), 좌석 점유·구간별 비용 분담·정산 QR, 결제·송금, 조향각·공조·와이퍼 반응, Gleo 음성, 서버 연동.

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

### 한 번에 실행 (에뮬레이터 + Virtual Console)

```bash
./scripts/run-emulator.sh            # 에뮬레이터 켜기 → 부팅 대기 → Virtual Console 브라우저로 열기
./scripts/run-emulator.sh --install  # 위에 더해 앱 빌드·설치까지
```

스크립트가 하는 일:
1. AVD `Pleos_Connect_v2.1.0`을 **Cold boot**(`-no-snapshot-load`)로 켭니다. Quick boot로 켜면 VHAL을 쓸 수 없습니다. 이미 켜진 에뮬레이터가 있으면 그것을 씁니다.
2. 부팅이 끝날 때까지 기다립니다 (`sys.boot_completed`, 최대 5분).
3. `--install`을 주면 Android Studio 내장 JBR로 `./gradlew installDebug`를 실행합니다.
4. `adb forward tcp:8088 tcp:8088`으로 포트를 연결하고, 응답이 오면 `http://127.0.0.1:8088/`(Virtual Console)을 기본 브라우저로 엽니다.

바꿀 수 있는 값: `AVD_NAME`(에뮬레이터 이름), `ANDROID_HOME`(SDK 경로, 기본 `~/Library/Android/sdk`), `JAVA_HOME`.
예: `AVD_NAME=Pleos_Chabak_Test ./scripts/run-emulator.sh`. 에뮬레이터 로그는 `$TMPDIR/catmeter-emulator.log`에 남습니다.

같은 일을 손으로 할 때:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load &   # 에뮬레이터 (Cold boot)
adb wait-for-device                                                                       # 부팅 기다리기
adb forward tcp:8088 tcp:8088                                                             # Virtual Console 포트 연결
open http://127.0.0.1:8088/                                                               # Virtual Console 열기
```

Virtual Console이 열리지 않으면 `adb devices`로 에뮬레이터가 보이는지 확인하고, `adb forward tcp:8088 tcp:8088`을 다시 실행합니다.
PC에서 8088 포트를 다른 프로그램이 쓰고 있지 않은지도 확인합니다. (근거: Pleos 문서 [Virtual Console Setup Guide](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [Configuring Emulator](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator))

### 탑승 등록 서버 (탑승 QR)

출발 전 화면의 **탑승 QR 띄우기**를 누르면, 앱이 이 서버에 이번 이동을 열고 휴대폰용 주소를 QR로 보여 줍니다.
승객이 휴대폰으로 찍어 이름을 넣으면, 2초 간격으로 확인해 동승자 목록에 넣습니다. 외부 라이브러리 없이 Node.js 20 이상만 있으면 됩니다.

**자동결제 (테스트 · 실제 출금 없음)**
1. 승객은 탑승 페이지에서 이름을 넣고 **"지금 출근·퇴근하는 길이에요"**를 고릅니다. 고르지 않으면 결제 없이 냥만 표시됩니다.
2. 출퇴근 길이면 결제 수단(카카오페이·네이버페이·토스페이·테스트 카드)을 한 번 고릅니다. 실제 결제창은 열리지 않고 카드번호도 받지 않습니다. 서버는 `mock_`으로 시작하는 가짜 빌링키만 저장합니다.
3. 차량 탑승 QR 화면에 사람별 상태가 보입니다 (`하늘 · 카카오페이`, `바다 · 결제 미등록`, `별 · 출퇴근 아님`, `민수 · 직접 입력`).
4. 도착하면, 출퇴근 함께 타기로 판정된 이동이면 앱이 서버에 1인당 금액으로 자동결제를 요청합니다. 도착 화면에 사람별 결과가 나오고, 열려 있는 휴대폰 페이지에는 영수증이 뜹니다.
   결제하지 못한 사람(결제 수단 미등록, 출퇴근 아님, QR로 타지 않음)은 이유를 보여 줍니다. 같은 이동은 두 번 결제하지 않습니다.
5. 결제 요청은 이동을 연 차량만 할 수 있습니다 (서버가 처음 이동을 열 때 준 차량 토큰 필요).
6. **중간 탑승:** 이동 중 차가 서 있을 때(주행 제한이 풀렸을 때) 이동 화면에 **중간 탑승 QR** 버튼이 보입니다. 같은 탑승 코드로 열리고, 차가 움직이면 QR 화면은 바로 닫힙니다.
   중간에 탄 사람은 탄 지점(거리·시간)을 기록해 **탄 뒤 구간만** 나눠 냅니다. 탑승 시점마다 구간을 나누고, 각 구간 요금을 그 구간에 타고 있던 사람끼리 나눕니다.
   아무도 타지 않은 구간은 운전자 몫이고, 사람별 금액은 10냥 단위로 올립니다. (`TripBill.shares`)

**좌석으로 탑승·하차 자동 판단**
- **탈 때:** 동승석에 새로 앉으면(안전벨트 착용 또는 좌석 점유) 차가 서 있을 때 그 자리 탑승 QR(예: "조수석 탑승 QR")이 자동으로 뜹니다. 탑승 등록이 끝나면 닫히고, 운전자는 "이 자리는 나중에"로 넘길 수 있습니다. 넘긴 자리는 일반 탑승 QR 화면의 "앉았지만 아직 등록하지 않은 자리"에서 다시 띄울 수 있습니다. 같은 휴대폰이 다른 자리 QR을 찍으면 자리 이동으로 처리합니다.
- **함께 탈 때 (자리 고르기):** 출발 전에 두 명 이상이 함께 앉으면 자리별 QR 대신 QR 하나를 띄웁니다. 휴대폰에서 QR을 찍으면 차 좌석 배치도가 먼저 나오고, **사람이 앉아 있고 아직 아무도 고르지 않은 자리만** 누를 수 있습니다. 자리를 고른 뒤 이름·결제 수단을 넣습니다. 같은 자리를 먼저 고른 사람이 있으면 다시 고르게 하고, 한 자리만 남으면 그 자리 QR로 바뀝니다. 차량은 좌석 상태가 바뀔 때마다 서버에 알립니다 (`PUT /api/rides/:code/seats`).
- **내릴 때 (이동 중):** 차가 서 있을 때(속도 기준, P단이 아니어도 됨) 자리가 비면 "내리는 중?"으로 표시하고, 좌석 점유 센서가 있는 자리는 **30초**, 벨트만 있는 자리는 **벨트를 푼 채 다시 출발하면** 하차로 확정합니다 (`AlightTracker`).
  하차 지점은 자리가 빈 순간이라 기다리는 시간은 금액에 영향이 없습니다. 달리는 중에 비면 하차로 보지 않습니다.
- 하차가 확정되면 승객 휴대폰에 "하차 처리됐어요"와 **[아직 타고 있어요]**가 뜨고, **60초** 뒤 그 사람만 탄 구간만큼 결제합니다. 취소하면 그 자리는 다시 앉을 때까지 하차로 판단하지 않습니다.
- 중간 하차 결제는 출퇴근 최종 판정 전이라 지금까지의 조건(출발 시각·출발지·내비 목적지)으로 미리 판정해 맞을 때만 하고, 아니면 도착 때 함께 결제합니다. 도착 때는 남은 사람을 결제하고, 먼저 결제한 사람은 서버가 다시 결제하지 않습니다.
- 좌석 신호는 1.5초 동안 그대로일 때만 봅니다 (알림 등록 순간이나 앉는 동안 값이 잠깐 흔들림).
- 에뮬레이터: Virtual Console의 Seat Occupancy로 시험합니다 (안전벨트 착용은 콘솔에서 바꿀 수 없음). debug 빌드는 콘솔과 반대로 들어오는 좌석 점유 값을 뒤집습니다 (`SEAT_OCCUPANCY_INVERTED`). 차속은 기어 D에서 가속 페달 값으로 바뀝니다.

**시연용 출퇴근 판정 (debug 빌드만)**: 자동결제는 평일 출퇴근 시간대에 집↔회사를 이동한 운행에서만 켜집니다. 다른 시간에 시연하려면 아래 명령으로 판정을 켭니다. 앱을 다시 시작하면 꺼집니다.

```bash
adb shell am broadcast --user current -a kr.catmeter.app.DEBUG_ROUTE -n kr.catmeter.app/.DebugRouteReceiver --ez demoCommute true
```

```bash
cd server
npm start   # 또는 node src/server.mjs
npm test    # 서버 테스트
```

- 서버는 `8787` 포트로 뜨고, 시작할 때 휴대폰이 열 주소(이 컴퓨터의 와이파이 IP)를 출력합니다. **휴대폰과 컴퓨터가 같은 와이파이**에 있어야 합니다.
- 에뮬레이터 앱은 `http://10.0.2.2:8787`로 접속합니다. 다른 서버를 쓰려면 `./gradlew installDebug -PboardingServerUrl=https://...`로 빌드합니다.
- 환경 변수: `PORT`(기본 8787), `PUBLIC_URL`(휴대폰이 열 주소. 비우면 와이파이 IP로 만듭니다).
- 탑승 정보는 서버 메모리에만 두고, 3시간이 지나면 지웁니다. 서버를 끄면 사라집니다. 받는 개인정보는 탑승자 이름뿐입니다.
- debug 빌드만 `10.0.2.2`·`localhost`로 http 통신을 허용합니다 (`app/src/debug/res/xml/network_security_config.xml`). 실차·release는 https 서버가 필요합니다.
- 서버에 연결하지 못하면 QR 대신 "탑승 서버에 연결할 수 없어요"와 **다시 연결** 버튼을 보여 주고, 이름은 직접 넣을 수 있습니다.

| 주소 | 누가 | 하는 일 |
|---|---|---|
| `PUT /api/rides/:code` | 차량 앱 | 이동을 열고 휴대폰용 주소(`joinUrl`)와 차량 토큰을 받음 |
| `GET /api/rides/:code` | 차량 앱 | 탑승자 이름 목록 |
| `GET /ride/:code` | 승객 휴대폰 | 탑승 등록 페이지 |
| `POST /api/rides/:code/passengers` | 승객 휴대폰 | 이름·출퇴근 여부를 넣어 탑승 (최대 7명, 이름 1~10자, 같은 이름 불가) → 내 탑승 번호 |
| `POST /api/rides/:code/passengers/:id/payment` | 승객 휴대폰 | 테스트 결제 수단 등록 (출퇴근 길인 사람만) |
| `GET /api/rides/:code/passengers/:id` | 승객 휴대폰 | 내 탑승 정보와 결제 결과 |
| `POST /api/rides/:code/charges` | 차량 앱 | 도착 후 자동결제 (테스트). `Authorization: Bearer <차량 토큰>` 필요 |

### Android Studio로 실행

1. Android Studio로 이 저장소를 엽니다. JDK는 Android Studio에 내장된 JBR을 씁니다.
2. **Pleos Connect 에뮬레이터**(system-image `android-34/pleos_car_emulator`)를 실행합니다. AVD 설정에서 Boot option을 **Cold boot**로 둡니다.
3. `./gradlew installDebug`를 실행합니다. 터미널에서 빌드하려면 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`을 지정합니다.
4. 주행 값(기어·누적 거리·좌석 점유 등)은 **Pleos Connect Virtual Console**(위 스크립트, 또는 `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`)에서 넣습니다. 이 에뮬레이터는 user 빌드라 `adb shell cmd car_service inject-vhal-event`를 쓸 수 없습니다.
5. 분할 화면은 화면 오른쪽 끝의 `⋮` 핸들을 왼쪽으로 끌어서 엽니다.

### Virtual Console에서 넣을 수 있는 값 (Pleos 문서 기준)

| 영역 | 카드 | 이 앱에서 쓰는 곳 |
|---|---|---|
| Motion | Gear Selection (P·R·N·D), Odometer (0 ~ 1,000,000 km) | 도착 확인(P단), 누적 거리 |
| Convenience | Seat Occupancy (운전석·조수석·뒷좌석 좌우, Empty / Occupied) | 좌석 점유 (탑승 확인에 연결 예정) |
| Safety | Telltales (안전벨트 경고등 등 Off / On / Blink) | 안전벨트 **착용 여부가 아니라 경고등**만 바꿀 수 있습니다 |

차속을 넣는 카드는 문서 표에 없습니다. 차속을 어떻게 바꾸는지는 Virtual Console을 열어 확인해야 합니다.

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
  ui/BoardingQrScreen.kt    탑승 QR 화면 (서버 연결 상태·탑승자 목록)
  trip/Boarding.kt          탑승 코드 만들기
  data/BoardingClient.kt    탑승 등록 서버와 통신
server/                     탑승 등록 서버 (src/app.mjs 요청 처리, src/store.mjs 저장소, public/ride.html 휴대폰 페이지)
scripts/run-emulator.sh     에뮬레이터 + Virtual Console 한 번에 실행
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
