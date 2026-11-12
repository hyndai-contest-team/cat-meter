# 냥 미터기 3D 캐릭터 모델 기술 명세 (초안)

**대상 프로젝트:** cat-meter / PLEOS 기반 Android 앱

**모델 위치:** app/src/main/assets/models/cat_cheese.glb / cat_mackerel.glb / cat_black.glb / cat_white.glb (털 색마다 하나)

**상태:** 모델 v0.2.0 생성·검증, Filament 앱 연동 및 PLEOS 에뮬레이터 렌더링 확인 (2026-11-06)

**원본:** tools/make_cat_glb.py (코드로 생성. 모델을 고칠 때는 이 스크립트를 고치고 다시 실행)

**미리보기:** python3 tools/preview_glb.py [--set views|poses|furs] → build/cat_preview.png

## 1. 목적

- 목적지 설정 화면과 도착 후 '고양 영수증' 화면에 등장할 고양이 마스코트 정의
- 차량 주행 중에는 캐릭터 렌더링 및 불필요한 애니메이션 중지
- 기본 캐릭터 한 종을 바탕으로 털 색상, 소품, 표정, 포즈, 애니메이션 확장
- 말랑한 클레이 피규어 느낌의 소프트 3D 스타일 유지

## 2. 시각적 요구사항

- 기존 2D 고양이 이미지와는 별도 신규 디자인
- 둥근 얼굴, 자연스럽게 연결되는 통통한 몸통, 앞발 2개, 유연한 꼬리
- 얼굴과 몸통, 꼬리, 앞발이 화면에서 잘 보이는 구도
- 기본형에 모자/목도리 없음 (장착 가능한 선택 소품으로 제공)
- 과장된 실사 털보다는 매끈한 클레이 스타일 표면
- 다크 UI에서 실루엣과 표정이 명확히 보일 것

## 3. GLB 내부 노드 명세 (v0.2 실제 구조)

```text
CatRoot
├── Body                     몸통·엉덩이·뒷발이 한 덩어리 (Idle 숨쉬기)
│   └── BodyAccessories
│       └── Scarf            목도리 (기본 숨김)
├── Head                     얼굴·볼·귀가 한 덩어리 + 수염. 피벗은 목
│   ├── LeftEye / RightEye   홍채·동공 + 하이라이트 (깜빡임: 눈 중심 피벗, 세로로 납작하게)
│   ├── Nose
│   ├── Mouth
│   └── Accessories
│       ├── Hat              니트 모자 (기본 숨김)
│       ├── Sunglasses       선글라스 (기본 숨김)
│       └── Ribbon           리본 (기본 숨김)
├── LeftFrontPaw / RightFrontPaw   어깨가 피벗 (Wave·ShowReceipt)
├── Tail                     꼬리 뿌리 절반
│   └── TailTip              꼬리 끝 절반 (따로 휘어 살랑임)
└── Receipt                  고양 영수증 (기본 숨김)
```

- glTF 에는 '숨김' 속성이 없다. 소품·영수증은 모두 들어 있고 앱이 숨긴다(레이어 마스크).
- 앱은 이름으로 엔티티를 찾으므로(getEntitiesByName) 계층 위치보다 이름이 중요하다.
- v0.1 대비: 무늬 노드(*Pattern) 없음 — 무늬는 꼭짓점 색으로 칠해져 있다.

## 4. 털 색과 재질 (v0.2)

- 털 색마다 GLB 하나: cat_cheese / cat_mackerel / cat_black / cat_white. 앱은 털을 바꾸면 모델을 갈아 끼운다.
  (털 색에 따라 배 털·줄무늬·양말·눈 색·재질 광택까지 달라 실행 중 색 바꾸기로는 표현이 부족했음)
- 털·배 털·줄무늬·볼 터치·귀 안쪽: 꼭짓점 색(COLOR_0)으로 부드럽게 칠함. 재질 Fur 는 흰색 × 꼭짓점 색.
- 재질: Fur, Eye(꼭짓점 색 홍채), EyeHighlight, Nose, Mouth, Whisker, Paper, Ink,
  HatMain, HatBand, Lens, Frame, Ribbon, Scarf
- 광택: 까망은 유광 비닐(KHR_materials_clearcoat), 나머지 털은 무광 클레이. 눈·선글라스 렌즈도 클리어코트.
- 까망은 눈을 호박색으로 칠함 (다크 UI 에서 표정이 보이도록).
- 모양: 몸·머리·발·꼬리를 거리 함수로 부드럽게 합친 뒤 표면을 뽑음 → 이음새 없는 말랑한 피규어 표면.
  비율은 큰 머리 + 작은 몸 + 짧은 앞발 (2026 소프트 3D 캐릭터 경향, 냥미터기_UI_캐릭터_트렌드_분석.md 참고).

## 5. 소품

- Hat(니트 모자), Sunglasses, Ribbon(왼쪽 귀 밑), Scarf, Receipt — 모두 GLB 안에 있고 앱이 표시/숨김
- 머리 소품은 Head, 목도리는 Body 의 자식이라 Idle 등 움직임을 그대로 따라감
- 모자를 쓰면 귀가 대부분 가려짐 (귀 구멍은 아직 없음)

## 6. 애니메이션 (v0.1.0, 노드 회전·이동·크기 키프레임. 리깅 없음)

| 이름        | 길이  | 움직이는 노드                         | 재생 방식            |
| --- | --- | --- | --- |
| Idle        | 3.0s  | Body(숨쉬기), Head                    | 반복                 |
| TailWag     | 1.6s  | Tail, TailTip                         | 반복                 |
| Blink       | 0.2s  | LeftEye, RightEye                     | 3~5초마다 한 번      |
| Wave        | 1.6s  | RightFrontPaw(회전+들어 올림), Head   | 한 번 (인사, 누르면 다시) |
| ShowReceipt | 1.2s  | 양 앞발, Receipt(커지며 등장), Head   | 한 번, 마지막 자세 유지 |

동시 재생 규칙 (Filament Animator.applyAnimation 은 나중에 적용한 값이 이김):
- Idle·TailWag·Blink 는 건드리는 노드가 겹치지 않아 매 프레임 함께 적용해도 됨.
- Wave·ShowReceipt 는 Head 도 움직이므로 Idle 다음에 적용. 둘은 앞발이 겹치니 동시에 쓰지 않음.
- ShowReceipt 동안 Receipt 노드는 보이게 해야 함 (시작 시 크기 0.001 → 1).

## 7. 앱 상태와 표시 정책

- SETUP: 목적지 설정 시 Idle + (허용되는 경우) TailWag/Blink
- DRIVING: 캐릭터 숨김, 애니메이션 업데이트/불필요한 렌더링 중지
- RECEIPT: 목적지 도착 및 정산 확인 시 ShowReceipt
- 차량 주행 제한 및 PLEOS UX/심사 정책을 확인하고 실제 표시 여부 제어
- 단순 화면 상태만으로 차량이 안전하게 정차했다고 간주하지 말 것

## 8. 렌더링/통합 계획

- 포맷: GLB (glTF 2.0 binary)
- Android 렌더러 후보: Google Filament
- GLB 로더: gltfio-android
- 앱 연동: Kotlin, 필요 시 SurfaceView/ModelViewer 기반 MVP
- 버전은 Maven Central에서 실제 배포된 동일 버전으로 고정
- PLEOS 에뮬레이터/대상 기기의 GPU, 메모리, 프레임률 검증 필요
- 원본 제작물은 art/ 하위에서 관리하고 앱 배포 파일만 assets/models에 복사

## 9. 단계별 완료 조건

- [x] 털 색별 cat_<털>.glb 생성 (v0.2.0, 각 약 1.5MB, 삼각형 약 8만)
- [x] glTF 유효성 검사 통과 (Khronos glTF-Validator 2.0.0-dev.3.10: 4개 모두 오류 0, 경고 0)
- [x] Android/PLEOS에서 모델 정상 렌더링 (Pleos_Connect_v2.1.0 에뮬레이터, OpenGL, 투명 배경)
- [x] 치즈냥 재질과 표정 확인 (에뮬레이터 화면)
- [x] 색상 프리셋 최소 2개 전환 검증 (까망↔치즈 모델 교체)
- [~] 꼬리 애니메이션 재생 검증 (재생 코드는 동작, 움직임은 화면 녹화로 따로 확인 필요)
- [x] 소품 표시/숨김 검증 (모자·목도리)
- [x] 영수증 포즈 검증 (v0.1 모델로 영수증 화면 확인. v0.2 는 미리보기로만 확인)
- [~] 주행 상태별 캐릭터/렌더링 중단 검증 (정차 중 정지 그림은 몇 프레임 뒤 렌더링 멈춤 확인. 실제 주행 신호로는 미검증)
- [ ] 메모리 사용량, 프레임률, 앱 UX/심사 요건 검증

## 10. 변경 관리

- 모델 노드/머티리얼/애니메이션 이름 변경 시 본 명세와 앱 매핑 코드 동시 수정
- 호환성을 깨는 변경 시 모델 버전 및 변경 이유 기록
- 초기 단계의 미확정 항목은 실제 GLB 제작 결과를 보고 확정
