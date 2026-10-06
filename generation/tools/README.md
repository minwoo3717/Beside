# tools — 에셋 변환 도구 (3D Generation 트랙)

## `convert_asset.py` 자리 (직접 할 일)

OBJ + PNG (+ hair.npz) → GLB 변환기와 glTFast 로드 검증은 팀이 별도 세션에서 완성했다고 보고됐으나 **이 리포에는 아직 없다**. 그 결과물을 이 폴더에 투입하고, 아래 인터페이스와 다르면 이 README 와 [docs/asset/GLB_SPEC.md](../../docs/asset/GLB_SPEC.md) §5 를 실제 스크립트에 맞게 고친다(PLAN 0단계).

### 제안 인터페이스

```bash
python tools/convert_asset.py \
  --obj outputs/<jobId>/mesh.obj --texture outputs/<jobId>/uv.png \
  [--hair outputs/<jobId>/hair.npz] \
  --out results/<jobId>/base.glb [--hair-out results/<jobId>/hair.glb] \
  --metrics results/<jobId>/base.metrics.json \
  [--normalize-height 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760]
```

- 출력 GLB 는 GLB_SPEC §1~§4 를 만족해야 한다: 텍스처 임베드, Y-up, 1 unit = 1 m, 원점 발바닥 중심(최저점 y = 0), 정면 +Z, 노드 이름 `base`/`hair`. 출력 경로 `results/<jobId>/base.glb` 는 추론 서비스가 `/infer` 응답의 `glbPath` 로 돌려주고 Spring 이 `storage/results/{jobId}/base.glb` 로 복사한다(GLB_SPEC §5·§6 과 같은 모양).
- `--normalize-height 1.0` 은 GLB_SPEC §2 의 "바운딩 박스 **최장 축** 1.0 m" 와 다르다. 실제 스크립트를 투입할 때 플래그 이름과 의미를 §2 에 맞춘다(예 `--normalize-longest-axis 1.0`).
- `metrics.json`: `{ "vertices", "triangles", "textureSize", "bytes", "convertMs" }` (이름 고정, [docs/METRICS.md](../../docs/METRICS.md)).
- 상한 위반 시 종료 코드 ≠ 0 과 이유 출력 → 추론 서비스는 `CONVERSION_FAILED` 로 보고한다.
- 검증: `gltf-validator` 오류 0 (`npm i -g gltf-validator` 또는 Khronos 바이너리), Unity 에디터 glTFast 로드.

### 이 폴더에 둘 것

- `convert_asset.py` (+ 의존성 `requirements.txt`: trimesh / pygltflib 등 실제 사용 라이브러리)
- `validate_glb.sh` 또는 `.py`: gltf-validator 실행 + GLB_SPEC 상한 검사 (계획)
- 변환 결과 샘플은 커밋하지 않는다. Mock 서버용 유효 GLB 1개만 `backend/storage/results/sample-dog.glb` 로 교체한다.
