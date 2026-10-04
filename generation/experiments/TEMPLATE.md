# exp-NN — <주제 한 줄>

| 항목 | 값 |
|---|---|
| 날짜 | YYYY-MM-DD |
| 담당 | |
| 코드 커밋 | `git rev-parse --short HEAD` |
| 환경 | GPU 모델 / 드라이버·CUDA / torch 버전 / 입력 사진 수·해상도 / 배치 크기 |
| 입력 데이터 | 사진 출처·마리 수·품종 (예: 말티즈 1마리 3장, 믹스견 1마리 5장) |
| 상태 | [실험 결과] 또는 [진행 중] |

## 1. 실패 사례 (무엇이 안 되는가)

- 현상을 구체적으로 적는다. 예: "정면 사진만 넣으면 귀가 머리 뒤로 붙는다", "흰 털에서 텍스처 seam 이 등줄기에 보인다".
- 재현 조건과 빈도(N회 중 M회). 렌더 캡처: `YYYY-MM-DD_exp-NN/fail-01.png`
- 베이스라인 수치와 품질 점수(아래 표의 "baseline" 행).

## 2. 원인 가설

1. 가설 A — 근거(논문 절, 코드 위치, 관찰).
2. 가설 B — 근거.
3. 각 가설을 확인할 방법(어떤 변수를 바꾸면 무엇이 달라져야 하는가).

## 3. 개선 (무엇을 바꿨는가)

- 변경 1: 파라미터/코드/전처리 — 어디를(파일·함수) 어떻게.
- 변경 2: …
- 변경하지 않은 것(통제 변수)을 명시한다.

## 4. 비교 실험

같은 입력·같은 환경에서 baseline 과 개선안을 나란히 측정한다. 품질은 평가자 2명 이상의 평균(1~5).

| 실험 | inferMs | gpuPeakMB | modelParams | outputVertices | outputTriangles | convertMs | faceShape | ears | bodyProportion | furColor | pattern | textureArtifacts | GLB bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline | | | | | | | | | | | | | |
| 개선안 A | | | | | | | | | | | | | |
| 개선안 B | | | | | | | | | | | | | |

렌더 비교: `YYYY-MM-DD_exp-NN/compare-front.png`, `compare-side.png`, `compare-top.png` (baseline | A | B 나란히)

GLB_SPEC 검사: gltf-validator 오류 수 / 삼각형 상한 / 텍스처 크기 / 파일 크기 — 통과 여부.

## 5. 결론 · 다음 실험

- 채택 여부와 이유(어떤 지표가 얼마나 좋아졌고 무엇이 나빠졌는가).
- 아직 설명되지 않는 현상.
- 다음 실험(exp-NN+1)의 가설 한 줄.
