# tools — 에셋 변환 도구 (3D Generation 트랙)

AnimalLift 출력(`mesh.obj` + `uv.png` + `hair.npz`)을 앱이 쓰는 GLB 로 만든다. 추론 서비스(`inference_service/app.py`)가 아래 순서로 부르며, 손으로 돌릴 때도 순서가 같다. 계약은 [docs/asset/GLB_SPEC.md](../../docs/asset/GLB_SPEC.md).

```text
mesh.obj + uv.png + hair.npz
   │ 1. convert_asset.py   GLB_SPEC 변환 + 털 지도 PNG
   ▼
base.glb (정적) + base.metrics.json + texture_padded.png + hair_length.png / hair_flow.png / hair_maps.json + hair_region.png
   │ 2. rig_transfer.py    SMAL 뼈대·웨이트·클립 옮기기 (템플릿이 있을 때만)
   ▼
base.glb (리깅)
   │ 3. embed_hair.py      털 지도 2장 + TANGENT 를 GLB 안에 (마지막 단계)
   ▼
base.glb (리깅 + extras.beside)
```

상태: 세 도구 모두 **[구현됨]**. 털 지도 삽입은 2026-11-01 추가.

## 1. `convert_asset.py`

```bash
python tools/convert_asset.py \
  --obj work/<jobId>/raw/mesh.obj --texture work/<jobId>/raw/uv.png \
  [--hair work/<jobId>/raw/hair.npz --hair-meta <AnimalLift>/assets/hair_map_meta_uvanchor.npz] \
  --out results/<jobId>/base.glb --metrics results/<jobId>/base.metrics.json \
  [--forward-axis +x] [--normalize-longest-axis 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760]
```

- GLB_SPEC §1~§4: 단일 GLB, 텍스처 임베드, 노드 `base`, Y-up, 최장 축 1.0 m, 최저점 y = 0, 정면 +Z(AnimalLift 템플릿은 머리가 +X 라 기본 `--forward-axis +x`), metallic 0 / roughness 0.9.
- `metrics.json`: `vertices`, `triangles`, `textureSize`, `bytes`, `convertMs` ([docs/METRICS.md](../../docs/METRICS.md)).
- 위반 시 종료 코드 2 와 이유 → 추론 서비스는 `CONVERSION_FAILED`.
- `uv.png` 의 초록 배경은 가장 가까운 UV 섬 색으로 채운 `texture_padded.png` 로 바꿔 넣는다(귀·이음새 초록 번짐 방지).
- `--hair`: 털 길이·결 지도를 만든다. `--hair-meta` 를 주면 AnimalLift 가이드 텍셀(약 21%)만 써서 다시 채우고, 코·발바닥·눈 주변은 털을 없앤다. 자세한 규칙은 [ANIMALLIFT_SETUP.md](../inference_service/ANIMALLIFT_SETUP.md).

## 2. `rig_transfer.py`

```bash
python tools/rig_transfer.py --base results/<jobId>/base.glb --texture results/<jobId>/texture_padded.png \
    --template <로컬 경로>/template-rigged.glb --out results/<jobId>/base.glb
```

- 모든 AnimalLift 출력은 같은 토폴로지(정점 4,310개, 같은 순서)라 웨이트를 그대로 옮기고, 관절 위치만 새 체형에 맞춘다.
- **템플릿 GLB 는 SMAL 파생(학술 라이선스)이라 커밋하지 않는다.** 실행 시 경로로 넘긴다(추론 서비스는 `BESIDE_RIG_TEMPLATE`).
- 종료 코드 2 = 옮길 수 없음(토폴로지 다름 등) → 정적 GLB 를 그대로 쓴다.

## 3. `embed_hair.py`

```bash
python tools/embed_hair.py --glb results/<jobId>/base.glb --maps results/<jobId>/hair_maps.json
```

- `hair_length.png`, `hair_flow.png` 를 GLB 바이너리에 넣고 루트 `extras.beside = {hairLengthImage, hairFlowImage, furLength}` 를 쓴다. 털 결 방향에 필요한 TANGENT 도 추가한다. 형식은 GLB_SPEC §3.1.
- 리깅이 GLB 를 템플릿에서 다시 만들기 때문에 **반드시 마지막**에 돌린다. 다시 돌리면 이전 지도를 교체한다.
- 추론 서비스에서 끄려면 `BESIDE_EMBED_HAIR=0`.

## 검증

- `gltf-validator` 오류 0 (`npm i -g gltf-validator`), Unity 기기에서 glTFast 로드·털 표시 확인.
- 변환 결과(`work/`, `results/`)는 커밋하지 않는다.
