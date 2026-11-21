# Beside 계약서 v1.2 (통합본)

세 트랙(Backend · Unity · 3D Generation)이 지켜야 할 경계면을 한 파일에 모았다. 기계가 읽는 원본은 [api/openapi.yaml](api/openapi.yaml)이며 backend 계약 테스트(`JobV1ContractTest`)가 서버와 비교한다. 이 문서와 원본이 다르면 원본이 우선이고, 이 문서를 고친다. 상태 표기: **[구현됨]** / **[실험 결과]** / **[계획]**. "제안값"은 확정 전까지 임시값이다(2026-11-21 에 정한 것은 §7). 버전: `api-v1.0`(태그) → `api-v1.1`(큐 상한) → `api-v1.2`(검토 브랜치에서 고른 대기 정보·취소·공유·보관함·관리·평가, 브랜치 `feat/api-v1.2` 에 반영, 병합·태그 [계획]).

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
| `POST /api/v1/jobs` | multipart `photos` 1~10장, 장당 ≤ 5 MB, 전체 ≤ 20 MB(#3 확정), `image/jpeg \| image/png \| image/webp`. prod 는 파일 앞 바이트(JPEG·PNG·WEBP 시그니처)도 보고, 다른 프로파일은 선언된 Content-Type 만 본다(`upload.verify-magic`, #17). 헤더 `Idempotency-Key`(선택, 1~128자) | **202** + `Location: /api/v1/jobs/{jobId}` + `{ "jobId" }` | 400 `NO_PHOTOS` `TOO_MANY_PHOTOS` `UNSUPPORTED_IMAGE_TYPE` `INVALID_REQUEST`(없는 `petId` 포함, v1.2), 대표 사진 검사 400 7개(real·prod, §3), 413 `PAYLOAD_TOO_LARGE`, 500, 503 `QUEUE_FULL` + `Retry-After`(대기열 상한, 기본 꺼짐. 2026-11-21 #21 결정, `api-v1.1`) |
| `GET /api/v1/jobs/{jobId}` | 1~2초 간격 폴링 | **200** `JobResponse` | 404 `JOB_NOT_FOUND`, 500 |
| `GET /api/v1/jobs/{jobId}/asset?variant=base` | `variant` 는 `base` 뿐, 생략 시 `base`. `hair` 는 2026-11-21 계약에서 뺐다(#4) | **200** `model/gltf-binary`, `Content-Disposition: attachment; filename="{jobId}-{variant}.glb"`, `Content-Length` | 409 `JOB_NOT_COMPLETED`(미완료·실패), 404 `ASSET_NOT_FOUND`(완료됐지만 GLB 파일 없음), 400 `INVALID_REQUEST`(`base` 가 아닌 variant — `hair` 포함, 작업 상태와 상관없이), 404 `JOB_NOT_FOUND`, 500 |
| `POST /api/v1/jobs/{jobId}/retry` | 본문 없음 | **202** + `Location` + `{ "jobId" }`, 작업은 PENDING 으로 | 409 `JOB_NOT_FAILED`(FAILED 가 아님), 404, 500, 503 `QUEUE_FULL` + `Retry-After`(작업은 FAILED 그대로, `error.jobId` 는 그 작업. 2026-11-21 #21 결정, `api-v1.1`) |
| `GET /api/v1/jobs?limit=&cursor=` | `limit` 1~100(기본 20), `cursor` = 이전 `nextCursor` | **200** `{ items: JobResponse[], nextCursor: string\|null }` 최신순 | 400 `INVALID_REQUEST`, 500 |
| `GET /api/v1/healthz` | — | **200** `{ status: "ok", profile, workerType: "mock"\|"real" }`. `profile` 은 활성 Spring 프로파일을 활성화 순서대로 쉼표로 이은 값(공백 없음, 예 `mock`·`real`, 공개 서버는 `real,prod`, 활성 프로파일이 없으면 `mock`). 앱은 `mock`\|`real` 둘 중 하나라고 가정하지 않는다(워커 종류는 `workerType`) | — |
| `POST /api/v1/jobs/{jobId}/cancel` (v1.2) | 본문 없음. PENDING 일 때만 | **200** `JobResponse`(status=FAILED, `error.code=CANCELED`). 워커는 시작하지 않는다 | 409 `JOB_NOT_PENDING`(PROCESSING 은 GPU 연산을 멈출 수 없음, 이미 끝난·취소한 작업 포함), 404, 500 |

v1.2 의 웹용 엔드포인트(앱은 쓰지 않음, 형태는 [openapi.yaml](api/openapi.yaml)) — [구현됨] 브랜치 `feat/api-v1.2`(검토 브랜치 2026-10-30 → 2026-11-21 #11 로 고름):

- 공유 링크(tag share, 공개): `POST /api/v1/jobs/{jobId}/shares`(201, 완료 작업만), `GET /api/v1/shares/{token}`, `GET /api/v1/shares/{token}/asset`, `DELETE /api/v1/shares/{token}`(배포에서는 Basic 인증), 웹 뷰어 `/s/{token}`. 공유 쪽 오류는 404 `SHARE_NOT_FOUND` 하나이고 작업 id 를 드러내지 않는다.
- 보관함(tag pets, 배포에서는 Basic 인증): `POST/GET /api/v1/pets`, `GET/PATCH/DELETE /api/v1/pets/{petId}`(`?withJobs=true` 면 끝난 작업도 지움, 진행 중 작업이 있으면 409 `JOB_IN_PROGRESS`), `PUT /api/v1/jobs/{jobId}/pet`, `POST /api/v1/jobs` 의 선택 텍스트 파트 `petId`(없는 펫이면 400 `INVALID_REQUEST`, 앱 요청은 그대로). 404 `PET_NOT_FOUND`.
- 관리(tag admin, `/api/v1/admin/*`·웹 `/admin`, Basic 인증)와 품질 평가(tag quality, `GET·PUT /api/v1/jobs/{jobId}/ratings[/{rater}]`·웹 `/job/?id=`, Basic 인증).
- 받지 않은 것(#11): 작업 하나 지우기 `DELETE /api/v1/jobs/{jobId}`, `GET /api/v1/jobs/{jobId}/thumbnail`, `GET /api/v1/jobs/{jobId}/photos/{index}`, `JobResponse.thumbnailUrl`, `HealthResponse.inference`.

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
  "uploadedFiles": [ { "name": "dog1.jpg", "bytes": 705356 } ],
  "queue": { "ahead": 2, "reason": null },
  "estimate": { "remainingMs": 240000, "basedOnRuns": 12 },
  "petId": null
}
```

| 필드 | 규칙 |
|---|---|
| `status` | `PENDING → PROCESSING → COMPLETED \| FAILED`, `FAILED → (retry) → PENDING`, `PENDING → (cancel) → FAILED(CANCELED)`. 모르는 값은 UNKNOWN |
| `progress` | 0~1, 모르면 `null`. Mock: PENDING 0.0, PROCESSING 0.5, COMPLETED 1.0, FAILED null |
| `timings.queuedMs` | PENDING 경과. 진행 중이면 현재까지, 이후 고정 |
| `timings.processingMs` | PROCESSING 시작 전 `null`, 진행 중 경과, 종료 후 고정 |
| `timings.totalMs` | COMPLETED/FAILED 전 `null`, 이후 접수(또는 retry)~종료 |
| `asset` | COMPLETED 일 때만. `variant` 는 `base` 하나다(털 맵은 그 GLB 안, §4). `url` 은 **서버 루트 기준 상대 경로** → 앱이 baseUrl 과 결합(#1 유지) |
| `error` | FAILED 일 때만. `code` 는 §3 의 Job 실패 코드, `message` 는 개발자용(사용자에게 그대로 표시 금지) |
| `uploadedFiles` | 원본 파일명(경로 제거)과 크기. 서버 경로 없음 |
| `queue` | (v1.2) PENDING 일 때만. `ahead` = 이 작업보다 먼저 처리될 작업 수(생성 중인 작업 포함, 0 = 다음 차례). 대기열 상한을 켠 서버에서는 상한이 세는 PENDING 만 센다(`admission.stale-after-ms` 보다 오래된 행 제외). `reason` = `INFERENCE_OFFLINE`(GPU 추론 서비스가 UP 이 아님, real) 또는 `null`, 모르는 값은 무시. 그 외 상태 `null` |
| `estimate` | (v1.2) PENDING·PROCESSING 일 때만, **추정치**. `remainingMs` = 최근 완료 실행(같은 워커, 패스스루 제외, 20건)의 processingMs 중앙값 기준 남은 시간(GPU 1장 기준), `basedOnRuns` = 근거 실행 수. 근거 실행이 없으면 객체가 `null` |
| `petId` | (v1.2) 웹 보관함에서 넣은 반려동물. 앱에서 만든 작업은 `null`(미분류). 앱은 무시해도 된다 |

### 오류 응답 봉투 (모든 v1 오류)

```json
{ "error": { "code": "JOB_NOT_COMPLETED", "message": "developer text", "jobId": "uuid 또는 null" } }
```

상태 코드는 400 / 404 / 409 / 413 / 500 / 503 여섯 가지. 503 은 `QUEUE_FULL` 전용이다(2026-11-21 #21 결정, `api-v1.1` 에서 더함. 상한을 켠 서버만 보낸다). 429 `RATE_LIMITED` + `Retry-After`(클라이언트별 요청 제한)는 이름만 예약했다(2026-11-21 #22, 구현·한도는 [계획]). v0 `/api/jobs` 의 오류는 평면 `{ "error": "문자열" }` 이며 이 규칙을 따르지 않는다.

### Mock 서버 동작 (profile `mock`, 기본)

접수 → 2초 PENDING → 3초 PROCESSING → COMPLETED(샘플 GLB). 업로드 **파일명에 `fail`** 이 들어가면 FAILED `INFERENCE_FAILED`(실패 UI·retry 테스트용). 작업과 `Idempotency-Key` 는 서버 DB(H2)에 저장돼 재시작 후에도 남는다. 재시작 때 PENDING/PROCESSING 이던 작업은 FAILED `INTERNAL_ERROR` 로 바뀌며 retry 로 다시 실행한다. 샘플 GLB 는 현재 0바이트(교체 필요).

---

## 3. 오류 코드와 앱 표시 문구 — [구현됨]

### HTTP 오류 (`error.code`)

| code | HTTP | 상황 | 앱 표시 문구 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | 잘못된 variant/limit/cursor, 0바이트 파일, 깨진 multipart | 요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요. |
| `NO_PHOTOS` | 400 | `photos` 파트 없음 | 사진을 1장 이상 선택해 주세요. |
| `TOO_MANY_PHOTOS` | 400 | 11장 이상 | 사진은 최대 10장까지 올릴 수 있어요. |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | jpeg/png/webp 외(prod 는 파일 앞 바이트 시그니처도 봄, #17) | JPG, PNG, WEBP 사진만 사용할 수 있어요. |
| `UNREADABLE_IMAGE` | 400 | 대표 사진 검사: 이미지로 읽을 수 없음(디코딩 실패·화소 수 초과·5초 초과) | 대표 사진을 읽을 수 없어요. 다른 사진을 골라 주세요. |
| `IMAGE_TOO_SMALL` | 400 | 대표 사진 검사: 짧은 변 < 320 px (제안) | 대표 사진의 해상도가 너무 낮아요. 더 큰 사진을 골라 주세요. |
| `NO_ANIMAL_DETECTED` | 400 | 대표 사진 검사: 개·고양이 없음 | 대표 사진에서 강아지나 고양이를 찾지 못했어요. 반려동물이 잘 보이는 사진을 골라 주세요. |
| `TOO_MANY_ANIMALS` | 400 | 대표 사진 검사: 개·고양이 2마리 이상 | 대표 사진에 반려동물이 여러 마리 있어요. 한 마리만 나온 사진을 골라 주세요. |
| `ANIMAL_TOO_SMALL` | 400 | 대표 사진 검사: 동물 박스 긴 변 < 224 px (제안) | 반려동물이 너무 작게 나왔어요. 더 가까이에서 찍은 사진을 골라 주세요. |
| `IMAGE_TOO_DARK` | 400 | 대표 사진 검사: 동물 박스 밝기 90백분위 < 50 (제안) | 대표 사진이 너무 어두워요. 밝은 곳에서 찍은 사진을 골라 주세요. |
| `IMAGE_TOO_BLURRY` | 400 | 대표 사진 검사: 동물 박스 라플라시안 분산 < 50 (제안) | 대표 사진이 흔들렸어요. 선명한 사진을 골라 주세요. |
| `PAYLOAD_TOO_LARGE` | 413 | 장당 5 MB 또는 전체 20 MB 초과 | 사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요. |
| `JOB_NOT_FOUND` | 404 | 알 수 없는 jobId(서버 DB 를 지웠거나 다른 서버·프로파일에 연결한 경우 포함) | 작업을 찾을 수 없어요. 사진을 다시 올려 주세요. |
| `ASSET_NOT_FOUND` | 404 | 완료됐지만 GLB 파일 없음(보존 기간이 지나 지운 작업도 이 코드 — #23, 자동 삭제는 [계획]) | 요청한 모델 파일이 없어요. |
| `NOT_FOUND` | 404 | 알 수 없는 v1 경로 | 요청한 정보를 찾을 수 없어요. |
| `JOB_NOT_COMPLETED` | 409 | 미완료·실패 상태에서 asset 요청 | 아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요. |
| `JOB_NOT_FAILED` | 409 | FAILED 아닌 작업에 retry | 실패한 작업만 다시 시도할 수 있어요. |
| `JOB_NOT_PENDING` | 409 | PENDING 아닌 작업에 cancel(PROCESSING 은 GPU 연산을 멈출 수 없음) | 이미 생성이 시작돼 취소할 수 없어요. |
| `PET_NOT_FOUND` | 404 | 웹 보관함: 알 수 없는 petId | 반려동물을 찾을 수 없어요. 목록을 새로고침해 주세요. |
| `JOB_IN_PROGRESS` | 409 | 웹 보관함: 펫과 함께 지울 작업 중 PENDING·PROCESSING 이 있음(아무것도 지우지 않음) | 아직 만들고 있는 작업은 지울 수 없어요. 취소하거나 끝난 뒤 지워 주세요. |
| `INTERNAL_ERROR` | 500 | 예기치 않은 서버 오류 | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. |
| `QUEUE_FULL` | 503 | 대기 작업(PENDING)과 접수 중인 요청이 서버 상한(`admission.max-pending`, 기본 꺼짐)에 이름. `Retry-After` 초 뒤에 다시 보낸다. 2026-11-21 #21 결정, `api-v1.1` | 지금 생성 요청이 많아요. 잠시 후 다시 시도해 주세요. |

대표 사진 검사(`UNREADABLE_IMAGE` ~ `IMAGE_TOO_BLURRY`)는 첫 장만 202 전에 검사하고 처음 걸린 이유 하나를 돌려준다. real·prod 기본 켬, mock 기본 끔이라 같은 사진이 mock 에서는 202, real·prod 에서는 400 일 수 있다. 2026-11-21 결정(#19): 이 동작과 400 코드 7개를 확정하고, 기준값은 제안값 그대로 두고 팀 사진 판정표 뒤 다시 본다(자세한 규칙은 [api/ERROR_CODES.md](api/ERROR_CODES.md)). 서버 구현은 **[구현됨]**(2026-11-05 `feat/preprocess`, 2026-11-13 main 병합: 코드·테스트). VM(2 vCPU)의 검사 시간은 2차 부하 시험에 있다 **[실험 결과]**(`checkMs` 실행별 평균 381~483 ms, 같은 사진 반복, [perf/load/2026-11-18_vm-2nd.md](perf/load/2026-11-18_vm-2nd.md)). 메모리는 **[계획]**.

대기열 상한(`QUEUE_FULL`)은 2026-11-21 에 Backend 담당이 정했다(안건 #21, `api-v1.1`): 503 `QUEUE_FULL` + `Retry-After`, N=19·`Retry-After` 165 s(목표 최악 대기 W=180 s 규칙, c=1. 5차 부하 시험에서 S_busy 를 다시 재면 다시 계산할 수 있다). 서버 기본값은 꺼짐(`admission.max-pending=0`)이고 운영 서버는 Unity 새 빌드 설치와 5차 부하 시험 전까지 꺼 둔다 **[계획: 운영에서 켜기]**. 서버·웹 코드와 시험은 **[구현됨]**(PR #19), Unity 는 에디터·기기 확인 **[계획]**. 상한이 차 있으면 생성은 대표 사진 검사보다 먼저 503 을 받고, 같은 `Idempotency-Key` 의 요청은 상한과 상관없이 그 키의 작업을 받는다(첫 요청이 아직 접수 중이어도). 거절된 retry 의 작업은 FAILED 그대로다. 서버 설정 `admission.stale-after-ms`(기본은 정상 작업의 가장 긴 대기, prod·N 19·c 1 이면 2시간 56분)보다 오래된 PENDING 은 세지 않는다(자세한 규칙은 [api/ERROR_CODES.md](api/ERROR_CODES.md) '대기열 상한').

### Job 실패 (`JobResponse.error.code`, status=FAILED)

| code | 상황 | 앱 표시 문구 | retry |
|---|---|---|---|
| `INFERENCE_FAILED` | 3D 생성 실패, 입력 품질(real: 추론 서비스의 4xx·500). Mock 의 `fail` 트리거 | 3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요. | 예 |
| `INFERENCE_TIMEOUT` | 추론 요청이 `inference.timeout-ms`(120 s, §6.1) 초과 | 생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요. | 예 |
| `INFERENCE_UNAVAILABLE` | 추론 서버 연결 불가, 모델 미준비(501~504) | 생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `CONVERSION_FAILED` | OBJ/PNG → GLB 변환 실패, GLB 규격 위반, real 워커가 받은 GLB 가 없거나 glTF 2.0 이 아님 | 모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요. | 예 |
| `INTERNAL_ERROR` | 서버 재시작·워커 중단으로 작업이 끊김, 워커(mock·real)의 예기치 못한 오류, 서버가 GLB 를 쓰거나 결과 폴더로 복사하지 못함(디스크 등) | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 예 |
| `CANCELED` | 사용자가 PENDING 작업을 취소(POST /cancel). 실행 기록에 `FAILED:CANCELED` 로 남고 실패율 통계에서는 뺀다 | 취소한 작업이에요. 다시 시도하면 처음부터 만들어요. | 예(처음부터) |

모르는 코드는 일반 오류 문구로 처리한다. 문구는 제안이며 코드 이름은 유지한다.

---

## 4. GLB 에셋 계약 — [구현됨: 변환기·리깅] / [실험 결과: 삼각형·파일 크기] / [계획: gltf-validator·유효 샘플]

요약이다. 원본은 [asset/GLB_SPEC.md](asset/GLB_SPEC.md)(스킨·클립·털 맵 형식 포함). 변환기 `generation/tools/convert_asset.py` 와 리깅 `rig_transfer.py` 는 #1(2026-10-14)로 들어와 추론 서비스가 작업마다 부른다 **[구현됨]**. 두 도구의 자동 시험은 `generation/inference_service/test_app.py` 에 있다(합성 메시·합성 템플릿, 모델·GPU 없음): 털 맵을 GLB 안에 넣기·리깅본으로 옮기기(`FurMapsInGlbTest`·`RigTransferFurMapsTest`, PR #25), 업로드 → 내려받은 GLB 의 털 맵(`RealConversionCancelTest`), 리깅의 정점 순서·크기 상한(`RigTransferVertexOrderTest`·`RiggedResultTest`·`OversizeRiggedResultTest`). 좌표·노드 배치(GLB_SPEC §2·§3)를 직접 보는 시험은 없다 — [generation/tools/README.md](../generation/tools/README.md).

| 항목 | 값 |
|---|---|
| 포맷 | glTF 2.0 binary `.glb` 단일 파일, 텍스처 임베드, 확장 없음. `Content-Type: model/gltf-binary` |
| 좌표계 | **Y-up**, 오른손. **1 unit = 1 m**. 원점 = 네 발바닥 중심(최저점 y = 0). 정면 **+Z** |
| 크기 정규화 | 바운딩 박스 최장 축 1.0 m (제안값) |
| variant | `base` 하나. `hair` variant·hair GLB 는 없다(2026-11-21 #4) |
| 노드·스킨 | 정적(기본): 노드 1개 `base`, 스킨·클립 없음. 리깅(선택, 추론 서비스에 리깅 템플릿이 있을 때만): 리깅 템플릿의 노드(메시 + 관절, 이름은 템플릿이 정함)·스킨 1개·클립 5개(`Idle`·`Walk`·`Sit`·`TailWag`·`HeadTilt`). 관절 36개는 안건 #14 기록의 값이고 템플릿이 리포 밖이라 리포 시험으로 세지 않는다. 리깅이 실패하거나 크기 상한을 넘으면 정적 GLB 를 준다(#14, GLB_SPEC §3·§3.1) |
| 삼각형 상한 | ≤ 20,000 (제안값, exp-02·기기 FPS 측정 뒤 확정 — #3). 실제 출력 7,774 **[실험 결과]**(exp-01·2차 부하 시험) |
| 정점 속성 | `POSITION`, `NORMAL`, `TEXCOORD_0`, 리깅본은 + `JOINTS_0`·`WEIGHTS_0`(템플릿 것). 인덱스 uint16/uint32 |
| 머티리얼 | PBR metallic-roughness, baseColorTexture = `uv.png`, metallic 0, roughness 0.8~1.0 (제안값) |
| 텍스처 | ≤ 2048² (제안값, #3), PNG 임베드, sRGB. base color 1장(+ 아래 털 맵 2장은 linear 데이터, 같은 해상도 상한) |
| 털 맵 | 앱 셸 털용 길이·결 맵 두 장(PNG, BIN 청크)과 `furLength` 를 base GLB 루트 `extras.beside` = `{ hairLengthImage, hairFlowImage, furLength }` 에 싣는다(2026-11-21 #14). 변환기가 넣고 리깅본도 옮긴다 **[구현됨: PR #25, main 병합(2026-11-21)·Pod 반영 전]**. 앱은 작업 흐름으로 받은 GLB 에서만 읽는다. 기기 확인 **[계획]**. 형식은 GLB_SPEC §4.1 |
| 파일 크기 | ≤ 10 MB (제안값, #3). 실제 출력 1,310,876~1,327,400 B **[실험 결과]**(exp-01 — 리깅 없고 털 맵을 GLB 에 넣기 전의 출력) |
| 검증 | gltf-validator 오류 0 **[계획]**(exp-02), Unity glTFast 로드, 바닥 접지·정면 확인. 변환기는 삼각형·텍스처·파일 크기 상한을 넘으면 `CONVERSION_FAILED` 로 끝낸다 **[구현됨]** |
| 변환기 출력 | `base.glb` + `base.metrics.json { vertices, triangles, textureSize, bytes, convertMs }`(리깅한 GLB 를 쓰면 `bytes`·`convertMs` 를 그 기준으로 다시 씀) |
| 서버 저장 | real: `backend/storage/results/{jobId}/base.glb` [구현됨] — 받은 GLB 의 glTF 헤더만 확인하고 복사. Mock 은 `results/sample-dog.glb`(지금 0바이트, 교체는 직접 할 일) |
| 앱 배치 | 캐시 `persistentDataPath/models/{jobId}-{variant}.glb`. 기본 스케일 **0.6**, 핀치 **0.2~1.5**, 바닥 평면에 원점 접지, 초기 정면이 카메라를 향하도록 Y축 회전 |

---

## 5. 측정 항목 이름 (세 트랙 공통)

| 주체 | 파일 | 필드 | 상태 |
|---|---|---|---|
| 서버 | `backend/storage/events.jsonl` | `jobId, attempt, finishedAt, uploadBytes, uploadMs, queuedMs, processingMs, totalMs, workerType, result` + (2026-10-06 추가) `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs, glbBytes, passthrough, modelVersion` + (2026-11-04 추가, [구현됨] 2026-11-05) `checkMs, checkResult, preprocessMs, preprocessResult, preprocessVersion` | [구현됨]. 실제 모델 값은 [실험 결과]: VM 부하 시험 2~4차(2026-11-18~19, VM + Runpod A40, 같은 사진 반복)의 실행 폴더 `vm/events.jsonl`([perf/load/](perf/load/)). 사진 10장 이상은 [계획] |
| 서버 | H2 테이블 `job_runs` (`beside-{mock,real}.mv.db`, 실행마다 한 행, PK `jobId:attempt`) | 위 줄과 같은 필드를 snake_case 로 + `status, error_code`. 측정표는 관리 API `GET /api/v1/admin/runs?format=csv`(개발 PC 는 h2-console SQL 도 가능)로 뽑는다(METRICS §1.1) | [구현됨] 2026-10-06 |
| 추론 서비스 | `/infer` 응답 `metrics`, 실험 기록 | `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs` (+ 선택 `modelVersion`). `convertMs` 는 리깅이 성공한 실행이면 리깅 시간을 포함한다(#16) | 서비스가 채움 [구현됨](#1, `animallift_runner.py`·`convert_asset.py`), Spring 저장 [구현됨]. 값은 [실험 결과]: [exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)(4건 + 2차 부하 시험 539건, 리깅 없음) |
| 앱 | `persistentDataPath/metrics/metrics.csv` 헤더 순서 고정 | `jobId, uploadMs, waitMs, downloadMs, loadMs, e2eMs, avgFps, minFps, memMB, device`. 서버로 가져오기 `POST /api/v1/admin/client-metrics`(헤더가 정확히 같아야 함), 서버·추론과 합친 표 `GET /api/v1/admin/e2e?format=csv`(서버 uploadMs 는 `serverUploadMs`)는 METRICS §3.1 | 기록 코드 [구현됨](`MetricsRecorder.cs`, `JobManager.cs` 가 채움 — 실기기 실행 증빙은 리포에 없음). 측정값 0건, 기기 FPS 포함 [계획] · 서버 가져오기·결합 [구현됨](API v1.2) |
| 3D 품질 (사람, 1~5점, 평가자 2명 이상) | 작업 상세 웹 페이지 `/job/?id=` → `PUT /api/v1/jobs/{jobId}/ratings/{rater}`, H2 `job_ratings` | `faceShape`(얼굴 형태), `ears`(귀 모양/위치), `bodyProportion`(체형 비율), `furColor`(털 색상), `pattern`(무늬 재현), `textureArtifacts`(텍스처 번짐/seam). 측정표 `GET /api/v1/admin/ratings?format=csv`, modelVersion 별 평균 `GET /api/v1/admin/ratings/summary`(METRICS §4.1) | 저장·내보내기 [구현됨](API v1.2) · 실제 평가 [계획] (exp-01 표의 품질 칸은 비어 있음) |

단위: 시간 ms 정수, 크기 bytes/MB 정수. 환경(GPU, 드라이버, 해상도, 커밋) 함께 기록. 자세한 정의는 [METRICS.md](METRICS.md).

---

## 6. 내부 계약: Spring real 워커 ↔ Python 추론 서비스 — [구현됨: Spring 호출·FastAPI·AnimalLift 모델 모드] / [실험 결과: VM ↔ Runpod A40]

Unity 는 호출하지 않는다. `POST /infer`(경로 모드)는 같은 호스트 또는 공유 볼륨을 가정한다. 디스크를 공유하지 않는 분리 배포는 §6.1 업로드 모드로 한다(2026-11-21 #6 결정, 운영 VM 은 업로드 모드). 실제 모델로 돈 기록은 [exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)과 VM 부하 시험 2~4차([perf/load/](perf/load/))다 **[실험 결과]**. 모델은 첫 사진 1장만 쓴다.

`POST /infer`

```json
요청  { "jobId": "uuid", "imagePaths": ["/abs/path/dog1.jpg"], "options": { "hair": false } }
응답  { "glbPath": "/abs/results/{jobId}/base.glb", "hairGlbPath": null,
        "metrics": { "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs" },
        "passthrough": false, "modelVersion": "animallift-baseline@3f2a1c9" }
```

`modelVersion` 은 선택 필드(2026-10-06 추가, 없으면 null)로 GLB 를 만든 모델·가중치 버전이다. 끝의 `+rig` 는 그 작업에서 리깅이 성공했을 때만 붙는다(2026-11-21 #18, main 동작). Spring 은 `metrics`·`passthrough`·`modelVersion` 을 실행마다 `job_runs` 행과 `events.jsonl` 줄에 저장한다(§5). `options.hair` 와 `hairGlbPath` 는 자리만 남았다: Spring 은 늘 `false` 를 보내고 서비스는 늘 `null` 을 준다(hair variant 는 #4 로 뺐다).

| HTTP | `detail.code` | Spring 이 Job 에 기록 |
|---|---|---|
| 400 | `INFERENCE_FAILED` (사진 경로가 파일이 아님) | `INFERENCE_FAILED` |
| 500 | `INFERENCE_FAILED` (모델 실패·사진 읽기 실패, 취소된 실행) | `INFERENCE_FAILED` |
| 500 | `CONVERSION_FAILED` | `CONVERSION_FAILED` |
| 501 | `INFERENCE_UNAVAILABLE` (모델 미설정: 모델 경로·`BESIDE_SAMPLE_GLB` 모두 없음) | `INFERENCE_UNAVAILABLE` |
| 503 | `INFERENCE_UNAVAILABLE` (모델 불러오는 중·불러오기 실패) | `INFERENCE_UNAVAILABLE` |
| 501~504 | 없음·모르는 코드 | `INFERENCE_UNAVAILABLE` |
| 그 밖의 4xx·5xx | 없음·모르는 코드 | `INFERENCE_FAILED` |
| 200 | — (`glbPath` 없음·파일 없음·glTF 2.0 바이너리 아님) | `CONVERSION_FAILED` |
| 연결 실패 / 타임아웃 | — | `INFERENCE_UNAVAILABLE` / `INFERENCE_TIMEOUT` |

`detail.code` 가 Job 실패 코드면 HTTP 상태와 관계없이 그 코드를 쓴다. Spring 은 `/infer` 를 `inference.max-concurrency`(기본 1)건만 동시에 부르고, `error.message` 에 경로·내부 주소·추론 서비스 원문 메시지를 싣지 않는다(서버 로그에만).

`GET /healthz` → `{ "status": "ok", "device": "cuda|cpu|unknown", "modelLoaded": false, "passthrough": false }`. 환경변수 `BESIDE_SAMPLE_GLB` 가 있으면 모델 없이 샘플 경로를 돌려주는 패스스루 모드. 그 밖의 선택 필드(`tokenOk`·`busyForMs`·`loadError`·`instanceId` — 2026-11-20, `modelVersion` — API v1.2 병합 2026-11-21: 로드된 모델, `+rig` 는 붙이지 않음 #18)는 [inference_service/README.md](../generation/inference_service/README.md) 'GET /healthz'.
필드 추가(2026-11-20) **[구현됨: FastAPI·Spring, 시험으로 확인]**: `tokenOk`(요청에 `X-Inference-Token` 이 있을 때만 `/infer` 가 그 값을 받을지 true/false, 없으면 null)·`busyForMs`(아직 도는 가장 오래된 `predict`·패스스루 모의 생성이 시작한 뒤 ms, 없으면 0). 응답은 여전히 200 이고, Spring 은 두 필드로 Discord 알림만 보낸다(`inference.stuck-alert-ms`, 기본 120000). 작업을 보낼지 정하는 UP 판정은 바꾸지 않는다.
필드 추가(2026-11-20, 장애 훈련 FD18) **[구현됨: FastAPI·Spring, 시험으로 확인]**: `loadError`(모델을 불러오지 못한 이유 한 줄 — 예외 이름과 짧은 메시지, 경로·토큰 없음, 최대 200자. 불러오는 중·불러온 뒤·패스스루는 null). `status`·`modelLoaded`·UP 판정은 그대로이고(작업은 지금처럼 PENDING 으로 기다린다), Spring 은 생기고 풀릴 때 Discord 알림(🧩, 예외 이름만)을 한 번씩 보낸다.
필드 추가(2026-11-20, 장애 훈련 FD16b) **[구현됨: FastAPI·Spring, 시험으로 확인]**: `instanceId`(서비스 프로세스마다 새로 정하는 무작위 16진 12자). 상태·UP 판정·알림은 그대로이고, prod Spring 은 요청을 보낼 때 본 값과 다른 `instanceId` 가 보이면 끝나지 않은 그 요청을 포기한다(`POST` 중 `INFERENCE_UNAVAILABLE`, 내려받는 중 `CONVERSION_FAILED`, [inference_service/README.md](../generation/inference_service/README.md) 'Spring 쪽 매핑 추가').

### 6.1 업로드 모드 — [구현됨: Spring·FastAPI, VM ↔ Runpod Pod 운영] / [실험 결과: VM 부하 시험 2~4차] / [계획: VM ↔ 팀원 GPU PC 배치] (2026-11-21 결정, REVIEW_CHECKLIST #6 (b))

2026-10-30 에 (b) 로 제안해 구현했고, 2026-11-21 에 (b) 업로드 모드로 결정했다(계약 회의 대신 Backend 담당). 결정한 모양은 사진 멀티파트 업로드 `POST /infer/upload` → JSON `glbUrl` → `GET /artifacts/{jobId}/base.glb`·`DELETE /artifacts/{jobId}` 다(GLB 바이너리를 응답 본문으로 주지 않는다). 경로 모드(`/infer`)는 같은 호스트(개발 PC·GPU PC 한 대 배치)용으로 남긴다.

Spring 은 클라우드 VM, 추론 서비스는 Runpod Pod(대안: 팀원 GPU PC, 둘 다 Tailscale 사설망)라 디스크를 공유하지 않는다. Spring `inference.transfer=path|upload`(기본 `path`, VM compose 는 `upload`)로 고른다. 자세한 표는 [inference_service/README.md](../generation/inference_service/README.md) '전송 모드'.

```text
POST   /infer/upload            multipart: request = {"jobId", "options": {"hair": false}} (application/json, 파일명 없음)
                                           photos  = 사진 파일 1~10개 (원래 파일명)
       → 200 /infer 와 같은 필드 + "glbUrl": "/artifacts/{jobId}/base.glb" (서비스 기준 상대 경로)
GET    /artifacts/{jobId}/base.glb   → 200 model/gltf-binary (jobId [A-Za-z0-9-]{1,64}, 파일명 화이트리스트, 그 밖 404)
DELETE /artifacts/{jobId}            → 204 작업 폴더(업로드 사진·결과) 삭제, 없어도 204
```

- 인증(두 모드 공통): 서비스에 `BESIDE_INFERENCE_TOKEN` 이 있으면 `/infer`·`/infer/upload`·`/artifacts/*` 는 헤더 `X-Inference-Token` 이 같아야 한다(상수 시간 비교, 아니면 401). `/healthz` 는 열어 둔다. Spring 은 `inference.token` 이 비어 있지 않으면 두 모드 모두 이 헤더를 보낸다.
- 패스스루에서도 업로드 모드가 동작한다(샘플을 `{jobId}/base.glb` 로 복사해 artifact 로 서빙).
- Spring 은 `glbUrl` 을 `inference.base-url` 뒤에 붙여 GET 하고 `storage/tmp/{jobId}/base.glb`(`storage.tmp.dir`)에 받은 뒤 경로 모드와 같은 헤더 확인·복사를 한다. 끝나면(성공·실패 모두) 임시 파일을 지우고 `DELETE /artifacts/{jobId}` 를 보낸다. DELETE 실패는 로그만 남긴다.

| 상황 | 모드 | Spring 이 Job 에 기록 |
|---|---|---|
| HTTP 401·403, `detail.code` 없음·모르는 코드 | 두 모드 | `INFERENCE_UNAVAILABLE` |
| 200 인데 `glbUrl` 없음·`/` 로 시작하지 않음 | 업로드 | `CONVERSION_FAILED` |
| `GET glbUrl` 이 200 아님·연결 실패·시간 초과 | 업로드 | `CONVERSION_FAILED` |
| 그 밖 | 업로드 | 위 표 그대로 |

Spring 설정: `application-real.properties` 의 `inference.base-url`(기본 `http://localhost:8001`), `inference.timeout-ms`(기본 120000 — 2026-11-21 #7 로 600000 에서 줄였다. 그 작업 자신의 요청에만 걸리고 PENDING 대기는 세지 않는다. 앱의 최대 대기 600 s 는 그대로. 운영 VM 은 이 값을 담은 main 을 배포한 뒤부터 [계획]), `inference.max-concurrency`(기본 1), `inference.transfer`(기본 `path`), `inference.token`(기본 빈 값 = 헤더 안 보냄), `storage.result.dir`(기본 `storage/results`), `storage.tmp.dir`(기본 `storage/tmp`, 업로드 모드의 임시 GLB).

---

## 7. 결정 기록 (2026-11-21)

안건 #1~#23 은 2026-11-21 에 계약 회의를 열지 않고 Backend 담당(저장소 관리자)이 정했다. 결정 전문은 [api/REVIEW_CHECKLIST.md](api/REVIEW_CHECKLIST.md) 의 '결정' 칸, 버전별 정리는 [api/CHANGELOG.md](api/CHANGELOG.md).

- `api-v1.0`(2026-11-21 태그)에 넣은 것: `hair` variant 삭제(#4), GLB_SPEC 을 실제 출력에 맞춤(#14), 에셋 상한은 제안값 유지·hair 상한 삭제(#3), 프로파일 차이 명시(#17·#19), 429 `RATE_LIMITED` 이름 예약(#22), 내부 계약 — 업로드 모드(#6)·`inference.timeout-ms` 120 s(#7)·`+rig`(#18), 측정 문서 표기(#16). #7 의 나머지(retry 횟수 제한 없음, 서버 재시작 때 끊긴 작업은 FAILED `INTERNAL_ERROR` + retry, 폴링 1~2초, 앱 최대 대기 600 s)와 #1·#2·#5·#8·#9·#10·#13·#15·#20 은 지금 문서 그대로 확정.
- `api-v1.1` 에 넣는 것: 작업 대기열 상한(503 `QUEUE_FULL` + `Retry-After`, N=19·165 s, 기본 꺼짐 — #21, PR #19). 운영에서 켜는 것은 Unity 새 빌드와 5차 부하 시험 뒤 **[계획]**.
- `api-v1.2` 에 넣는 것: v1.1 검토 브랜치에서 고른 기능 — 대기 정보(`queue`·`estimate`, 상한을 켜면 상한과 같은 행을 셈)·대기 취소·공유 링크·반려동물 보관함·관리 API/화면·품질 평가와 오류 코드 `CANCELED`·`JOB_NOT_PENDING`·`JOB_IN_PROGRESS`·`SHARE_NOT_FOUND`·`PET_NOT_FOUND`(#11·#12). 작업 삭제·썸네일·입력 사진 엔드포인트와 `thumbnailUrl` 은 뺐다. `+rig` 는 그 작업의 리깅이 성공했을 때만(#18, 추론 서비스 `/healthz` 의 `modelVersion` 도 `+rig` 없이). 서버·웹과 시험 **[구현됨]**(브랜치 `feat/api-v1.2`), main 병합·태그·VM 배포는 **[계획]**, Unity 는 바꾸지 않음(새 필드·코드를 무시·일반 문구로 처리).
- 남은 것 **[계획]**: 보존 정책의 자동 삭제 구현(사진 7일·GLB 30일, 공개 베타 전 — #23), 요청 제한의 키·한도·구현(#22), 앱에서 `queue`·`estimate`·취소 쓰기.

## 8. 원본 문서

[api/openapi.yaml](api/openapi.yaml) · [api/ERROR_CODES.md](api/ERROR_CODES.md) · [asset/GLB_SPEC.md](asset/GLB_SPEC.md) · [METRICS.md](METRICS.md) · [../generation/inference_service/README.md](../generation/inference_service/README.md) · [api/CHANGELOG.md](api/CHANGELOG.md)
