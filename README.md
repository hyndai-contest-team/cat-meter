# 냥미터기 (Cat Meter)

함께 출퇴근하는 차에서, 돈이 오가도 되는 길인지 차가 먼저 판정하고, 누가 어디서 타고 내렸는지 알아보아 그 사람이 탄 구간의 기름값·충전비와 통행료만 운전자와 똑같이 나눠 자동으로 정산하는 Pleos Connect 앱입니다. 함께 달린 길은 옛 돈 단위 '냥'으로 세고, 도착하면 '냥' 하고 우는 고양이가 영수증(냥수증)을 건넵니다.

## 실행 방법

1. Pleos Connect 에뮬레이터를 **Cold boot**로 켭니다. Quick boot로 켜면 차량 값(VHAL)을 쓸 수 없습니다.
   ```bash
   ./scripts/run-emulator.sh
   ```
   스크립트가 에뮬레이터 켜기, 부팅 대기, Virtual Console 포트 연결(`adb forward tcp:8088 tcp:8088`)과 열기를 차례로 합니다.
2. 탑승 등록 서버를 같은 컴퓨터에서 켭니다.
   ```bash
   cd server
   npm start
   ```
3. APK를 설치하고 실행합니다.
   ```bash
   adb install -r apk/tridrive_CatMeter_release.apk
   ```
   처음 실행할 때 위치 권한과 차속 권한을 묻습니다. 둘 다 허용합니다.
4. Virtual Console(`http://127.0.0.1:8088/`)에서 차량 값을 넣어 동작을 확인합니다.
   - 기어를 **P**에 두고 Seat Occupancy에서 조수석이나 뒷좌석을 "앉음"으로 바꾸면 그 자리의 탑승 QR이 뜹니다.
   - 기어 **D**에서 Odometer의 누적 거리를 올리면 달린 거리와 냥, 실비가 오릅니다.
   - 차를 세우고 자리를 비운 뒤 그 자리 쪽 문(Door Position)을 열면 하차로 판단합니다.
   - 기어를 **P**에 두고 "도착했어요!"를 누르면 정산과 냥수증이 나옵니다.

직접 빌드하려면 `./gradlew :app:assembleDebug`(서명 설정 없이 됨) 또는 `./gradlew :app:assembleRelease`를 실행합니다. release 빌드에는 `local.properties`에 서명 키 정보(`release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`)가 있어야 합니다.

## 테스트 환경 및 필요한 설정

**테스트 환경**

- Pleos Connect 에뮬레이터 v2.1.0 (AVD `Pleos_Connect_v2.1.0`, system-image `android-34/pleos_car_emulator`)
- 앱: minSdk 28, targetSdk 35
- 탑승 등록 서버: Node.js 20 이상, 외부 라이브러리 없음 (`server/`)
- 개발 컴퓨터: macOS (Apple Silicon), Android Studio 내장 JDK

**필요한 설정**

- 에뮬레이터는 Cold boot로 켜고, Virtual Console을 연결해 둡니다 (`adb forward tcp:8088 tcp:8088`).
- 탑승 등록 서버(`server/`)를 같은 컴퓨터에서 켜 둡니다. 앱은 `http://10.0.2.2:8787`로 접속합니다. 서버가 없으면 탑승 QR 대신 "탑승 서버에 연결할 수 없어요"가 뜨고, 이름은 직접 넣을 수 있습니다.
- 위치 권한과 차속 권한(`android.car.permission.CAR_SPEED`)을 허용합니다. 차속 권한을 미리 주려면 `adb shell pm grant kr.catmeter.app android.car.permission.CAR_SPEED`.
- 자동 정산은 **평일 출퇴근 시간대(오전 7 ~ 9시, 오후 6 ~ 8시)**에 내비 즐겨찾기의 집↔회사 사이를 이동할 때만 켜집니다. 그 밖의 시간에는 냥 기록만 남습니다.
- 결제는 테스트 결제이며 실제 출금은 없습니다.

## 개발에 사용한 Pleos Connect 에뮬레이터

- 버전: **Pleos Connect 에뮬레이터 v2.1.0**
- 화면 규격: **2560 × 1440 px, 160 dpi**, 가로 화면
- 내비 옆 분할 화면(좁은 창)과 앱 단독 화면(넓은 창)을 모두 지원합니다.

## APK 파일 위치

- [`apk/tridrive_CatMeter_release.apk`](apk/tridrive_CatMeter_release.apk) (release 빌드, 버전 0.1.2)
