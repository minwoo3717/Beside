# exp-04 — 입력 3안(exp-03)과 텍스처 패딩(exp-04)의 A/B 비교, 베이스라인 품질(exp-02)을 한 세션으로

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-26 계획 작성. Pod 생성 2026-11-27, 사람 채점 2026-11-28 **[계획]** |
| 담당 | 3D(실행·지표), 팀 전원(채점, 평가자 2~3명) |
| 코드 커밋 | 도구 `2ebda9e`(브랜치 `w6/eval3d`). 실행 때 Beside·AnimalLift 커밋을 `env.json` 에서 옮겨 적는다 |
| 환경 | Runpod A40 Pod. 드라이버·CUDA·torch·입력 해상도(`ctrlSize`)는 실행 때 `env.json` 에서 채운다. 배치 1 |
| 입력 데이터 | 사진 12장, 개 6마리. CC0 3장(`backend/src/test/resources/photos/`: `dog.jpg` 블랙앤탄 하운드, `dog-single-black.jpg` 카렐리안 베어 독, `dog-single-edge.jpg` 크림색 개, 각 1장)과 팀 반려견 3마리 × 3장(`<개>_<n>.jpg`, 리포에 넣지 않음) |
| 상태 | **[계획]** — 도구만 [구현됨]([tools/README.md](../tools/README.md) 'A/B 평가 도구'). 실행 전 |

이 기록은 세 실험을 한 번의 생성·채점으로 묶는다. Pod 시간이 짧아(11/27 약 1~1.5 h 추가) 모델을 한 번 올려 모든 안을 만든다.

- **exp-02** 베이스라인 품질: 운영 설정(`remove`, pad 끔)의 6항목 점수와 시간·크기. PLAN 3단계의 "우리 사진 3마리 이상" 이다.
- **exp-03** 입력 3안: [2026-11-05 틀](2026-11-05_exp-03_input-preprocess.md) 을 이 세션에서 실행한다. 그 틀의 "서버를 세 번 띄워 e2e 로 올리기" 대신 `eval_arms.py` 를 쓴다. 입력은 서버와 같은 설정으로 `preprocessBatch` 가 만든다.
- **exp-04** 텍스처 패딩: `convert_asset.convert(padded_texture=True)` 를 `remove` 안에 켠다.

## 1. 실패 사례 (무엇이 안 되는가)

- 3D 품질 점수가 0건이고 개선 실험도 0건이다([METRICS](../../docs/METRICS.md) [계획] 줄, [PLAN](../../docs/PLAN.md) 3단계). 2026-11-25 멘토는 "3D 모델만 개선하고 숫자로 비교" 하라고 했다.
- 지금 아는 현상(관찰이며 수치가 아니다):
  - 입력 늘림: runner 는 사진을 `Resize((ctrl, ctrl))` 로 정사각형으로 늘린다([animallift_runner.py](../inference_service/animallift_runner.py)). 원본을 그대로 넣으면 가로 사진의 개가 눌린다. `model_in_before.png` 에서 볼 수 있다.
  - 초록 테두리: AnimalLift `uv.png` 의 섬 사이 배경은 순수 초록이다. 2026-11-26 도구 확인 중 2026-11-17(exp-01 날) 변환 GLB 1개를 채점 화면(model-viewer)으로 열었을 때 꼬리 끝에 초록 테두리가 보였다(1건 관찰, 채점 아님).
- baseline 행의 수치와 점수는 이 세션에서 처음 잰다(4.3 표).

## 2. 원인 가설

1. **가설 A — 늘림 (exp-03)**: `Resize((ctrl, ctrl))` 가 가로세로 비를 지키지 않아 체형 비율과 귀 위치가 틀어진다.
   - 근거: `animallift_runner.py` 의 `transforms.Resize((self.rt.ctrl_size, self.rt.ctrl_size))`. `PreprocessBatch.stretch` 가 같은 늘림을 `model_in_before.png` 로 재현한다.
   - 확인: `off` 의 `bodyProportion`·`ears` 가 `keep`·`remove` 보다 낮은지 본다.
2. **가설 B — 배경 번짐 (exp-03)**: 사진 배경 색이 털 텍스처로 번진다.
   - 근거: 2026-11-05 [일관성 조사](2026-11-05_animallift-consistency.md) 에서 배경 제거가 가장 일관적이었다(차이는 작음, AI 생성 사진).
   - 확인: `furColorDeltaE` 와 `furColor` 가 `off` → `keep` → `remove` 순으로 좋아지는지 본다.
3. **가설 C — 초록 번짐 (exp-04)**: 순수 초록 배경이 바이리니어·밉맵 필터링으로 섬 가장자리에 섞인다.
   - 근거: [convert_asset.py](../tools/convert_asset.py) `pad_texture` 의 설명.
   - 확인: pad 를 켜면 `seamGreenRatio@mip2` 와 `textureArtifacts` 가 좋아지는지 본다. pad 는 섬 밖만 바꾸므로 색·모양 지표는 그대로여야 한다.

## 3. 개선 (무엇을 바꾸는가)

| 안 | 입력 파일(preprocessBatch) | 텍스처 | 역할 |
|---|---|---|---|
| `remove` (baseline) | `cutout.png` 크롭+배경 제거 | pad 끔 | 운영 설정. exp-02 베이스라인, exp-03·04 의 기준 |
| `off` | `model_in_before.png` 사진 전체를 늘림 | pad 끔 | exp-03 |
| `keep` | `crop_pad.png` 크롭+패딩, 배경 유지 | pad 끔 | exp-03 |
| `remove+pad` | `cutout.png` | pad 켬(`padded_texture=True`) | exp-04 |

- `off+pad`·`keep+pad` 도 만들어지지만 채점하지 않는다(자동 지표만).
- 바꾸지 않는 것(통제 변수): 같은 12장, 같은 Pod·체크포인트(`modelVersion`), 시드 42(runner 가 predict 마다 다시 시드), `nfe`·`cfg_scale` 기본값, 털 맵 넣음, `forward_axis +x`, 같은 `preprocessBatch` 출력(사진 검사 기준값 그대로).
- 운영과 다른 점: 리깅을 끈다(정적 GLB 로 비교). 운영 서버를 거치지 않고 runner 를 직접 부른다.

## 4. 비교 실험

### 4.1 절차 [계획]

1. (PC, 11/26~27) 사진 12장 → `preprocessBatch`. `report.csv` 에서 12장이 모두 PASS 인지 본다. 거절된 사진은 같은 개의 다른 사진으로 바꾸고 여기에 적는다.
2. (Pod, 11/27) `eval_arms.py` 로 생성한다. 명령은 [tools/README.md](../tools/README.md) 1절이다. `runs.csv`·`env.json` 을 `2026-11-26_exp-04/` 에 복사한다(GLB·메시는 커밋하지 않는다).
3. (PC) `eval_metrics.py` → `summary.md`·`metrics.csv` 를 같은 폴더에 두고 4.3 표를 채운다.
4. (11/28) `eval_board.py build` → 평가자 2~3명이 따로 채점한다. 서로 상의하지 않고, `key.csv` 는 채점이 끝날 때까지 닫아 둔다. → `eval_board.py score`.
5. 렌더 캡처: 대표 사진 2장 × 4안을 나란히(`2026-11-26_exp-04/compare-*.png`, 가로 ≤ 1024 px).

### 4.2 미리 정한 주 지표와 판정

실행 전에 정한다. 결과를 본 뒤 바꾸지 않는다.

| 실험 | 비교 | 주 지표(사람) | 주 지표(자동) |
|---|---|---|---|
| exp-04 | `remove+pad` 대 `remove` | `textureArtifacts` | `seamGreenRatio@mip2` |
| exp-03 | `off`·`keep` 대 `remove` | `bodyProportion`, `ears` | `furColorDeltaE` |
| exp-02 | `remove` 만 | 6항목 평균 | `inferMs`·`gpuPeakMB`·`convertMs`·`outputTriangles`·`glbBytes` 의 중앙값과 범위 |

- 짝 비교는 사진마다 (안 − 기준)이다. 나아짐/같음/나빠짐 개수와 양측 부호 검정 p 를 적는다.
- "같음" 범위: 자동 지표는 ΔE 1.0, 색상각 2°, seam 비율 0.005, sameDogId 0. 사람 점수는 평가자 평균이 정확히 같을 때만 같음이다.
- 주 지표에서 p < 0.05 이면 "차이 있음" 으로 적는다. 아니면 방향과 개수만 적는다(예: 12장 중 8장 나아짐).
- 보조 지표(서술만): `furHueErrorDeg`, `seamGreenRatio@mip1·mip3`, `shapeDeltaPct`, `sameDogId`, 주 지표가 아닌 사람 항목. 여러 개를 보므로 p 는 참고로만 쓴다.
- 평가자 일치: 평가자 쌍마다 ±1 일치율과 이차 가중 kappa 를 적는다. kappa 가 0.4 미만이면 사람 점수 결론에 "평가자 간 차이가 큼" 을 붙인다.

### 4.3 결과 표 [계획 — 실행 전이라 비어 있음]

| 실험 | inferMs | gpuPeakMB | modelParams | outputVertices | outputTriangles | convertMs | faceShape | ears | bodyProportion | furColor | pattern | textureArtifacts | GLB bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline (`remove`) | | | | | | | | | | | | | |
| `off` | | | | | | | | | | | | | |
| `keep` | | | | | | | | | | | | | |
| `remove+pad` | | | | | | | | | | | | | |

| 실험 | furColorDeltaE | furHueErrorDeg | seamGreenRatio@mip2 | shapeDeltaPct | sameDogId (chance) |
|---|---|---|---|---|---|
| baseline (`remove`) | | | | 0 | |
| `off` | | | | | |
| `keep` | | | | | |
| `remove+pad` | | | | | |

렌더 비교: `2026-11-26_exp-04/compare-front.png`, `compare-side.png` (baseline | off | keep | remove+pad).

GLB_SPEC 검사: 변환기의 상한 검사(삼각형·텍스처·파일 크기)는 `eval_arms.py` 가 부르는 `convert()` 안에서 그대로 돈다. gltf-validator 는 이 세션 범위 밖이다 **[계획]**.

### 4.4 주장하지 않는 것

- **일반화**: 사진 12장, 개 6마리다. 다른 품종·고양이·다른 촬영 조건으로 넓히지 않는다.
- **유의성**: 주 지표의 p 가 0.05 이상이면 "좋아졌다" 고 쓰지 않는다. 방향과 개수만 쓴다.
- **3D 정확도**: 실제 개의 3D 정답 형상이 없다. `shapeDeltaPct` 는 기준 안과의 차이일 뿐 정확도가 아니다. 사람 점수도 사진과 얼마나 닮았는가이다.
- **앱의 털 모습**: 채점 화면(model-viewer)에는 앱의 셸 털이 없다. 앱에서 털 색·이음매가 어떻게 보이는지는 기기에서 따로 봐야 한다.
- **같은 개 식별**: `sameDogId` 는 텍스처 색 중앙값 하나로 가른 것이다. 모델이 개를 알아본다는 근거가 아니다. 사진이 1장인 CC0 개 3마리는 질문에서 빠지고 방해 후보로만 쓰인다.
- **속도**: `inferMs` 는 안끼리 같은지 보는 통제 확인용이다. 속도 개선은 주장하지 않는다.

## 5. 결론 · 다음 실험

- 실행 전이다. 채택 여부, 나빠진 지표, 설명되지 않는 현상은 실행 뒤에 적는다.
- 다음 실험 후보(가설 한 줄):
  - pad 가 채우지 않는 덜 초록인 가장자리 텍셀까지 채우면 seam 이 더 줄어든다. 도구 확인(리포 샘플 1장, 실험 아님)에서 pad 뒤에도 `seamGreenRatio@mip2` 가 0.011 로 남았다.
  - 여러 장 중 대표 사진 고르기(exp-03 틀의 후보).
