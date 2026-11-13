# exp-03 — 입력 전처리(크롭·배경 제거·정사각 패딩)가 3D 품질에 주는 영향

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-05 틀 작성. 실행 날짜는 아직 없음 |
| 담당 | Backend(전처리·실행 준비), 3D(실행·채점) — 번호 exp-03 은 3D 담당과 확인한다(PLAN 의 개선 실험 후보) |
| 코드 커밋 | 실행할 때 `git rev-parse --short HEAD` 를 적는다. 전처리는 브랜치 `feat/preprocess` |
| 환경 | GPU 모델 / 드라이버·CUDA / torch 버전 / AnimalLift `ctrl_size` / 입력 사진 수·해상도 — 실행할 때 채운다 |
| 입력 데이터 | 팀 사진(개·고양이, 마리별 3장 이상)을 쓴다. 시험용 CC0 사진 6장은 `backend/src/test/resources/photos/` 에 있다 |
| 상태 | **[계획]** — 실행 전 |

## 1. 실패 사례 (무엇이 안 되는가)

- 지금 AnimalLift 입력(`animallift_runner.py`)은 사진 전체를 `Resize((ctrl, ctrl))` 로 정사각형으로 **늘린다**. 그래서 가로 사진의 개는 옆으로 눌리고, 배경이 입력의 대부분을 차지한다.
- 늘린 모습은 폴더 일괄 도구가 만드는 `model_in_before.png` 에서 볼 수 있다(백엔드 [README](../../backend/README.md) '대표 사진 검사·전처리').
- 3D 결과에서 체형 비율·귀 위치가 어떻게 틀어지는지는 아직 재지 않았다. 이 실험에서 baseline 행으로 잰다.

## 2. 원인 가설

1. **가설 A — 늘림**: 비정사각 사진을 늘리면 체형 비율이 바뀐다. `bodyProportion`·`ears` 점수가 떨어진다.
   - 확인: 크롭+정사각 패딩(배경 유지)만 해도 그 점수가 오르는지 본다.
2. **가설 B — 배경**: 배경과 털 색이 섞이면 텍스처에 배경이 번진다. 흰 배경에서 학습한 입력 형식과도 다르다.
   - 확인: 배경까지 지우면 `furColor`·`textureArtifacts` 가 더 좋아지는지 본다.

## 3. 개선 (무엇을 바꿨는가) — Spring 쪽 [구현됨]

- **변경 1 — 크롭+패딩**: 동물을 찾아(YOLOX-S) 박스에 여백 15% 를 두고 자른다. 정사각 캔버스 가운데에 동물이 한 변의 85% 를 차지하게 놓고, 빈 곳은 흰색으로 둔다.
  - 서버 설정: `--preprocess.cutout.background=keep`
  - `preprocessVersion` 에 `bg=keep` 이 남는다.
- **변경 2 — 크롭+배경 제거**: 변경 1 에 U2Net 으로 배경을 지운 것이다. 투명한 곳은 Python `load_rgb` 가 흰색으로 합성한다.
  - 서버 기본값이다.
  - `preprocessVersion` 에 `bg=remove` 가 남는다.
- **baseline — 원본**: 서버 설정 `--preprocess.cutout.enabled=false` 로 원본 사진을 그대로 보낸다. `preprocessResult=OFF` 가 남는다.
- **변경하지 않은 것(통제 변수)**:
  - AnimalLift 모델·체크포인트(`modelVersion` 이 같아야 한다)와 시드.
  - 리깅 설정(`RIG_TEMPLATE` 을 같게).
  - 같은 사진 목록과 같은 GPU PC.
  - 대표 사진 검사 기준값(실험 중에는 바꾸지 않는다).

## 4. 비교 실험

같은 입력·같은 환경에서 baseline 과 개선안을 나란히 측정한다. 품질은 평가자 2명 이상이 1~5점으로 매긴 평균이다.

**절차**:

1. 사진 목록을 정하고, 폴더 일괄 도구로 세 입력을 미리 본다(`index.html`, `report.csv` 의 판정 확인). 검사에서 거절되는 사진은 목록에서 뺀다.
2. GPU PC 에서 real 서버를 세 번 띄운다. 띄울 때마다 baseline, 변경 1, 변경 2 의 설정을 하나씩 쓴다.
3. 띄울 때마다 같은 사진을 같은 순서로 `e2e_mock.ps1 -Photos ... -TimeoutSeconds 600` 으로 올린다.
4. `job_runs` 를 METRICS §1.1 의 SQL 로 내보낸다. `preprocess_result`·`preprocess_version` 으로 세 묶음을 나누고, `FALLBACK:*` 행은 원본이 들어간 것이므로 개선안 쪽에서 뺀다.
5. 같은 각도에서 렌더를 찍고, 평가자는 어느 안인지 모르게 채점한다.

| 실험 | 입력 | inferMs | gpuPeakMB | outputVertices | outputTriangles | convertMs | faceShape | ears | bodyProportion | furColor | pattern | textureArtifacts | GLB bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline | 원본(늘림) | | | | | | | | | | | | |
| 개선안 A | 크롭+패딩(`bg=keep`) | | | | | | | | | | | | |
| 개선안 B | 크롭+배경 제거(`bg=remove`) | | | | | | | | | | | | |

전처리 쪽 시간(`preprocessMs`, 개발 PC 약 1.2~1.4 초)은 [docs/preprocess/2026-11-05_photo-check.md](../../docs/preprocess/2026-11-05_photo-check.md) 를 본다. 실험에서는 GPU PC 의 값을 다시 적는다.

렌더 비교 이미지는 `2026-11-05_exp-03/compare-front.png`, `compare-side.png` 에 둔다(baseline | A | B 나란히).

## 5. 결론 · 다음 실험

- 실행 전이다. 채택 여부, 나빠진 지표, 설명되지 않는 현상은 실행 뒤에 적는다.
- 다음 실험 후보(가설 한 줄):
  - 여러 장 중 가장 좋은 사진을 고르기. 검사 점수(밝기·선명도·동물 크기)로 대표 사진을 자동으로 고른다.
