# GLB 에셋 계약 (v1.0)

적용 대상: 추론 서비스가 만들고(`generation/tools/convert_asset.py`), Spring 이 `GET /api/v1/jobs/{jobId}/asset` 으로 서빙하며, Unity 가 glTFast 로 로드하는 `.glb` 파일. 세 트랙이 이 문서 하나를 기준으로 삼는다. **"제안값"** 으로 표시한 숫자는 계약 회의에서 확정하기 전까지 임시값이다([REVIEW_CHECKLIST.md](../api/REVIEW_CHECKLIST.md)).

상태: 변환기(`convert_asset.py`)와 glTFast 로드 검증은 팀이 별도 세션에서 수행했다고 보고됐으나 **이 리포에는 아직 없다** → 투입은 [계획](PLAN 0단계). Mock 서버의 `backend/storage/results/sample-dog.glb` 는 0바이트라 이 계약을 만족하지 않는다(직접 교체).

## 1. 파일 형식

| 항목 | 값 | 비고 |
|---|---|---|
| 포맷 | glTF 2.0 binary (`.glb`), 단일 파일 | 외부 `.bin`/이미지 참조 금지. 텍스처는 bufferView 에 임베드 |
| Content-Type | `model/gltf-binary` | API 응답 헤더 및 `AssetInfo.contentType` |
| 파일 크기 | ≤ 10 MB (제안값) | `AssetInfo.bytes` 로 노출 |
| 검증 | [glTF Validator](https://github.khronos.org/glTF-Validator/) 오류 0 | 경고는 허용하되 실험 기록에 남긴다 |
| 확장(extension) | 없음을 기본으로 한다 | Draco/KTX2 압축은 glTFast 지원 확인 후 [계획]. 쓰려면 Unity 패키지 추가 필요 |

## 2. 좌표계 · 단위 · 원점 · 방향

- Y-up, 오른손 좌표계(glTF 표준). **1 unit = 1 m**.
- 원점 (0, 0, 0) = 네 발바닥(접지면)의 중심. 모델의 최저점 y = 0, 바닥에 그대로 놓인다.
- 정면 = **+Z** (얼굴이 +Z 를 향한다). glTF 관례와 같다.
- 크기 정규화: 변환기는 모델 바운딩 박스의 최장 축을 **1.0 m** 로 맞춘다(제안값). 사진만으로 실제 크기를 알 수 없으므로 실제 크기는 앱에서 사용자가 조정한다(§7).
- glTFast 가 Unity 좌표계 변환을 처리하므로 앱은 축 변환을 추가하지 않는다. 배치 후 정면이 사용자를 향하도록 Y축 회전만 적용한다.

## 3. 메시 구성

| 항목 | 값 |
|---|---|
| `variant=base` 파일 | 노드 1개, 이름 `base`. 몸체 메시 |
| `variant=hair` 파일 | 노드 2개, 이름 `base`, `hair`. 털 메시가 추가된 완성본(파일 하나로 자급) |
| 삼각형 수 상한 | base ≤ 20,000 / hair ≤ 100,000 (제안값) |
| 정점 속성 | `POSITION`, `NORMAL`, `TEXCOORD_0` 필수. `TANGENT` 선택. 인덱스는 uint16 또는 uint32 |
| 머티리얼 | PBR metallic-roughness. `baseColorTexture` = `uv.png`. metallic 0, roughness 0.8~1.0 (제안값) |
| hair 머티리얼 | `alphaMode` = `MASK`(기본) 또는 `BLEND`, `doubleSided` = true |
| 애니메이션/스킨 | 없음(확장 기능, [계획]) |

노드 이름 `base`/`hair` 는 고정이다. Unity 는 `Transform.Find("hair")` 로 털 표시를 켜고 끌 수 있다.

## 4. 텍스처

- 해상도 ≤ 2048 × 2048 (제안값), 정사각형 권장. PNG 또는 JPEG 로 임베드. 색 공간 sRGB.
- base 1장, hair 1장(선택). 노멀맵·러프니스맵은 없음을 기본으로 한다.

## 5. 변환기 출력 (`generation/tools/convert_asset.py`)

| 입력 | 출력 |
|---|---|
| `mesh.obj` + `uv.png` (+ `hair.npz`) | `{jobId}-base.glb`, 선택 `{jobId}-hair.glb`, 각 GLB 옆에 `{같은 이름}.metrics.json` |

`metrics.json` 은 다음 다섯 필드를 가진다(이름 고정, [METRICS.md](../METRICS.md) 와 동일).

```json
{ "vertices": 12345, "triangles": 19876, "textureSize": 2048, "bytes": 5242880, "convertMs": 850 }
```

변환기는 §1~§4 를 스스로 검사하고 위반 시 0 이 아닌 종료 코드와 이유를 출력한다(검증 실패 = `CONVERSION_FAILED`).

## 6. 저장 · 서빙 규칙 (Spring)

- 저장 경로: real 워커는 `/infer` 가 돌려준 GLB 를 `backend/storage/results/{jobId}/base.glb` 로 복사한다 **[구현됨]** 2026-10-04. 복사 전에 glTF 2.0 바이너리 헤더(magic `glTF`, version 2)만 확인하고 아니면 `CONVERSION_FAILED` 다. §1~§4 검증은 변환기 몫이다. `hair.glb`, `*.metrics.json` 은 [계획]. Mock 은 `storage/results/sample-dog.glb` 하나를 모든 작업에 돌려준다.
- 응답 헤더: `Content-Type: model/gltf-binary`, `Content-Length`, `Content-Disposition: attachment; filename="{jobId}-{variant}.glb"`.
- `JobResponse.asset` 은 base 에셋만 설명한다. hair 는 `?variant=hair` 로 요청하고 없으면 404(`ASSET_NOT_FOUND`). hair 가용 여부를 응답에 노출하는 방식은 계약 회의 안건.

## 7. 앱 배치 규칙 (Unity)

- 캐시: `Application.persistentDataPath/models/{jobId}-{variant}.glb`. 같은 jobId 는 다시 받지 않는다.
- 기본 배치 스케일 **0.6** (모델 최장 축 1.0 m 기준 → 약 0.6 m). 사용자 핀치로 **0.2 ~ 1.5** 범위 조정.
- 바닥 평면(AR plane) 위에 원점(발바닥 중심)을 놓는다. 처음 배치 시 +Z 가 카메라를 향하도록 Y축 회전.
- 로딩 시간(`loadMs`)과 FPS 는 [METRICS.md](../METRICS.md) 의 이름으로 기록한다.

## 8. 검증 절차 (완료 기준)

1. `gltf-validator` 오류 0 — 결과 JSON 을 실험 기록에 첨부.
2. §3·§4·§1 의 상한(삼각형, 텍스처, 파일 크기) 통과 — `metrics.json` 으로 확인.
3. Unity 에디터에서 glTFast 로드 성공, 바닥 접지(최저점 y=0)와 정면(+Z) 확인 스크린샷.
4. 품질 체크리스트 6항목(얼굴, 귀, 체형, 털 색상, 무늬, 텍스처 번짐/seam) 1~5점 기록 — [METRICS.md](../METRICS.md).
