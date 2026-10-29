# tools — 에셋 변환 도구 (3D Generation 트랙)

상태: `convert_asset.py`·`rig_transfer.py` 는 #1(2026-10-14)로 머지됐고 추론 서비스(`inference_service/app.py`)가 작업마다 부른다. 이 리포에는 이 도구의 자동 테스트가 없고, 실제 사진으로 만든 GLB 의 측정 기록(convertMs·삼각형 수·gltf-validator 결과)도 아직 없다 — **[계획]** (exp-01·02).

의존성: [requirements.txt](requirements.txt) (numpy, pillow, trimesh, scipy). 추론 서비스와 같은 env 에 설치한다.

## `convert_asset.py` — AnimalLift 출력 → GLB

```bash
python tools/convert_asset.py \
  --obj work/<jobId>/raw/mesh.obj --texture work/<jobId>/raw/uv.png [--hair work/<jobId>/raw/hair.npz] \
  --out work/<jobId>/base.glb --metrics work/<jobId>/base.metrics.json \
  [--forward-axis +x] [--normalize-longest-axis 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760]
```

- GLB_SPEC §1~§4 를 적용한다: 텍스처 임베드, 노드 하나(`base`), Y-up, 1 unit = 1 m, 바운딩 박스 최장 축 1.0 m, 최저점 y = 0·x/z 가운데, 정면 +Z(AnimalLift 템플릿은 머리가 +X 라 기본 `--forward-axis +x`), metallic 0·roughness 0.9.
- 상한(삼각형·텍스처·파일 크기)이나 UV 없음 등 위반은 종료 코드 2 와 `CONVERSION_FAILED: 이유` → 추론 서비스가 `CONVERSION_FAILED` 로 답한다.
- 표준 출력과 `--metrics` 파일: `{ "vertices", "triangles", "textureSize", "bytes", "convertMs" }` (이름 고정, [docs/METRICS.md](../../docs/METRICS.md)).
- `--hair` 를 주면 GLB 옆에 `hair_length.png`·`hair_flow.png`·`hair_maps.json`(앱 셸 털용 맵)을 따로 쓴다. GLB 안에는 넣지 않으므로 지금 앱은 서버에서 만든 모델에 털을 그리지 못한다(앱은 GLB 안 `extras.beside` 의 맵을 기대) — GLB_SPEC 과의 차이는 계약 회의 안건([REVIEW_CHECKLIST](../../docs/api/REVIEW_CHECKLIST.md) #14).

## `rig_transfer.py` — 템플릿의 뼈대·애니메이션 이식

```bash
python tools/rig_transfer.py --base work/<jobId>/base.glb --texture work/<jobId>/raw/uv.png \
  --template <리포 밖>/template-rigged.glb --out work/<jobId>/base.glb
```

- AnimalLift 출력은 모두 같은 템플릿 위상(SMAL 계열, 정점 순서 동일)이라, 템플릿의 스킨 가중치·삼각형·UV·애니메이션 클립을 그대로 쓰고 정점 위치·법선만 새 모델 것으로 바꾼다. 관절 위치는 근처 정점으로 다시 맞추고, 루트 이동 키는 골반 높이 비율로 늘이거나 줄인다.
- 템플릿 GLB 는 SMAL 파생(학술 라이선스)이라 git 에 넣지 않는다. 추론 서비스는 환경변수 `BESIDE_RIG_TEMPLATE` 이 있을 때만 리깅한다(`scripts/start_inference_wsl.sh` 는 `~/beside-rig/template-rigged.glb` 가 있으면 켠다). 템플릿을 만든 스크립트는 이 리포에 없다.
- 표준 출력: `{ "joints", "fitErrorM", "heightRatio", "clips", "bytes" }`. 위상이 다르면 종료 코드 2(`RIG_FAILED`) — 추론 서비스는 그때 리깅 없는 GLB 를 그대로 돌려준다. 리깅 시간은 `convertMs` 에 더해진다(METRICS 의 정의는 변환만 — 측정 때 주의).
- 앱(`unity/Assets/Beside/Pet/PetAnimationPlayer.cs`)은 GLB 안의 클립 `Idle`·`Walk`·`Sit`·`TailWag`·`HeadTilt` 를 칩으로 보여 준다.

## 검증 ([계획])

- `gltf-validator` 오류 0 (`npm i -g gltf-validator` 또는 Khronos 바이너리), Unity 에디터 glTFast 로드 — 결과를 실험 기록에 남긴다.
- 변환 결과 샘플은 커밋하지 않는다. Mock 서버용 `backend/storage/results/sample-dog.glb` 는 아직 0바이트다(유효 GLB 로 교체는 직접 할 일).
