# tools — 에셋 변환 도구 (3D Generation 트랙)

상태: `convert_asset.py`·`rig_transfer.py` 는 #1(2026-10-14)로 머지됐고 추론 서비스(`inference_service/app.py`)가 작업마다 부른다. 이 폴더의 시험 파일은 3D A/B 평가 도구의 `test_eval3d.py`(아래 'A/B 평가 도구' 절) 하나뿐이고, 두 도구의 시험은 모두 [`inference_service/test_app.py`](../inference_service/test_app.py) 에 있다(numpy·trimesh·pillow 가 없으면 건너뜀). 털 맵(2026-11-21, PR #25): `FurMapsInGlbTest` 가 `convert()` 를 직접 돌려 앱과 같은 방식으로 GLB 안 털 맵을 읽고(`hair.npz` 없음·읽지 못함·해상도 상한 포함), `RigTransferFurMapsTest` 가 `transfer()` 가 털 맵을 옮기고 템플릿 것은 버리는지 본다. 추론 서비스 시험 `RealConversionCancelTest`(2026-11-19)가 합성 출력으로 `convert()` 를 실제로 돌려 쓰기 위치와 `make_dirs=False`(출력 폴더를 만들지 않고, 없으면 `ConversionError`. 추론 서비스가 쓴다. CLI 는 지금처럼 만든다)를 본다. `rig_transfer.py` 의 정점 순서 확인은 `RigTransferVertexOrderTest`(합성 GLB 로 `transfer()`·CLI 종료 코드 2), 리깅 결과의 크기 상한·`base.metrics.json` 다시 쓰기는 `RiggedResultTest`·`OversizeRiggedResultTest`(가짜 `rig_transfer`)가 본다(2026-11-20). 브랜치 `Unity`(2d2e235)에서 옮긴 선택 기능(2026-11-21)은 `PadTextureTest`·`HairMetaTest`(변환기), `ConverterSwitchTest`·`PaddedTextureRigTest`·`RealEmbedHairOffTest`(추론 서비스)가 본다. convertMs·삼각형 수는 [exp-01](../experiments/2026-11-17_exp-01_baseline-runpod.md) 에 있다 [실험 결과]. gltf-validator 결과는 **[계획]** (exp-02).

의존성: [requirements.txt](requirements.txt) (numpy, pillow, trimesh, scipy). 추론 서비스와 같은 env 에 설치한다.

## `convert_asset.py` — AnimalLift 출력 → GLB

```bash
python tools/convert_asset.py \
  --obj work/<jobId>/raw/mesh.obj --texture work/<jobId>/raw/uv.png [--hair work/<jobId>/raw/hair.npz] \
  --out work/<jobId>/base.glb --metrics work/<jobId>/base.metrics.json \
  [--forward-axis +x] [--normalize-longest-axis 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760] \
  [--hair-meta <AnimalLift>/assets/hair_map_meta_uvanchor.npz] [--pad-texture]
```

- GLB_SPEC §1~§4 를 적용한다: 텍스처 임베드, 노드 하나(`base`), Y-up, 1 unit = 1 m, 바운딩 박스 최장 축 1.0 m, 최저점 y = 0·x/z 가운데, 정면 +Z(AnimalLift 템플릿은 머리가 +X 라 기본 `--forward-axis +x`), metallic 0·roughness 0.9.
- 상한(삼각형·텍스처·파일 크기)이나 UV 없음 등 위반은 종료 코드 2 와 `CONVERSION_FAILED: 이유` → 추론 서비스가 `CONVERSION_FAILED` 로 답한다.
- 표준 출력과 `--metrics` 파일: `{ "vertices", "triangles", "textureSize", "bytes", "convertMs" }` (이름 고정, [docs/METRICS.md](../../docs/METRICS.md)).
- `--hair` 를 주면 앱 셸 털용 맵 두 장(길이·방향)을 GLB 안에 넣고 루트 `extras.beside` 에 이미지 번호와 `furLength` 를 적는다([GLB_SPEC §4.1](../../docs/asset/GLB_SPEC.md)). 앱(`RuntimeModelLoader`)은 이 자리에서 맵을 읽는다. 같은 맵을 GLB 옆에도 `hair_length.png`·`hair_flow.png`·`hair_maps.json` 로 쓰지만 확인용이다. `hair.npz` 를 못 읽거나 맵이 텍스처 상한보다 크면 `CONVERSION_FAILED` 다. 2026-11-21 전에는 GLB 옆 PNG 로만 써서 서버 모델에 털이 그려지지 않았다(안건 [#14](../../docs/api/REVIEW_CHECKLIST.md)).
- 선택 기능 두 가지(브랜치 `Unity` 2d2e235 에서 2026-11-21 옮김, **기본 꺼짐**, scipy 필요). 끈 상태의 출력은 옮기기 전과 바이트 단위로 같다(리포 샘플 `n02085782_186` 로컬 변환으로 확인). 켜면 GLB_SPEC §4(리깅본 텍스처는 `uv.png` 원본 바이트)·§4.1(`furLength` = `hair.npz` 전체의 99.5 백분위수)의 서술과 달라지므로, Pod 에서 켜기 전에 계약에 적는 결정이 필요하다 **[계획]**:
  - `--hair-meta`(추론 서비스 `BESIDE_HAIR_META`): AnimalLift 는 가이드 텍셀(약 21%)에서만 털을 학습하므로 그 텍셀만으로 털 맵을 다시 채우고(가우시안 정규화 합성곱), 가이드가 닿지 않는 곳은 0, 메타 파일 옆에 `base_texture.png` 가 있으면 코·발바닥 털을 없앤다(털 없는 곳 쪽으로 4 texel 에 걸쳐 짧아짐), UV 섬 밖 3 texel 을 채운다. 그때 `furLength` 는 털이 있는 texel 의 99.5 백분위수이고, GLB 옆에 `hair_region.png`(texel 마다 가까운 가이드 그룹 번호, 255 = 털 없음)를 쓴다. 메타 파일을 읽지 못하거나 격자가 다르면 경고 뒤 원래 맵을 쓴다. 키·이미지 이름·결 맵 G 부호는 그대로다.
  - `--pad-texture`(추론 서비스 `BESIDE_PAD_TEXTURE=1`): `uv.png` 의 순수 초록 배경을 가장 가까운 UV 섬 색으로 채워(귀·이음새의 초록 번짐 방지) GLB 의 base color 로 넣고 `texture_padded.png` 로도 쓴다. 추론 서비스는 리깅에 그 파일을 넘긴다. 로컬 확인 1건(실험 아님): 리포 샘플 GLB 2,120,640 B → 1,862,340 B.
- 옮기지 않은 것: 브랜치의 `embed_hair.py`(리깅 뒤 털 맵을 한 번 더 넣는 두 번째 쓰기, 이미지 이름이 달라 main GLB 에 고아 이미지가 남음)와 TANGENT 추가·결 맵 G 부호 뒤집기 제거. 둘은 GLB_SPEC §4.1 의 뜻을 바꾸므로 계약 결정과 기기 나란히 비교가 먼저다(셸 털 v2 는 TANGENT 가 없으면 털 결을 끈다, [unity/docs/FUR_RENDERING.md](../../unity/docs/FUR_RENDERING.md)).

## `rig_transfer.py` — 템플릿의 뼈대·애니메이션 이식

```bash
python tools/rig_transfer.py --base work/<jobId>/base.glb --texture work/<jobId>/raw/uv.png \
  --template <리포 밖>/template-rigged.glb --out work/<jobId>/base.glb
# --pad-texture 로 변환했다면 --texture work/<jobId>/texture_padded.png
```

- AnimalLift 출력은 모두 같은 템플릿 위상(SMAL 계열, 정점 순서 동일)이라, 템플릿의 스킨 가중치·삼각형·UV·애니메이션 클립을 그대로 쓰고 정점 위치·법선만 새 모델 것으로 바꾼다. 관절 위치는 근처 정점으로 다시 맞추고, 루트 이동 키는 골반 높이 비율로 늘이거나 줄인다.
- 템플릿 GLB 는 SMAL 파생(학술 라이선스)이라 git 에 넣지 않는다. 추론 서비스는 환경변수 `BESIDE_RIG_TEMPLATE` 이 있을 때만 리깅한다(`scripts/start_inference_wsl.sh` 는 `~/beside-rig/template-rigged.glb` 가 있으면 켠다). 템플릿을 만든 스크립트는 이 리포에 없다.
- 표준 출력: `{ "joints", "fitErrorM", "heightRatio", "clips", "bytes" }`. 위상이 다르면 종료 코드 2(`RIG_FAILED`) — 추론 서비스는 그때 리깅 없는 GLB 를 그대로 돌려준다. 위상 확인은 정점 수·삼각형 수, 그리고 삼각형의 정점 번호다(2026-11-20): 수가 같아도 정점 순서가 다르면 삼각형의 정점 번호가 달라 거절한다. 면 순서·시작 꼭짓점은 따지지 않고 UV 값은 비교하지 않는다.
- 추론 서비스는 리깅한 GLB 도 GLB_SPEC §1 크기 상한(10 MB, `convert_asset.MAX_GLB_BYTES`)으로 다시 본다. 넘으면 리깅 없는 GLB 를 돌려준다(2026-11-20). 리깅한 GLB 를 쓰면 `base.metrics.json` 의 `bytes`·`convertMs` 를 그 파일 기준으로 다시 쓴다. 리깅 시간은 `convertMs` 에 더해진다(2026-11-21 안건 #16 으로 METRICS 의 정의도 그렇게 고쳤다: 리깅이 성공한 실행의 `convertMs` 는 변환 + 리깅).
- 털 맵: 리깅본은 템플릿 JSON 으로 다시 만들어지므로, `base.glb` 의 `extras.beside` 와 털 맵 이미지 두 장을 그대로 옮긴다. 템플릿에 있던 `extras.beside`(다른 동물의 털)는 버린다([GLB_SPEC §4.1](../../docs/asset/GLB_SPEC.md)).

## `reorder_rig_template.py` — 템플릿 정점 번호를 변환기 출력에 맞추기 (한 번, 리포 밖 파일)

```bash
python tools/reorder_rig_template.py --template <리포 밖>/animallift_template_rigged.glb \
  --reference work/<jobId>/base.glb --out <리포 밖>/template-rigged.glb
```

- 왜: 리포 밖 `rigging/tools/rig_animallift.py` 가 만든 템플릿은 UV 이음매 정점을 `np.unique` 순서로 번호 매기고, `convert_asset.py`(trimesh)는 다른 순서로 매긴다. 메시는 같아서(면 순서·UV 같음) 위의 정점 순서 확인(2026-11-20)만 걸린다. 그래서 2026-11-21 까지 이 템플릿을 올려도 모든 작업이 `vertex order differs` 로 정적 GLB 가 되었다.
- 무엇을: `--reference`(변환기가 쓴 아무 `base.glb`)와 면 순서대로 꼭짓점을 맞춰 정점 번호표를 구하고, 템플릿의 정점별 값(위치·법선·UV·`JOINTS_0`·`WEIGHTS_0`·모프)과 삼각형 번호를 그 번호로 다시 쓴다. 뼈대·클립·머티리얼은 그대로다. UV 가 1e-4 넘게 다르거나 면 순서가 다르면 종료 코드 2(`REORDER_FAILED`)이고 아무것도 쓰지 않는다.
- 2026-11-21 실제 템플릿(4310 정점)에서 417개 정점 번호가 바뀌었고, 바꾼 템플릿으로 그날 VM 결과 3건이 모두 리깅되었다(뼈 36개, 클립 5개, 털 맵 유지, 1.76~1.78 MB — `--texture` 없이 잰 값). 시험: `ReorderRigTemplateTest`(합성 피라미드).
- 앱(`unity/Assets/Beside/Pet/PetAnimationPlayer.cs`)은 GLB 안의 클립 `Idle`·`Walk`·`Sit`·`TailWag`·`HeadTilt` 를 칩으로 보여 준다.

## 3D A/B 평가 도구 — `eval_arms.py`·`eval_metrics.py`·`eval_board.py` (2026-11-26)

상태: 코드와 시험은 **[구현됨]**(`test_eval3d.py` 24개, torch·GPU 없이 돈다). Pod 실행(11/27)과 사람 채점(11/28)은 **[계획]**. 실험 계획은 [exp-04 기록](../experiments/2026-11-26_exp-04_ab-input-texture.md)이다.

흐름: PC `preprocessBatch` → Pod `eval_arms.py` → PC `eval_metrics.py` → PC `eval_board.py build` → 평가자 → `eval_board.py score`.

### 0. 입력 준비 (PC)

- 사진 12장을 한 폴더에 모은다. CC0 3장(`backend/src/test/resources/photos/` 의 `dog.jpg`·`dog-single-black.jpg`·`dog-single-edge.jpg`)과 팀 개 3마리 × 3장이다.
- 팀 사진 이름은 `<개>_<n>.jpg` 로 한다(예 `coco_1.jpg`). `_<n>` 앞이 개 이름이 되고 `sameDogId` 가 그것을 쓴다.
- 백엔드 폴더 일괄 도구로 세 입력을 만든다: `.\gradlew.bat preprocessBatch --args="--in <사진 폴더> --out <pre 폴더>"`([backend/README.md](../../backend/README.md)). 사진마다 `NN_<이름>/` 에 `model_in_before.png`·`crop_pad.png`·`cutout.png`·`original.jpg` 가 생기고, 맨 위에 `report.csv` 가 생긴다.
- 지금 코드로 다시 만든다. 2026-11-04 출력(`C:\beside\pre-out`)은 둘째 동물 기준(0.40)을 바꾸기 전이라 판정이 다르다.
- `<pre 폴더>` 를 Pod 의 `/workspace/eval/pre` 로 올린다(scp 또는 `runpodctl send`).

### 1. `eval_arms.py` — Pod 에서 생성 (GPU)

```bash
cd /workspace/Beside   # 이 도구가 든 커밋으로 맞춘 뒤
PY=/workspace/miniconda3/envs/animallift/bin/python   # deploy/runpod/install_animallift.sh 의 conda env
$PY generation/tools/eval_arms.py --photos-dir /workspace/eval/pre --out /workspace/eval/out --list   # 목록만 확인
$PY generation/tools/eval_arms.py --photos-dir /workspace/eval/pre --out /workspace/eval/out --model-dir /workspace/AnimalLift
```

- 입력 안 세 가지: `off` = `model_in_before.png`(사진 전체를 정사각형으로 늘림), `keep` = `crop_pad.png`(크롭+패딩, 배경 유지), `remove` = `cutout.png`(크롭+배경 제거, 운영 입력).
- 모델은 한 번만 올린다. 사진 × 안마다 predict 를 한 번 한다(시드 42, runner 가 predict 마다 다시 시드). 재기 전에 버리는 predict 를 한 번 한다(warm-up, `--no-warmup` 으로 끔).
- 변환은 두 번 한다. pad 끔 → `<out>/<안>/<사진>/base.glb`, pad 켬 → `<out>/<안>+pad/<사진>/base.glb`. 리깅은 끄고, 털 맵은 운영처럼 넣는다.
- `<out>/runs.csv`: `photo, dog, arm, pad, inferMs, gpuPeakMB, convertMs, outputVertices, outputTriangles, textureSize, glbBytes` 뒤에 `variant, modelParams, modelVersion, input, error`. pad 두 행은 같은 predict 라 `inferMs` 가 같다.
- `<out>/env.json`: GPU·드라이버·torch·CUDA·`modelVersion`·`ctrlSize`·`nfe`·시드·Beside 커밋·모델 로딩 시간. 실험 기록의 환경 칸에 옮긴다.
- 끊기면 같은 명령을 다시 실행한다. `predict.json` 이 있는 것은 건너뛴다(`--force` 로 다시 함).
- `report.csv` 에서 사진 검사에 거절된 사진은 뺀다(`--include-refused` 로 넣음). 입력 파일이 없는 안(전처리 폴백)은 `error` 칸에 적고 넘어간다.
- 재는 동안 같은 Pod 의 추론 서비스에 작업을 보내지 않는다. GPU 를 나눠 쓰면 `inferMs` 가 늘어난다.
- 걸리는 시간은 아직 재지 않았다. exp-01 의 inferMs(3.9~5.7 s)·convertMs(약 1 s)로 어림하면 predict 36번과 변환 72번에 5분 안팎이다(추정).
- 결과를 PC 로 가져온다: `tar czf /workspace/eval/out.tgz -C /workspace/eval out`.

### 2. `eval_metrics.py` — PC 에서 자동 지표 (numpy·Pillow)

```powershell
python -I generation/tools/eval_metrics.py --eval-dir <out> --photos-dir <pre 폴더>
```

- 출력은 `<out>/metrics/` 의 `metrics.csv`(사진 × 안 한 행), `summary.csv`, `summary.md`(안별 평균, 기준 `remove` 대비 짝 비교)다.

| 지표 | 무엇 | 좋은 쪽 |
|---|---|---|
| `furColorDeltaE` | 사진 전경(`cutout.png` 알파 > 0.5, 3 px 깎음)의 Lab 중앙값과 텍스처 UV 섬 텍셀의 Lab 중앙값 사이 CIEDE2000. 섬 = 순수 초록 배경이 아닌 곳(`convert_asset.pad_texture` 와 같은 판정, pad 전 `uv.png` 로 정함) | 낮음 |
| `furHueErrorDeg` | 두 중앙값의 색상각 atan2(b, a) 차이(0~180°). 회색에 가까우면 흔들린다 | 낮음 |
| `seamGreenRatio@mip1/2/3` | GLB 에 실제로 든 텍스처(+pad 안은 pad 된 것)를 2·4·8배 박스 필터로 줄인 뒤, 섬 텍셀(덮임 ≥ 0.5) 중 배경에서 4 텍셀 안인 것에서 G − max(R, B) > 0.15 인 비율. exp-04 주 지표 | 낮음 |
| `shapeDeltaPct` | 같은 사진의 `remove` 안 `mesh.obj` 와 정점별 거리 평균 ÷ 그 바운딩 박스 대각선(%). AnimalLift 출력은 위상이 같아 정점 번호가 맞는다(리포 샘플 `mesh.obj` 는 `v` 3,889줄, GLB 는 UV 이음매에서 나뉘어 4,310) | 차이일 뿐 |
| `sameDogId` | 같은 안 안에서 텍스처 Lab 중앙값의 최근접 이웃(한 장 빼기)이 같은 개면 1. 사진이 한 장뿐인 개는 묻지 않는다. `chance` = 1 / 개 수, `chanceNN` = 무작위 이웃의 정확한 확률 | 높음 |

- 짝 비교는 사진마다 (안 − remove) 다. "같음" 범위는 미리 정했다: ΔE 1.0, 색상각 2°, seam 비율 0.005, sameDogId 0. 부호 검정은 양측이고 같음은 뺀다.
- CIEDE2000·sRGB→Lab 은 직접 구현했고 Sharma(2005) 표 15쌍으로 시험한다.
- 도구 확인(실험 결과 아님): 리포 샘플 `unity/Assets/Models/n02085782_186 (1)_uv.png`(1024²) 를 손으로 만든 GLB 에 넣어 돌렸다. `seamGreenRatio@mip2` 는 pad 전 0.064, `pad_texture` 와 같은 방식으로 채운 뒤 0.011 이었다. pad 뒤에도 0 이 아닌 까닭은, pad 가 엄격한 초록 판정에 걸리는 텍셀만 채우고 섬 가장자리의 덜 초록인 텍셀은 남기기 때문으로 보인다(사진 1장).

### 3. `eval_board.py` — 블라인드 채점

```powershell
python -I generation/tools/eval_board.py build --eval-dir <out> --photos-dir <pre 폴더> --out <rating 폴더>
python -m http.server 8000 --directory <rating 폴더>/board     # 브라우저로 http://localhost:8000/
python -I generation/tools/eval_board.py score --key <rating 폴더>/_private/key.csv --ratings kim.csv lee.csv park.csv
```

- 기본 안은 `off, keep, remove, remove+pad` 이다(12장이면 48개). 코드 A01.. 의 순서는 시드 20261128 로 섞는다. 같은 입력·시드면 어느 PC 에서도 같다.
- 평가자에게는 `board/` 만 준다: `A01.glb`.., `photos/P01.jpg`(입력 사진, 긴 변 ≤ 1024 px, EXIF 없음), `index.html`, `ratings_template.csv`.
- `_private/key.csv`(코드 → 사진·개·안)는 채점이 끝날 때까지 열지 않는다. build 가 경고를 출력한다.
- `index.html` 은 model-viewer 3.5.0 을 jsDelivr 에서 받는다(인터넷 필요). `file://` 로 열면 모델이 뜨지 않으므로 `http.server` 로 연다.
- 웹 뷰어에는 앱의 셸 털이 없다. 털 색·무늬는 텍스처로만 본다.
- 평가자는 `ratings_template.csv` 를 복사해 `rater` 칸에 이름을 쓰고, 6항목(`faceShape, ears, bodyProportion, furColor, pattern, textureArtifacts`, [METRICS §4](../../docs/METRICS.md))을 1~5 정수로 채운다. 빈 칸은 건너뛴다. 범위 밖 값은 파일과 줄을 알려 주고 멈춘다.
- `score` 출력(기본 `_private/scores/`): `scores_by_arm.csv`, `scores_paired.csv`, `agreement.csv`, `scores_summary.md`. 안 이름이 드러나므로 `_private` 에 둔다.
- 점수는 코드마다 평가자 평균을 낸 뒤 안별로 평균한다. 평가자 일치는 평가자 쌍마다 정확히 같은 비율, ±1 안 비율, 이차 가중 kappa 다.
- 로컬 확인: 실제 변환 GLB 1개를 넣은 보드를 `http.server` 로 열어 사진과 모델이 함께 뜨는 것을 봤다(2026-11-26).

### 시험

```powershell
python -I -m unittest discover -s generation/tools -p "test_eval3d.py"
```

- 24개다. torch·GPU·trimesh 없이 돈다. numpy 가 필요하고, Pillow 가 없으면 이미지를 쓰는 5개를 건너뛴다.

## 검증 ([계획])

- `gltf-validator` 오류 0 (`npm i -g gltf-validator` 또는 Khronos 바이너리), Unity 에디터 glTFast 로드 — 결과를 실험 기록에 남긴다.
- 변환 결과 샘플은 커밋하지 않는다. Mock 서버용 `backend/storage/results/sample-dog.glb` 는 아직 0바이트다(유효 GLB 로 교체는 직접 할 일).
