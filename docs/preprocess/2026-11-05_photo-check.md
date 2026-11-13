# 대표 사진 검사·전처리 첫 측정 — CC0 개 사진 6장 (2026-11-05)

| 항목 | 값 |
|---|---|
| 상태 | **[실험 결과]** — 개발 PC 에서 CC0 공개 사진 6장과 합성 변형으로 쟀다. 팀 사진도 VM 수치도 아니다 |
| 코드 | 브랜치 `feat/preprocess` `9ece9dc`(둘째 동물 기준 0.40 적용 후) |
| 환경 | AMD Ryzen 5 5600X(6코어 12스레드), Windows 11, Microsoft Build of OpenJDK 17.0.17, ONNX Runtime 1.31.0(CPU). 감지 스레드 2개, 배경 제거 스레드 1개. 측정 중 OP.GG·Riot 클라이언트가 떠 있었다(측정 전 6초 평균 CPU 4%) |
| 모델 | `yolox_s#c5c2d13e`(입력 640), `u2net#8d10d2f3`(입력 320) — [backend/models/README.md](../../backend/models/README.md) |
| 입력 | `backend/src/test/resources/photos/` 6장 — Wikimedia Commons CC0, 긴 변 1200px 이하([SOURCES.md](../../backend/src/test/resources/photos/SOURCES.md)) |
| 도구 | `.\gradlew.bat preprocessBatch --args="--in src/test/resources/photos --out C:\beside\pre-out-1105a"` 를 두 번 실행(a, b) |
| 원자료 | [data/2026-11-05_cc0/](data/2026-11-05_cc0/) — `run-a.csv`, `run-b.csv`, `settings.txt`(실제 설정·모델 해시) |

## 1. 실패 사례

2026-11-04 첫 실행에서 프렌치 불도그 두 마리 사진(`dogs-two-swimming.jpg`)이 **통과**했다. 발표에서 약속한 "2마리 이상이면 거절"과 어긋난다.

## 2. 원인 가설

둘째 개는 구명조끼를 입고 몸이 물에 잠겨 있어 감지 점수가 0.48 로 낮았다. 같은 자리를 YOLOX 는 `boat 0.59` 로도 봤다. 그런데 '둘째 동물' 기준(제안 0.50)이 첫째 기준(0.40)보다 높아서 한 마리로 셌다.

## 3. 바꾼 것

- `preprocess.check.second-animal-score` 를 0.50 에서 0.40 으로 내렸다. 첫째와 같은 기준이다.
  - 사용자 결정: 2026-11-05.
  - 계약 문서 `fa66821`, 코드 `ba57614`.
- 바꾸지 않은 것:
  - 넓이 조건: 둘째 박스가 첫째 박스의 15% 이상.
  - 나머지 기준값 6개.
  - 모델과 디코딩 크기.

## 4. 결과 — 판정

| 파일 | 사람 기대 | 0.50 (11-04) | 0.40 (11-05) | 주 동물 | 둘째·다른 물체 | 밝기 p90 | 선명도 |
|---|---|---|---|---|---|---|---|
| `dog.jpg` | 통과 | 통과 | 통과 | dog 0.93 | - | 166 | 835.2 |
| `dog-single-black.jpg` | 통과 (검은 털) | 통과 | 통과 | dog 0.92 | - | 186 | 1566.8 |
| `dog-single-edge.jpg` | 통과 (몸이 가장자리에서 잘림) | 통과 | 통과 | dog 0.69 | - | 160 | 1952.8 |
| `dogs-two-swimming.jpg` | `TOO_MANY_ANIMALS` | **통과** | `TOO_MANY_ANIMALS` | dog 0.71 | dog 0.48, boat 0.59 | - | - |
| `dog-blurry.jpg` | `IMAGE_TOO_BLURRY` | `IMAGE_TOO_BLURRY` | `IMAGE_TOO_BLURRY` | cat 0.75 (개를 고양이로 봄) | - | 170 | 11.1 |
| `no-animal-room.jpg` | `NO_ANIMAL_DETECTED` | `NO_ANIMAL_DETECTED` | `NO_ANIMAL_DETECTED` | 없음 | chair 0.92 | - | - |

0.40 에서는 6장 모두 사람의 기대와 같다. 두 번의 실행에서 판정과 점수가 똑같았다. 표의 `-` 는 그 앞 단계에서 판정이 나 재지 않은 값이다.

`dog.jpg` 를 바꿔 만든 변형도 확인했다(`PhotoCheckModelTest`).

| 변형 | 판정 |
|---|---|
| 밝기 ×0.15 | `IMAGE_TOO_DARK` |
| 긴 변 240px 로 축소 | `IMAGE_TOO_SMALL` |
| 800×600 사진 가운데에 200px 높이로 붙임 | `ANIMAL_TOO_SMALL` |
| 1600×1200 사진에 200px 높이로 붙임 | `NO_ANIMAL_DETECTED` (아래 한계 1) |
| 90° 돌려 저장하고 EXIF 6 을 붙임 | 통과 (서버가 똑바로 세운 뒤 검사) |

크롭은 `CutoutModelTest` 로 확인했다. 대상은 `dog.jpg`, `dog-single-black.jpg`, `dog-single-edge.jpg` 이고 모두 다음을 만족했다.

- `APPLIED`, 정사각형이고 네 모서리가 투명하다.
- 개가 한 변의 0.85±0.05 를 채우고, 가로·세로 모두 가운데에서 ±5% 안에 있다.
- 전경 비율은 0.36~0.53 이다.

## 5. 결과 — 시간 (이 PC)

| 실행 | 검사 (디코딩+감지+밝기·선명도) | 그중 감지 | 크롭+배경 제거 (다시 감지 포함) | 그중 배경 제거 |
|---|---|---|---|---|
| a | 중앙값 221 ms (198~227) | 198.5 ms | 1270 ms (1243~1372) | 1070 ms |
| b | 중앙값 212.5 ms (205~222) | 192 ms | 1264 ms (1248~1320) | 1063 ms |

- **모델 로딩과 워밍업**(서버 시작 때 한 번, `PreprocessModelsTest`, 2026-11-04): yolox_s 584 ms, u2net 1630 ms.
- **실행 사이 편차**: 2026-11-04 밤 같은 PC·같은 사진의 첫 실행에서는 감지가 약 130 ms, 검사가 약 140 ms 였다. 실행마다 50% 가까이 차이 나므로 이 숫자는 범위로만 쓴다.
- **뜻**:
  - 검사를 켜면 업로드 응답이 검사 시간만큼 늦어진다(이 PC 0.14~0.23 초). `uploadMs` 에는 들어가지 않고 `checkMs` 로 따로 남는다.
  - 크롭과 배경 제거는 202 뒤, 추론 슬롯을 잡기 전에 하므로 `queuedMs` 안에 들어간다(`preprocessMs`).
- VM(2 vCPU)에서의 시간과 메모리는 **[계획]** 이다.

## 6. 알게 된 한계

1. **작거나 먼 동물**: 1600×1200 사진에 200px 로 작게 찍힌 개는 감지 자체가 되지 않는다. 그래서 `ANIMAL_TOO_SMALL` 이 아니라 `NO_ANIMAL_DETECTED` 가 되고, 앱은 "반려동물이 잘 보이는 사진"을 고르라고 안내한다. `ANIMAL_TOO_SMALL` 은 감지는 되지만 원본에서 224px 보다 작은 경우에만 나온다.
2. **클래스 혼동**: 흐린 개를 `cat 0.75` 로 봤다. 개와 고양이를 함께 세므로 판정에는 영향이 없다.
3. **표본이 작다**: 사진 6장, 그것도 개 사진만이라 잘못 거절·잘못 통과의 비율은 말할 수 없다. 둘째 기준을 0.40 으로 낮춘 대가는 아직 재지 않았다(장난감, 액자 속 동물을 둘째로 셀 위험).
4. **조건이 다르다**: CC0 사진은 실제 업로드와 다르다. 앱은 휴대폰 사진을 JPEG 긴 변 1600 으로 올린다.

## 7. 다음 — [계획]

- 팀 사진으로 같은 도구를 다시 돌린다. 고양이, 실내, 어두운 곳, 여러 마리가 함께 찍힌 사진을 넣고, 기준값 조정의 근거로 쓴다. 회의 안건 #19 와 연결된다.
- VM(2 vCPU)에서 `checkMs`·`preprocessMs`·`docker stats` 를 잰다.
- 3D 품질 비교(원본 / 크롭 / 크롭+배경 제거) — `generation/experiments` 의 틀.

발표 슬라이드용 전/후 이미지는 같은 명령으로 다시 만든다. 출력 폴더의 `index.html` 에서 원본, 감지, 지금 입력(늘림), 크롭+패딩, 배경 제거, 새 입력을 나란히 볼 수 있다. CC0 사진이라 출처 표기 의무는 없지만, 출처는 SOURCES.md 에 있다.
