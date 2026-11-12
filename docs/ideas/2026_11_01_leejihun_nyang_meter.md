# 냥미터기 아이디어

**작성일:** 2026년 11월 1일

**작성자:** LeeJihun

**관련 문서:** 2026_11_01_cat_meter.md (고양 미터기 추가 기능 아이디어)

## 1. 기획 배경

- 택시 미터기처럼 요금 미터를 보여주되, 단위를 원 대신 '냥'으로 바꿔 "몇 냥"처럼 재미있고 순화된 느낌으로 전달한다.
- 착안: 한국에서 자가용으로 돈을 받고 사람을 태우는 것은 불법이지만, 출퇴근 시간대 카풀은 허용된다. 그래서 평소에는 실제 돈 대신 '냥'을 쓰고, 이름도 냥 + 미터기 = 냥미터기로 정했다.
- 화면: Pleos는 운전자가 보는 위치에 반복되는 장식 애니메이션을 자제하라고 안내한다. 그래서 요금 미터는 내비와 분할 화면으로 함께 보여주고, 주행 중에는 숫자만 바뀌게 한다.

근거
- 여객자동차 운수사업법 제81조: 자가용자동차를 유상(자동차 운행에 필요한 경비 포함)으로 운송용으로 제공하거나 이를 알선하는 것을 금지한다. 예외는 출퇴근 시간대(평일 오전 7~9시, 오후 6~8시, 토·일·공휴일 제외)에 승용자동차를 함께 타는 경우 등이다. 위반 시 2년 이하 징역 또는 2천만원 이하 벌금.
- Pleos 안전 설계 가이드: 주행 중 운전자의 주의를 과도하게 끄는 영상, 애니메이션, 움직이는 이미지를 최소화하거나 제한한다.

## 2. 기본 기능

### 가. 냥 요금 미터

- 택시처럼 기본요금에서 시작해 달린 거리만큼 냥이 오른다.
- 막히거나 신호 대기로 느릴 때는 시간 요금으로 오르고, 밤에는 심야 할증이 붙는다.
- 근거: 실제 택시 요금은 기본요금, 거리 요금, 저속 시 시간 요금을 함께 계산하고 심야 할증이 있다. 거리와 시간은 Pleos Odometer API(getTripCurrentDrivingDistance, getTripCurrentDrivingTime), 속도는 AAOS PERF_VEHICLE_SPEED로 받을 수 있다.

### 나. 내비 옆 분할 화면 표시

- 좁은 창(세로)에서는 요금 숫자만, 넓은 창(가로)에서는 요금 내역까지 보여준다.
- 주행 중에는 숫자만 바뀌고 장식 애니메이션은 쓰지 않는다.
- 근거: Pleos Connect는 고정된 주행 정보 영역 외에 앱 두 개를 나란히 띄운다. Connect-S는 1:1, Connect-W는 2:1 또는 1:2로 배치한다.
- 내비 화면 위에 미터를 겹쳐 띄우는 기능(오버레이)은 Pleos 문서에 없다. 다른 앱 화면을 우리 앱 안에 넣는 것도 안드로이드에서는 시스템 앱만 가능하다.

### 다. 냥수증(도착 영수증)과 QR 결제

- 도착하면 택시 영수증처럼 기본, 거리, 시간 냥 내역과 이동 거리, 이동 시간을 보여주고 저장·공유한다.
- 결제는 냥수증에 뜨는 QR 코드로 하는 방식을 넣는다. 실제 결제는 PG사와 협업이 필요하므로 공모전에서는 모의 결제로 구현한다.
- 결제는 출퇴근 시간대에 미터가 켜진 운행만 가능하다. 자세한 조건은 '마. 냥풀'에 정리했다.
- QR 화면은 차를 세우고 P단에 둔 상태에서 띄운다.
- 근거: Pleos 주행 제한 정책상 주차 상태에서는 모든 기능을 쓸 수 있다.

### 라. 음성으로 요금 듣기

- "지금 1,250냥이다냥"처럼 화면을 보지 않아도 요금을 알려준다.
- 근거: Pleos Gleo AI SDK에 TTS(음성 합성)와 STT(음성 인식)가 있다. 주행 중에는 화면보다 음성이 안전하다.

### 마. 냥풀 (요금 나눠내기)

- 고양이(탑승자)들이 낼 요금을 냥으로 나눠 보여준다.
- 요금은 집사(운전자)와 고양이들이 미터당 금액을 합의하거나, 집사가 "어디까지 얼마"처럼 고정 요금으로 정한다.
- 결제는 출퇴근 시간대(평일 오전 7~9시, 오후 6~8시, 토·일·공휴일 제외)에 미터가 켜진 운행만 가능하다. 출퇴근 시간 전후에 시작한 운행은 나눈 냥만 보여주고 결제는 되지 않는다.
- 공휴일 정보는 서버에서 내려받는다. (예: 공공데이터포털의 한국천문연구원 특일 정보)
- 근거: 여객자동차 운수사업법 제81조. 출퇴근 시간대를 빼면 자가용으로 돈을 받는 것 자체가 불법이고, 기름값처럼 운행 경비를 나눠 받는 것도 포함된다. 미터당 합의 금액이든 고정 요금이든 똑같이 적용된다.
- 법에는 출퇴근 시간 경계에 걸친 운행의 기준이 따로 없다. '미터가 켜진 시각 기준'은 팀이 정한 운영 규칙이다.

### 바. 백그라운드 실행

- 앱이 화면에 떠 있지 않아도 미터와 탑승 기록은 계속 계산된다.
- 근거: 안드로이드 포그라운드 서비스로 구현할 수 있다. Pleos 성능·리소스 가이드에 백그라운드 제한이 있는지는 확인이 필요하다.

### 사. 글레오 음성 하차

- 글레오에게 "민수 내려"라고 하면 바로 앱이 실행되고 민수의 냥수증이 뜬다.
- 근거: Pleos Gleo Interface. 앱이 App Schema(JSON)에 하차 처리 같은 기능을 등록하면, 글레오가 LLM으로 말을 해석해 그 기능을 호출하고 Android Intent로 앱에 전달한다. 앱이 결과를 Callback으로 돌려주면 글레오가 "민수 2,100냥이에요"처럼 답한다.

## 3. AI 결합 아이디어

### 가. 냥풀 요금 합의 도우미

- 집사와 고양이들이 요금에 대해 나눈 대화 내용을 LLM에 전달해서 적절한 요금(미터당 금액 또는 고정 요금)을 책정한다.
- LLM은 대화 내용과 함께 이동 거리, 인원수, 연비를 참고한다.
- 출퇴근 시간대에 시작한 운행이 아니면 결제 없이 냥 표시만 된다고 안내한다.
- 근거: Pleos Gleo AI SDK의 STT(음성 인식)와 LLM, NaviHelper 경로 정보, 차량 연료·전비 데이터.
- 대화 내용을 LLM에 보내려면 탑승자 동의가 필요하다. Pleos 앱 심사는 개인정보를 쓰는 앱에 이용약관, 처리방침, 사용자 동의를 요구한다.

## 4. 추가 검토 후보

- 과속하면 미터 정지: 제한속도를 넘거나 급가속·급정거를 하면 미터가 멈추거나 냥이 깎인다. Pleos 앱 심사는 주행 안전에 위험한 앱을 거절하고, 팀 기획도 높은 속도로 보상을 얻는 구조를 쓰지 않는다. 제한속도 데이터를 받을 수 있는지는 확인이 필요하다.
- 모은 냥으로 고양이 꾸미기: 냥은 앱 안에서만 쓰게 한다. 현금이나 기프티콘으로 바꿔주면 게임산업진흥에 관한 법률 제32조 제1항 제7호(게임 결과물 환전 금지)에 걸릴 수 있다.
- 목적지 예상 냥: 내비에 목적지를 찍으면 예상 요금을 미리 보여준다. NaviHelper가 남은 거리를 주는지 확인이 필요하다.

## 5. 확인 필요 사항

- 글레오가 호출할 때 앱이 꺼져 있어도 실행되는지 (Gleo Interface 예제 문서 확인)
- 백그라운드에서 미터 계산을 계속할 수 있는지 (Pleos 성능·리소스 가이드 확인)
- 제한속도 데이터와 NaviHelper의 남은 거리 정보를 받을 수 있는지
- 신호 대기 같은 정차 중에도 QR 화면을 띄울 수 있는지. 문서에는 주차와 주행 정책만 있다.

## 6. 참고 자료

- 여객자동차 운수사업법 제81조: https://www.law.go.kr/법령/여객자동차운수사업법/제81조
- Pleos 창 정책(멀티윈도우): https://document.pleos.ai/en/docs/connect/guide/docs-design/getting-started/window
- Pleos 화면 구성: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
- Pleos 안전 설계 가이드: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- Pleos 주행 제한 정책: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
- Pleos 앱 심사 가이드: https://document.pleos.ai/en/docs/pleos-only/distribute/app-review-guide/
- Gleo AI SDK: https://document.pleos.ai/en/api-reference/connect-sdk-pleos/gleo-ai-index
- Gleo Interface 핵심 개념: https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/core-concepts
- NaviHelper: https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro
- 거리·시간 API: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/entry
