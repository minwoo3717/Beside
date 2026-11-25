# GLB 에셋 계약 (v1.0)

적용 대상: 추론 서비스가 만들고(`generation/tools/convert_asset.py`, 선택으로 `rig_transfer.py`), Spring 이 `GET /api/v1/jobs/{jobId}/asset` 으로 서빙하며, Unity 가 glTFast 로 로드하는 `.glb` 파일. 세 트랙이 이 문서 하나를 기준으로 삼는다. **"제안값"** 으로 표시한 숫자는 확정 전 임시값이다([REVIEW_CHECKLIST.md](../api/REVIEW_CHECKLIST.md) #3).

2026-11-21 결정 반영(계약 회의 대신 Backend 담당 결정, [REVIEW_CHECKLIST.md](../api/REVIEW_CHECKLIST.md) #3·#4·#14·#16): 결과 GLB 는 작업당 `base.glb` **하나**다. 리깅(스킨·클립)은 선택 항목, 털 맵은 그 GLB 안 `extras.beside`(§4.1), hair variant GLB 와 hair 삼각형 상한(100k)은 없다. 에셋 숫자 상한(base 삼각형 20k·텍스처 2048²·GLB 10 MB)은 exp-02 와 기기 FPS 를 잰 뒤 확정한다.

상태:

- **[구현됨]** 변환기 `convert_asset.py`(털 맵 넣기 포함)와 리깅 `rig_transfer.py`(털 맵 옮기기 포함)가 `generation/tools/` 에 있고 추론 서비스(`generation/inference_service/app.py`)가 작업마다 부른다. 털 맵을 GLB 안에 넣는 코드는 PR #25(브랜치 `fix/hair-maps-in-glb`, 2026-11-21)로 들어왔고 `feat/contract-v1.0` 에 합쳤다 — main 병합(2026-11-21)·Pod 반영(2026-11-25 확인: 2026-11-21 5차 부하 시험의 Pod 가 낸 GLB 에 털 맵, [perf/load/2026-11-21_vm-5th.md](../perf/load/2026-11-21_vm-5th.md) 1절 '털 맵 확인'. §4.1 '알려진 점'). 각 절에 코드가 강제하는 항목과 그것을 보는 시험을 적었다. 변환기·리깅 도구의 시험도 `generation/inference_service/test_app.py` 에 있다(`generation/tools/` 에는 시험 파일이 따로 없다). 시험은 모델·GPU 없이 작은 합성 메시로 돌고, numpy·trimesh·pillow 가 없으면 건너뛴다.
- **[실험 결과]** [exp-01](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)(Runpod A40, **리깅 끔**, 2026-11-17·18): 정점 4,310·삼각형 7,774, GLB 1,310,876~1,327,400 bytes(약 1.3 MB), `convertMs` 846~1,133(표본 4건)·2차 부하 539건 평균 900. 이때는 털 맵이 GLB 밖 파일이었다. 털 맵을 넣은 GLB(리깅 끔)는 5차 부하 시험(2026-11-21, Runpod A40, 같은 사진 반복)에서 1,923,996 bytes 였다(모든 실행이 같은 크기, [perf/load/2026-11-21_vm-5th.md](../perf/load/2026-11-21_vm-5th.md) 1절) **[실험 결과]**. 서버가 만든 리깅본(`modelVersion` 끝 `+rig`)은 2026-11-25 발표 GPU 리허설(Runpod A40, 사진 `dog.jpg` 1장, 9건, 1회)에서 2,100,924 bytes 였다(모든 실행이 같은 크기, [리허설 기록](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md)) **[실험 결과]**.
- **[계획]** glTF Validator 실행, Unity(glTFast) 로드·`loadMs`·기기 FPS 기록, 서버에서 만든 GLB 의 털·클립을 기기에서 확인, 품질 채점.
- Mock 의 `backend/storage/results/sample-dog.glb` 는 리포에서 0바이트라 이 계약을 만족하지 않는다(직접 교체).

## 1. 파일 형식

| 항목 | 값 | 비고 |
|---|---|---|
| 포맷 | glTF 2.0 binary (`.glb`), 단일 파일, buffer 1개(= BIN 청크) | 외부 `.bin`/이미지 참조 금지. 텍스처·털 맵은 bufferView 에 임베드. 앱의 털 맵 읽기는 BIN 청크만 보고, 변환기는 buffer 가 하나가 아니면 털 맵을 넣지 못해 `CONVERSION_FAILED` 로 끝낸다 |
| Content-Type | `model/gltf-binary` | API 응답 헤더 및 `AssetInfo.contentType` |
| 파일 크기 | ≤ 10 MB (제안값) | 털 맵·스킨·클립을 모두 넣은 **최종 파일** 기준. 변환기는 털 맵을 넣은 뒤의 크기를 검사하고(`convert_asset.py`, 이 상한을 넘는 정적 GLB 의 시험은 없다), 추론 서비스는 리깅본을 다시 검사한다(`OversizeRiggedResultTest`) **[구현됨]**. 리깅본이 넘으면 정적 GLB 를 낸다. `AssetInfo.bytes` 로 노출 |
| 검증 | [glTF Validator](https://github.khronos.org/glTF-Validator/) 오류 0 | **[계획]**(exp-02). 경고는 허용하되 실험 기록에 남긴다. 털 맵 이미지는 어느 texture 도 가리키지 않아(§4.1) '쓰이지 않는 객체' 알림이 나올 수 있다(확인 안 함) |
| 확장(extension) | 없음 | Draco/KTX2 압축은 glTFast 지원 확인 후 [계획]. 쓰려면 Unity 패키지 추가 필요 |

## 2. 좌표계 · 단위 · 원점 · 방향

- Y-up, 오른손 좌표계(glTF 표준). **1 unit = 1 m**.
- 원점 (0, 0, 0) = 네 발바닥(접지면)의 중심. 모델의 최저점 y = 0, 바닥에 그대로 놓인다. x·z 는 바운딩 박스 가운데.
- 정면 = **+Z** (얼굴이 +Z 를 향한다). glTF 관례와 같다. AnimalLift 출력은 머리가 +X 라 변환기 기본값 `--forward-axis +x` 로 돌린다.
- 크기 정규화: 변환기는 모델 바운딩 박스의 최장 축을 **1.0 m** 로 맞춘다(제안값). 사진만으로 실제 크기를 알 수 없으므로 실제 크기는 앱에서 사용자가 조정한다(§7).
- 리깅본도 정점 위치는 정적 GLB 의 것 그대로라 위 규칙이 같다. 관절은 그 정점에 맞춰 다시 놓는다.
- glTFast 가 Unity 좌표계 변환을 처리하므로 앱은 축 변환을 추가하지 않는다. 배치 후 정면이 사용자를 향하도록 Y축 회전만 적용한다.

상태: **[구현됨]** `convert_asset.py`(코드). 이 배치(최장 축 1.0, 최저점 0, x·z 가운데, OBJ 의 +X → +Z)를 직접 확인하는 자동 시험은 아직 없다 **[계획]**.

## 3. 메시 구성

| 항목 | 정적 (기본) | 리깅 (선택: 추론 서비스에 `BESIDE_RIG_TEMPLATE`) |
|---|---|---|
| 파일 | `base.glb` 하나 | 같은 `base.glb` 를 리깅본으로 바꾼다 |
| 노드 | 1개, 이름 `base` | 리깅 템플릿의 노드(메시 노드 + 관절 노드). 템플릿이 리포 밖이라 노드 이름은 이 문서가 정하지 않는다 |
| 정점 속성 | `POSITION`, `NORMAL`, `TEXCOORD_0` | + `JOINTS_0`, `WEIGHTS_0`(템플릿 것) |
| 인덱스 | uint16 또는 uint32 | 템플릿 것 |
| 삼각형 수 상한 | ≤ 20,000 (제안값). 변환기가 검사. exp-01 7,774 **[실험 결과]** | 정적과 같은 위상이라 같은 수 |
| 머티리얼 | PBR metallic-roughness, `baseColorTexture` = `uv.png`, metallic 0, roughness 0.9(계약 범위 0.8~1.0, 제안값) | 템플릿의 머티리얼에 base color 이미지만 새 `uv.png` 로 바꾼다 |
| 스킨 · 애니메이션 | 없음 | 스킨 1개(템플릿의 36 joints — 안건 #14 기록 기준. 템플릿이 리포 밖이라 이 리포의 시험으로 세지 않는다. 추론 서비스 로그 `rigged (N joints, clips …)` 에 실제 수가 찍힌다), 클립 5개 `Idle`·`Walk`·`Sit`·`TailWag`·`HeadTilt` |
| 털 맵 | §4.1 (`extras.beside`) | §4.1 — 정적 GLB 의 것을 그대로 옮긴다 |

hair variant 는 없다(#4, 2026-11-21): 예전의 `variant=hair` 파일, 노드 `hair`, hair 머티리얼(`alphaMode`), hair 삼각형 상한 100,000 은 삭제했다. 털은 앱이 §4.1 털 맵으로 셸 털을 그린다.

정적 상태: **[구현됨]** `convert_asset.py`(노드 `base`, 정점 속성, metallic 0·roughness 0.9 — 코드), 삼각형 상한은 변환기 검사. 노드 이름·머티리얼 값을 확인하는 시험은 없다. `FurMapsInGlbTest` 는 변환 결과를 trimesh 로 다시 읽어 메시 하나·texture 하나인지만 본다.

### 3.1 리깅 (선택 항목)

- 추론 서비스에 리깅 템플릿(SMAL 파생, 학술 라이선스라 리포 밖)이 설정됐을 때만 만든다. AnimalLift 출력은 모두 같은 템플릿 위상이라 템플릿의 스킨 가중치·삼각형·UV·클립을 쓰고 정점 위치·법선만 새 모델 것으로 바꾼다([generation/tools/README.md](../../generation/tools/README.md)).
- 클립 이름은 템플릿이 정하고 `rig_transfer.py` 는 그대로 옮긴다. 앱(`PetAnimationPlayer`)은 위 다섯 이름으로 찾는다 — 템플릿을 바꾸면 이름을 맞춘다. 클립이 없는 정적 GLB 면 앱은 동작 버튼을 숨긴다.
- 리깅에 실패하거나(위상이 다름 등) 리깅본이 §1 크기 상한을 넘으면 정적 GLB 를 낸다. 작업은 실패하지 않는다. `modelVersion` 의 `+rig` 는 그 작업에서 리깅이 성공했을 때만 붙는다(#18).
- 리깅이 성공한 실행은 리깅 시간이 `convertMs` 에 들어간다(#16, §5).
- 상태: **[구현됨]** 시험은 모두 `test_app.py` 에 있다 — 합성 피라미드 템플릿으로 `rig_transfer.py` 를 직접 돌리는 `RigTransferVertexOrderTest`·`RigTransferFurMapsTest`, 가짜 리깅 모듈로 추론 서비스의 처리(리깅본으로 바꾸기·크기 상한)를 보는 `RiggedResultTest`·`OversizeRiggedResultTest`. 실제 템플릿으로 리깅을 켠 측정은 2026-11-25 발표 GPU 리허설 9건(1회·사진 1장)이 처음이다: GLB 2,100,924 B, `processingMs`·`convertMs` 는 [리허설 기록](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) **[실험 결과]**. 사진을 바꾼 측정과 `inferMs`·`convertMs` 정리는 **[계획]**(exp-01 은 리깅 끔).

## 4. 텍스처

- base color 1장: 해상도 ≤ 2048 × 2048 (제안값), 정사각 권장. PNG 로 임베드(정적은 변환기가 다시 인코딩한 PNG, 리깅본은 `uv.png` 원본 바이트). 색 공간 sRGB. 변환기가 검사한다 **[구현됨]**.
- 털 맵 2장(§4.1): PNG, linear 데이터, 같은 해상도 상한을 변환기가 검사한다 **[구현됨]**(`test_fur_maps_over_the_texture_limit_are_refused`).
- 노멀맵·러프니스맵은 없음을 기본으로 한다.

### 4.1 셸 털 맵 (`extras.beside`)

[구현됨: PR #25(`fix/hair-maps-in-glb`, 2026-11-21), `feat/contract-v1.0` 에 합침 — main 병합(2026-11-21)·Pod 반영(2026-11-25 확인: 2026-11-21 5차 부하 시험의 Pod GLB 에 `extras.beside`, [vm-5th](../perf/load/2026-11-21_vm-5th.md) 1절)] 앱의 셸 털(`ShellFurRenderer`)이 쓰는 맵 두 장을 `base.glb` 안에 넣는다. 전에는 변환기가 GLB 옆에 PNG 로만 써서 어떤 경로로도 앱에 닿지 않았고, 서버에서 만든 모델에는 털이 그려지지 않았다(안건 #14). 필드 **추가**다: 쓰이지 않는 이미지와 `extras` 는 glTF 2.0 에서 허용되고, 털 맵을 넣은 GLB 를 trimesh 로 다시 읽는 시험이 있다(`FurMapsInGlbTest`). glTFast 로드는 확인 전이다 **[계획]**.

앱(`unity/Assets/Beside/Loader/RuntimeModelLoader.cs` 의 `TryReadEmbeddedHair`)이 읽는 모양이 기준이고, 변환기(`convert_asset.py` 의 `embed_hair`)가 그 모양으로 쓴다. 정적 변환기 출력의 예:

```json
{
  "extras": { "beside": { "hairLengthImage": 1, "hairFlowImage": 2, "furLength": 0.012345 } },
  "images": [
    { "bufferView": 2, "mimeType": "image/png" },
    { "name": "hair_length", "mimeType": "image/png", "bufferView": 5 },
    { "name": "hair_flow", "mimeType": "image/png", "bufferView": 6 }
  ],
  "textures": [ { "source": 0 } ]
}
```

(예시: 이미지 0 이 base color, 1·2 가 털 맵. 번호는 파일마다 다르니(리깅본은 템플릿 이미지 뒤에 붙는다) 키로 찾는다.)

| 키 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| `hairLengthImage` | int — `images` 의 번호 | 예 | 털 길이 맵. 없거나 그 이미지를 읽지 못하면 앱은 이 GLB 에 털 맵이 없다고 본다 |
| `hairFlowImage` | int — `images` 의 번호 | 아니오(변환기는 늘 씀) | 털 결 맵. 없으면 앱은 `_FlowMap` 을 바꾸지 않아 털 머티리얼 템플릿에 들어 있던 결 맵이 그대로 쓰인다 |
| `furLength` | number | 아니오(변환기는 늘 씀) | 털 길이 기준값, 이 GLB 의 단위(m, 최장 축 1 m 기준) = `hair.npz` 털 끝 길이의 99.5 백분위수 × 변환기의 크기 정규화 배율(§2), 소수 6자리. 앱이 셸 털 셰이더의 `_FurLength` 에 넣는다. 없거나 0 이하면 앱은 넣지 않아 털 머티리얼 템플릿(`RuntimeModelLoader.furMaterialTemplate`, `SampleScene` 에서는 `Dog_Fur_Mat`)의 `_FurLength` 가 쓰인다 — 지금 0.025. `ShellFur.shader` 의 속성 기본값 0.065 가 아니다(앱은 템플릿 머티리얼을 복사해 쓴다) |

- 위치: glTF JSON **최상위**(root)의 `extras.beside`. 변환기는 그 밖의 `extras` 키를 지우지 않는다. 앱은 `beside` 안의 세 키만 읽고 나머지는 무시한다.
- 이미지: `images[n]` = `{ "name": "hair_length" | "hair_flow", "mimeType": "image/png", "bufferView": … }`(URI 아님). 그 bufferView 는 유일한 buffer, 즉 BIN 청크 안에 4바이트 정렬로 붙는다. 앱은 `bufferView.buffer` 를 보지 않고 BIN 청크 데이터 시작(20 + JSON 청크 길이 + 8)부터 `byteOffset`(없으면 0)·`byteLength` 만큼 읽는다.
- 어느 `textures[]`·머티리얼도 털 맵 이미지를 가리키지 않는다. 그래서 `textures[0]` 은 몸 텍스처 그대로다(앱의 머티리얼 교체가 대체 텍스처로 `GetTexture(0)` 을 쓴다).
- 길이 맵(`hair_length`): 8-bit 그레이스케일 PNG. 값/255 = 그 texel 의 털 길이 ÷ `furLength`, 0~1 로 자른다(기준보다 긴 털은 1). 셰이더는 R 채널을 읽는다.
- 결 맵(`hair_flow`): 8-bit RGB PNG. R·G = 털 끝 방향의 접선 성분, B = 법선 성분(털 끝 벡터 ÷ 길이, 각 -1~1 로 자름), 각각 `v × 0.5 + 0.5` 로 0~255 에 담는다.
- 두 맵 모두 색이 아닌 데이터다: 앱은 linear 텍스처로 읽는다. UV 는 몸 메시 `TEXCOORD_0` 과 같은 배치이고, PNG 위쪽 행 = glTF v 0(몸 텍스처와 같은 관례)이다. `hair.npz` 배열은 행 0 = v 0 으로 저장돼 있어 변환기가 행을 뒤집어 넣고 결 맵 G 의 부호도 그에 맞춘다(변환기 주석: UV seam 으로 확인).
- 해상도: `hair.npz` 격자(높이 × 너비) 그대로(AnimalLift 의 실제 크기는 기록 없음). 긴 변이 §4 텍스처 상한을 넘거나, `hair.npz` 를 읽지 못하거나 모양이 (높이, 너비, 점, 3) 이 아니면 `CONVERSION_FAILED` 다. GLB 크기 상한(§1)은 털 맵을 넣은 뒤에 본다.
- 리깅본: `rig_transfer.py` 가 정적 `base.glb` 의 `extras.beside` 와 두 이미지 바이트를 템플릿 JSON 에 붙여 옮긴다(이미지 번호는 다시 매긴다). 템플릿에 있던 `extras.beside`(템플릿 동물의 털)는 버린다. 정적 GLB 에 털 맵이 없으면 리깅본에도 없다.
- `hair.npz` 가 없으면(모델이 내지 않거나 변환기 CLI 에 `--hair` 를 주지 않으면) `extras.beside` 가 없다. 추론 서비스는 모델이 `hair.npz` 를 내면(AnimalLift 는 매번) 내부 요청의 `options.hair` 와 상관없이 넘긴다. 단 환경 변수 `BESIDE_EMBED_HAIR=0` 이면 넘기지 않아 `extras.beside` 가 없다(기본은 넘김, `ConverterSwitchTest`·`RealEmbedHairOffTest`).
- 확인용 사본: 변환기는 같은 맵을 GLB 옆에 `hair_length.png`·`hair_flow.png`·`hair_maps.json`(`furLength`, 맵 파일 이름)으로도 쓴다. 서빙하지 않는다(추론 서비스 `/artifacts` 는 `base.glb` 만 내주고, Spring 은 GLB 하나만 받는다).

앱이 읽는 방식(지금 Unity 코드 기준):

- 작업 흐름만 읽는다: `JobManager` 가 내려받은 GLB 바이트로 `LoadAndRegister(byte[] …)` 를 부르고, 그 안에서 `TryReadEmbeddedHair` 가 맵과 `furLength` 를 꺼낸다. 맵이 없거나 읽지 못하면 Inspector 의 대체 맵(`fallbackHairLength`·`fallbackHairFlow`, `SampleScene` 에서는 비어 있음)을 쓰고, 그것도 없으면 털을 그리지 않는다.
- URL·파일 경로로 여는 `LoadAndRegister(string glbUrl, …)`(Inspector 의 Test Glb Url)는 `TryReadEmbeddedHair` 를 부르지 않는다. 그 경로는 Test Hair Length/Flow Url 로 따로 준 PNG 만 쓰고 `furLength` 도 읽지 않는다(템플릿 값). 그래서 서버가 만든 GLB 를 Test Glb Url 로만 열면 털이 없다.
- 셸 수는 `furShellCount`(`SampleScene` 20). 정적 `MeshFilter`·리깅 `SkinnedMeshRenderer` 모두에 셸을 붙인다.
- 결 맵은 메시에 `TANGENT` 가 있을 때만 쓴다(셸 털 v2, 2026-11-21 브랜치 Unity 포트로 들어옴 — 그 전 v1 은 `TANGENT` 가 없으면 임시 접선을 만들어 R·G 를 썼다). 이 변환기와 `rig_transfer.py` 는 `TANGENT` 를 쓰지 않으므로 지금 서버 GLB 에서는 R·G(위의 G 부호 맞춤 포함)로 털을 눕히지 않고 법선 쪽으로 세운다. 그러면 B 도 방향에 영향이 없어 결 맵 전체가 효과가 없다. 형식은 그대로이고, `TANGENT` 를 넣을지(서버 또는 앱)와 G 부호는 결정 전이다 **[계획]**([unity/docs/FUR_RENDERING.md](../../unity/docs/FUR_RENDERING.md) §1). 기기에서 확인하지 않았다.

상태:

- **[구현됨]** 쓰기 `convert_asset.py` 의 `hair_maps`·`embed_hair`, 옮기기 `rig_transfer.py` 의 `_copy_fur_maps`. 시험(`generation/inference_service/test_app.py`): `FurMapsInGlbTest`(앱 방식으로 읽힘·사본과 같은 바이트·texture 1개·`hair.npz` 없으면 항목 없음·읽지 못하는 `hair.npz`·해상도 상한), `RealConversionCancelTest.test_the_conversion_writes_into_the_existing_job_folder`(업로드 → `/artifacts` 로 내려받은 `base.glb` 에 털 맵), `RigTransferFurMapsTest`(이 GLB 의 털 맵은 옮기고 템플릿 것은 버림). 셋 다 앱의 `TryReadEmbeddedHair` 를 같은 순서로 옮긴 파이썬 함수 `_app_reads_fur` 로 읽어 확인한다.
- 앱의 읽기 코드는 PR #25 전부터 있었다(#25 는 그 주석만 고쳤다). Unity 쪽 자동 시험은 없다.
- **[계획]** 서버에서 만든 GLB 로 기기에서 털이 그려지는지, GLB 의 `furLength`(아래 '알려진 점'의 로컬 확인 값 약 0.070. 5차 부하 시험의 서버 GLB 는 0.02041 — 사진 `dog.jpg` 1장, [vm-5th](../perf/load/2026-11-21_vm-5th.md) 1절)와 지금까지 기기에서 써 온 `Dog_Fur_Mat` 의 `_FurLength` 0.025 를 같은 모델로 나란히 비교, 털 맵을 넣은 GLB 의 `loadMs`·FPS(크기는 맨 위 '상태'의 5차 부하 시험 값).
- 알려진 점: Pod 가 새 변환기를 쓰기 시작하면 이미 깔린 앱도 새 작업마다(작업 흐름) 셸 20겹 털을 GLB 의 `furLength` 로 그린다. [RUNBOOK](../ops/RUNBOOK.md) 'GPU Pod 다시 세우기' 2단계는 Pod 를 켤 때마다 `checkout -B main origin/main` 을 한다. 그래서 main 병합 뒤에는 장애 복구로 Pod 를 한 번 다시 켜기만 해도 털이 켜진다. 추론 서비스에 털 맵 넣기를 끄는 스위치 `BESIDE_EMBED_HAIR=0` 이 생겼다 **[구현됨]**(브랜치 Unity 포트, 2026-11-21 — 위 `hair.npz` 항목). Pod 를 켤 때 그 값을 넣는 것은 [계획]이다. 기기 확인 전에 털이 켜지는 것을 피하려면 Pod 를 켤 때 그 값을 주거나, 그 단계에서 Pod 리포를 병합 전 main 커밋(2026-11-21 기준 `24bce92` — 그 뒤 Pod 에서 도는 코드의 동작 차이는 PR #25 의 변환기·리깅뿐이고 나머지는 주석·문서다)에 고정한다(명령은 RUNBOOK 같은 단계). 2026-11-25 확인: Pod 는 2026-11-21 5차 부하 시험부터 털 맵을 넣은 GLB 를 냈다(맨 위 '상태'). `BESIDE_EMBED_HAIR=0` 으로 Pod 를 켠 기록은 리포에 없다. 앱에서 그 털이 어떻게 그려지는지는 기기에서 확인하지 않았다 **[계획]**.
- 알려진 점(로컬 확인 1건, 실험 기록 아님): 리포의 AnimalLift 출력 `unity/Assets/Models/n02085782_186 (1)_{mesh.obj,uv.png,hair.npz}` 를 이 변환기(`convert()`, `forward_axis="+x"`)로 2026-11-21 에 로컬 변환하니 `hair.npz` 격자 512×512, 털 맵 PNG 135,776 B(길이) + 444,118 B(결), GLB 2,120,640 B(§1 상한 안), `furLength` 0.069799 였다. `Dog_Fur_Mat` 의 0.025 의 약 2.8배라 기기에서 털 모양이 눈에 띄게 달라질 수 있다. 그 길이가 화면에서 맞는지, 20겹의 FPS·메모리는 재지 않았다 **[계획]** — Unity·3D 담당이 기기에서 확인한다.

## 5. 변환기 출력 (`generation/tools/convert_asset.py`)

| 입력 | 출력 |
|---|---|
| `mesh.obj` + `uv.png` (+ `hair.npz`) | `{jobId}/base.glb`(`hair.npz` 가 있으면 §4.1 털 맵 포함) + `{jobId}/base.metrics.json`. 털 맵 사본 `hair_length.png`·`hair_flow.png`·`hair_maps.json` 도 옆에 쓰지만 확인용이고 서빙하지 않는다. 리깅을 켜면 추론 서비스가 같은 `base.glb` 를 리깅본으로 바꾸고 `base.metrics.json` 을 다시 쓴다 |

경로 모양은 `tools/README.md` 의 CLI(`--out results/<jobId>/base.glb`)와 Spring 저장 규칙(§6, `storage/results/{jobId}/base.glb`)과 같다. 입력 세 파일은 AnimalLift 가 실제로 쓰는 것이고(`animallift_runner.py`), exp-01 에서 이 입력으로 변환했다 **[실험 결과]**.

`metrics.json` 은 다음 다섯 필드를 가진다(이름 고정, [METRICS.md](../METRICS.md) 와 동일). 값은 예시다.

```json
{ "vertices": 4310, "triangles": 7774, "textureSize": 2048, "bytes": 1326748, "convertMs": 867 }
```

- `bytes`: 서빙되는 최종 GLB 의 크기(털 맵 포함, 리깅본이면 리깅본).
- `convertMs`: 변환 시간. 리깅이 성공한 실행(`modelVersion` 끝이 `+rig`)은 리깅 시간을 더한다(#16, 2026-11-21 결정. 정의는 [METRICS.md](../METRICS.md)).
- `textureSize`: base color 텍스처의 긴 변. `vertices`·`triangles`: 메시.

변환기는 삼각형 수·텍스처(털 맵 포함) 해상도·최종 파일 크기·UV 유무·`hair.npz` 읽기를 스스로 검사하고, 위반이면 0 이 아닌 종료 코드(2)와 이유를 낸다(검증 실패 = `CONVERSION_FAILED`) **[구현됨]**. glTF Validator 수준의 검사는 하지 않는다(§8).

## 6. 저장 · 서빙 규칙 (Spring)

- 저장 경로: real 워커는 추론 서비스가 돌려준 GLB(경로 모드는 `/infer` 의 `glbPath`, 업로드 모드는 `GET /artifacts/{jobId}/base.glb`)를 `backend/storage/results/{jobId}/base.glb` 로 복사한다 **[구현됨]** 2026-10-04. 복사 전에 glTF 2.0 바이너리 헤더(magic `glTF`, version 2)만 확인하고 아니면 `CONVERSION_FAILED` 다. §1~§4 검증은 변환기 몫이다. 털 맵·스킨이 그 파일 안에 있으므로 Spring 이 따로 받을 파일은 없다. `base.metrics.json` 파일은 가져오지 않는다(같은 수치를 `/infer` 응답 `metrics` 로 받아 `job_runs` 에 저장, [METRICS.md](../METRICS.md)). Mock 은 `storage/results/sample-dog.glb` 하나를 모든 작업에 돌려준다.
- 응답 헤더: `Content-Type: model/gltf-binary`, `Content-Length`, `Content-Disposition: attachment; filename="{jobId}-{variant}.glb"`.
- variant 는 `base` 하나다(#4, 2026-11-21 결정: `hair` 를 v1.0 계약에서 뺌). API 쪽 반영은 [openapi.yaml](../api/openapi.yaml)·[CHANGELOG.md](../api/CHANGELOG.md) 가 기준이다. 추론 서비스 내부 계약의 `options.hair`·`hairGlbPath` 는 모양을 지키려고 남겼다: Spring 은 늘 `false` 를 보내고 서비스는 늘 `null` 을 준다([generation/inference_service/README.md](../../generation/inference_service/README.md)).

## 7. 앱 배치 규칙 (Unity)

- 캐시: `Application.persistentDataPath/models/{jobId}-{variant}.glb`(variant 는 `base`). 같은 jobId 는 다시 받지 않는다.
- 기본 배치 스케일 **0.6** (모델 최장 축 1.0 m 기준 → 약 0.6 m). 사용자 핀치로 **0.2 ~ 1.5** 범위 조정.
- 바닥 평면(AR plane) 위에 원점(발바닥 중심)을 놓는다. 처음 배치 시 +Z 가 카메라를 향하도록 Y축 회전.
- 털: 작업 흐름(`JobManager` → GLB 바이트)에서 `RuntimeModelLoader` 가 §4.1 을 읽어 `ShellFurRenderer` 로 셸 털을 그린다(정적 `MeshFilter`·리깅 `SkinnedMeshRenderer` 모두). Inspector 의 Test Glb Url(URL·파일 경로) 경로는 GLB 안 털 맵을 읽지 않는다(§4.1 '앱이 읽는 방식'). 클립: `PetAnimationPlayer` 가 §3.1 의 다섯 이름을 재생한다. 서버에서 만든 GLB 로 기기 확인은 **[계획]**.
- 로딩 시간(`loadMs`)과 FPS 는 [METRICS.md](../METRICS.md) 의 이름으로 기록한다.

## 8. 검증 절차 (완료 기준)

1. `gltf-validator` 오류 0 — 결과 JSON 을 실험 기록에 첨부. **[계획]**
2. §3·§4·§1 의 상한(삼각형, 텍스처, 파일 크기) 통과 — 변환기·추론 서비스가 작업마다 자동으로 검사한다 **[구현됨]**. 실제 출력 수치는 `metrics.json`·`job_runs` 로 기록한다(exp-01: 삼각형 7,774, 약 1.3 MB — 털 맵을 넣기 전, 리깅 끔 **[실험 결과]**).
3. Unity 에디터·기기에서 glTFast 로드 성공, 바닥 접지(최저점 y=0)와 정면(+Z), 털(§4.1, 작업 흐름으로 받은 GLB), 리깅본이면 클립 다섯 개 확인 스크린샷. **[계획]**
4. 품질 체크리스트 6항목(얼굴, 귀, 체형, 털 색상, 무늬, 텍스처 번짐/seam) 1~5점 기록 — [METRICS.md](../METRICS.md). **[계획]**
