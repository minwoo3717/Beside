# Beside 계약서 v1.0 (통합본)

세 트랙(Backend · Unity · 3D Generation)이 지켜야 할 경계면을 한 파일에 모았다. 기계가 읽는 원본은 [api/openapi.yaml](api/openapi.yaml)이며 backend 계약 테스트(`JobV1ContractTest`)가 서버와 비교한다. 이 문서와 원본이 다르면 원본이 우선이고, 이 문서를 고친다. 상태 표기: **[구현됨]** / **[계획]**. "제안값"은 계약 회의에서 확정 전까지 임시값이다.

---

## 1. 변경 규칙

- 응답 필드 **추가**: PR 설명에 한 줄 기록 후 자유. 클라이언트는 모르는 필드를 무시한다.
- 기존 필드 **이름/타입 변경·삭제, enum 값 변경, 상태 코드 변경**: 주 1회 계약 회의에서만 결정 → [api/CHANGELOG.md](api/CHANGELOG.md) 기록 → `git tag api-v1.x`.
- 모든 enum: 클라이언트는 알 수 없는 값을 **UNKNOWN** 으로 처리한다(크래시 금지).
- 서버 내부 경로(`resultPath`, 업로드 경로)는 어떤 응답에도 노출하지 않는다.
- v0 `/api/jobs` 는 동결. Unity 가 v1 으로 옮긴 뒤 회의에서 제거 시점 결정.
- 순서: `openapi.yaml` 수정 → 코드 → 계약 테스트 통과 → CHANGELOG → Unity DTO·FastAPI 스키마 동기화.

---

## 2. API v1 (Unity ↔ Spring) — [구현됨]

base URL 예: `http://<PC IP>:8080`. 모든 경로는 `/api/v1` 아래. 인증 없음(캡스톤 범위).

| 메서드·경로 | 요청 | 성공 | 오류 |
|---|---|---|---|
| `POST /api/v1/jobs` | multipart `photos` 1~10장, 장당 ≤ 5 MB, 전체 ≤ 20 MB, `image/jpeg \| image/png \| image/webp`. 헤더 `Idempotency-Key`(선택, 1~128자) | **202** + `Location: /api/v1/jobs/{jobId}` + `{ "jobId" }` | 400 `NO_PHOTOS` `TOO_MANY_PHOTOS` `UNSUPPORTED_IMAGE_TYPE` `INVALID_REQUEST`, 413 `PAYLOAD_TOO_LARGE`, 500 |
| `GET /api/v1/jobs/{jobId}` | 1~2초 간격 폴링 | **200** `JobResponse` | 404 `JOB_NOT_FOUND`, 500 |
| `GET /api/v1/jobs/{jobId}/asset?variant=base\|hair` | `variant` 생략 시 `base` | **200** `model/gltf-binary`, `Content-Disposition: attachment; filename="{jobId}-{variant}.glb"`, `Content-Length` | 409 `JOB_NOT_COMPLETED`(미완료·실패), 404 `ASSET_NOT_FOUND`(없는 variant, Mock 은 hair 없음), 400 잘못된 variant, 404 `JOB_NOT_FOUND`, 500 |
| `POST /api/v1/jobs/{jobId}/retry` | 본문 없음 | **202** + `Location` + `{ "jobId" }`, 작업은 PENDING 으로 | 409 `JOB_NOT_FAILED`(FAILED 가 아님), 404, 500 |
| `GET /api/v1/jobs?limit=&cursor=` | `limit` 1~100(기본 20), `cursor` = 이전 `nextCursor` | **200** `{ items: JobResponse[], nextCursor: string\|null }` 최신순 | 400 `INVALID_REQUEST`, 500 |
| `GET /api/v1/healthz` | — | **200** `{ status: "ok", profile: "mock"\|"real", workerType: "mock"\|"real" }` | — |

### JobResponse

```json
{
  "id": "uuid",
  "status": "PENDING | PROCESSING | COMPLETED | FAILED",
  "progress": 0.5,
  "createdAt": "2026-10-04T05:53:03Z",
  "updatedAt": "2026-10-04T05:53:09Z",
  "timings": { "queuedMs": 2015, "processingMs": 3000, "totalMs": 5015 },
  "asset": { "url": "/api/v1/jobs/{jobId}/asset?variant=base", "bytes": 5242880, "variant": "base", "contentType": "model/gltf-binary" },
  "error": { "code": "INFERENCE_FAILED", "message": "developer text" },
  "uploadedFiles": [ { "name": "dog1.jpg", "bytes": 705356 } ]
}
```

| 필드 | 규칙 |
|---|---|
| `status` | `PENDING → PROCESSING → COMPLETED \| FAILED`, `FAILED → (retry) → PENDING`. 모르는 값은 UNKNOWN |
| `progress` | 0~1, 모르면 `null`. Mock: PENDING 0.0, PROCESSING 0.5, COMPLETED 1.0, FAILED null |
| `timings.queuedMs` | PENDING 경과. 진행 중이면 현재까지, 이후 고정 |
| `timings.processingMs` | PROCESSING 시작 전 `null`, 진행 중 경과, 종료 후 고정 |
| `timings.totalMs` | COMPLETED/FAILED 전 `null`, 이후 접수(또는 retry)~종료 |
| `asset` | COMPLETED 일 때만, base 에셋만 설명. `url` 은 **서버 루트 기준 상대 경로** → 앱이 baseUrl 과 결합 |
| `error` | FAILED 일 때만. `code` 는 §3 의 Job 실패 코드, `message` 는 개발자용(사용자에게 그대로 표시 금지) |
| `uploadedFiles` | 원본 파일명(경로 제거)과 크기. 서버 경로 없음 |

### 오류 응답 봉투 (모든 v1 오류)

```json
{ "error": { "code": "JOB_NOT_COMPLETED", "message": "developer text", "jobId": "uuid 또는 null" } }
```

상태 코드는 400 / 404 / 409 / 413 / 500 다섯 가지. v0 `/api/jobs` 의 오류는 평면 `{ "error": "문자열" }` 이며 이 규칙을 따르지 않는다.

### Mock 서버 동작 (profile `mock`, 기본)

접수 → 2초 PENDING → 3초 PROCESSING → COMPLETED(샘플 GLB). 업로드 **파일명에 `fail`** 이 들어가면 FAILED `INFERENCE_FAILED`(실패 UI·retry 테스트용). `Idempotency-Key` 는 프로세스 생존 범위에서만 기억. 샘플 GLB 는 현재 0바이트(교체 필요).

---

## 3. 오류 코드와 앱 표시 문구 — [구현됨]

### HTTP 오류 (`error.code`)

| code | HTTP | 상황 | 앱 표시 문구 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | 잘못된 variant/limit/cursor, 0바이트 파일, 깨진 multipart | 요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요. |
| `NO_PHOTOS` | 400 | `photos` 파트 없음 | 사진을 1장 이상 선택해 주세요. |
| `TOO_MANY_PHOTOS` | 400 | 11장 이상 | 사진은 최대 10장까지 올릴 수 있어요. |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | jpeg/png/webp 외 | JPG, PNG, WEBP 사진만 사용할 수 있어요. |
| `PAYLOAD_TOO_LARGE` | 413 | 장당 5 MB 또는 전체 20 MB 초과 | 사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요. |
| `JOB_NOT_FOUND` | 404 | 알 수 없는 jobId(서버 재시작 포함) | 작업을 찾을 수 없어요. 사진을 다시 올려 주세요. |
| `ASSET_NOT_FOUND` | 404 | 완료됐지만 해당 variant 파일 없음 | 요청한 모델 파일이 없어요. |
| `NOT_FOUND` | 404 | 알 수 없는 v1 경로 | 요청한 정보를 찾을 수 없어요. |
| `JOB_NOT_COMPLETED` | 409 | 미완료·실패 상태에서 asset 요청 | 아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요. |
| `JOB_NOT_FAILED` | 409 | FAILED 아닌 작업에 retry | 실패한 작업만 다시 시도할 수 있어요. |
| `INTERNAL_ERROR` | 500 | 예기치 않은 서버 오류 | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. |

### Job 실패 (`JobResponse.error.code`, status=FAILED)

| code | 상황 | 앱 표시 문구 | retry |
|---|---|---|---|
| `INFERENCE_FAILED` | 3D 생성 실패, 입력 품질. Mock 의 `fail` 트리거 | 3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요. | 예 |
| `INFERENCE_TIMEOUT` | 추론이 `inference.timeout-ms` 초과 | 생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요. | 예 |
| `INFERENCE_UNAVAILABLE` | 추론 서버 연결 불가, real 워커 미구현(현재 스텁) | 생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `CONVERSION_FAILED` | OBJ/PNG → GLB 변환 실패, GLB 규격 위반 | 모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요. | 예 |

모르는 코드는 일반 오류 문구로 처리한다. 문구는 제안이며 코드 이름은 유지한다.

---

## 4. GLB 에셋 계약 — [계획] (변환기·유효 샘플 투입 전)

| 항목 | 값 |
|---|---|
| 포맷 | glTF 2.0 binary `.glb` 단일 파일, 텍스처 임베드, 확장 없음. `Content-Type: model/gltf-binary` |
| 좌표계 | **Y-up**, 오른손. **1 unit = 1 m**. 원점 = 네 발바닥 중심(최저점 y = 0). 정면 **+Z** |
| 크기 정규화 | 바운딩 박스 최장 축 1.0 m (제안값) |
| 노드 | `variant=base`: 노드 1개 `base`. `variant=hair`: 노드 2개 `base`, `hair` (파일 하나로 자급) |
| 삼각형 상한 | base ≤ 20,000 / hair ≤ 100,000 (제안값) |
| 정점 속성 | `POSITION`, `NORMAL`, `TEXCOORD_0` 필수, `TANGENT` 선택, 인덱스 uint16/uint32 |
| 머티리얼 | PBR metallic-roughness, baseColorTexture = `uv.png`, metallic 0, roughness 0.8~1.0 (제안값). hair: `alphaMode MASK/BLEND`, `doubleSided` |
| 텍스처 | ≤ 2048² (제안값), PNG/JPEG, sRGB. base 1장 + hair 1장(선택) |
| 파일 크기 | ≤ 10 MB (제안값) |
| 검증 | gltf-validator 오류 0, Unity glTFast 로드, 바닥 접지·정면 확인 |
| 변환기 출력 | `{jobId}-base.glb` (+ `-hair.glb`) + `metrics.json { vertices, triangles, textureSize, bytes, convertMs }` |
| 서버 저장 | `backend/storage/results/{jobId}/base.glb`, `hair.glb` (real, 계획). Mock 은 `results/sample-dog.glb` |
| 앱 배치 | 캐시 `persistentDataPath/models/{jobId}-{variant}.glb`. 기본 스케일 **0.6**, 핀치 **0.2~1.5**, 바닥 평면에 원점 접지, 초기 정면이 카메라를 향하도록 Y축 회전 |

애니메이션·리깅은 확장 기능(없음).

---

## 5. 측정 항목 이름 (세 트랙 공통)

| 주체 | 파일 | 필드 | 상태 |
|---|---|---|---|
| 서버 | `backend/storage/events.jsonl` | `jobId, uploadBytes, uploadMs, queuedMs, processingMs, totalMs, workerType, result` | [계획] (`timings` 는 [구현됨]) |
| 추론 서비스 | `/infer` 응답 `metrics`, 실험 기록 | `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs` | [계획] |
| 앱 | `persistentDataPath/metrics/metrics.csv` 헤더 순서 고정 | `jobId, uploadMs, waitMs, downloadMs, loadMs, e2eMs, avgFps, minFps, memMB, device` | [계획] |
| 3D 품질 (사람, 1~5점, 평가자 2명 이상) | 실험 기록 표 | `faceShape`(얼굴 형태), `ears`(귀 모양/위치), `bodyProportion`(체형 비율), `furColor`(털 색상), `pattern`(무늬 재현), `textureArtifacts`(텍스처 번짐/seam) | [계획] |

단위: 시간 ms 정수, 크기 bytes/MB 정수. 환경(GPU, 드라이버, 해상도, 커밋) 함께 기록. 자세한 정의는 [METRICS.md](METRICS.md).

---

## 6. 내부 계약: Spring real 워커 ↔ Python 추론 서비스 — [구현됨: 스텁] / [계획: 모델]

Unity 는 호출하지 않는다. 같은 호스트 또는 공유 볼륨 가정(분리 배포 시 바이너리 전송으로 변경, 회의 안건).

`POST /infer`

```json
요청  { "jobId": "uuid", "imagePaths": ["/abs/path/dog1.jpg"], "options": { "hair": false } }
응답  { "glbPath": "/abs/results/{jobId}/base.glb", "hairGlbPath": null,
        "metrics": { "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs" },
        "passthrough": false }
```

| HTTP | `detail.code` | Spring 이 Job 에 기록 |
|---|---|---|
| 400 | `INFERENCE_FAILED` | `INFERENCE_FAILED` |
| 500 | `CONVERSION_FAILED` | `CONVERSION_FAILED` |
| 501 | `INFERENCE_UNAVAILABLE` (모델 미구현, 현재 기본) | `INFERENCE_UNAVAILABLE` |
| 연결 실패 / 타임아웃 | — | `INFERENCE_UNAVAILABLE` / `INFERENCE_TIMEOUT` |

`GET /healthz` → `{ "status": "ok", "device": "cuda|cpu|unknown", "modelLoaded": false, "passthrough": false }`. 환경변수 `BESIDE_SAMPLE_GLB` 가 있으면 모델 없이 샘플 경로를 돌려주는 패스스루 모드.

Spring 설정: `application-real.properties` 의 `inference.base-url`(기본 `http://localhost:8001`), `inference.timeout-ms`(기본 600000).

---

## 7. 회의에서 결정할 것 (요약)

1. `asset.url` 상대 경로 유지 여부 · 2. GIF/HEIC 허용 여부 · 3. 숫자 상한 확정 · 4. hair variant 의 v1.0 포함과 노출 방식 · 5. Idempotency-Key 유지(H2 영속화) 여부 · 6. Spring↔Python 파일 공유 vs 바이너리 전송 · 7. 폴링·retry 상한 · 8. healthz 경로·`apiVersion` 추가 · 9. 목록 API 공개 범위 · 10. v0 제거 시점. 상세와 결정 칸은 [api/REVIEW_CHECKLIST.md](api/REVIEW_CHECKLIST.md).

## 8. 원본 문서

[api/openapi.yaml](api/openapi.yaml) · [api/ERROR_CODES.md](api/ERROR_CODES.md) · [asset/GLB_SPEC.md](asset/GLB_SPEC.md) · [METRICS.md](METRICS.md) · [../generation/inference_service/README.md](../generation/inference_service/README.md) · [api/CHANGELOG.md](api/CHANGELOG.md)
