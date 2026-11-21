# API 계약 변경 이력

형식: `## vX.Y — YYYY-MM-DD (git tag api-vX.Y)` 아래에 `추가 / 변경 / 삭제` 로 나눠 적는다. "변경·삭제"는 주 1회 계약 회의에서 결정된 것만 올린다. 필드 "추가"는 PR 에 한 줄 기록하면 되지만, 다음 태그를 올릴 때 여기에 모아 적는다.

## v1.2 — (git tag api-v1.2, 병합 뒤)

review/api-v1.1 병합 직후의 초안(검토 브랜치의 'v1.1 (제안)' 절 그대로). 2026-11-21 결정(#11·#12)대로 고른 것만 남기는 정리는 다음 커밋들에서 한다.

- 응답 필드 추가 `JobResponse`: `queue {ahead, reason}`(PENDING 만), `estimate {remainingMs, basedOnRuns}`(PENDING·PROCESSING, 근거 없으면 null), `petId`, `thumbnailUrl`. 앱은 모르는 필드를 무시한다.
- 요청 추가: `POST /jobs` 의 선택 multipart 텍스트 파트 `petId`.
- 엔드포인트 추가 — 작업: `POST /jobs/{id}/cancel`(PENDING 만, 결과는 FAILED + `CANCELED`), `DELETE /jobs/{id}`, `PUT /jobs/{id}/pet`, `GET /jobs/{id}/thumbnail`, `GET /jobs/{id}/photos/{index}`, `GET /jobs/{id}/ratings`, `PUT /jobs/{id}/ratings/{rater}`.
- 엔드포인트 추가 — 공유: `POST /jobs/{id}/shares`, `GET /shares/{token}`, `GET /shares/{token}/asset`, `DELETE /shares/{token}` (+ 웹 페이지 `/s/{token}`, API 아님).
- 엔드포인트 추가 — 보관함: `POST·GET /pets`, `GET·PATCH·DELETE /pets/{petId}`. 새 enum `species`: `DOG | CAT | OTHER`(모르는 값은 UNKNOWN 처리).
- 엔드포인트 추가 — 관리(팀 전용, 배포에서는 Basic 인증): `GET /admin/overview`, `GET /admin/runs`(json·csv), `GET /admin/stats`, `GET /admin/jobs`, `GET /admin/jobs/{id}`, `GET /admin/ratings`, `GET /admin/ratings/summary`, `GET·POST /admin/client-metrics`, `GET /admin/e2e`.
- 오류 코드 추가: HTTP `JOB_NOT_PENDING`(409), `JOB_IN_PROGRESS`(409), `SHARE_NOT_FOUND`(404), `PET_NOT_FOUND`(404). Job 실패 코드 `CANCELED`(retry 가능). 앱은 모르는 코드를 일반 문구로 보여 준다(`INTERNAL_ERROR` 추가 선례).
- 동작 변경(코드·형태 그대로): v1 에서 본문이 없거나 JSON 이 아닌 요청이 500 대신 400 `INVALID_REQUEST`. prod 프로파일에서는 사진 내용이 JPEG/PNG/WEBP 시그니처가 아니어도 `UNSUPPORTED_IMAGE_TYPE`(main 에 이미 있음, 여기서 문서화). prod 에서 GPU 추론 서비스가 응답하지 않으면 real 작업이 최대 30분 PENDING 으로 기다린 뒤 `INFERENCE_UNAVAILABLE`(main 에 이미 있음).
- 측정(METRICS): 측정표 내보내기를 관리 API 로(§1.1, 이름 그대로), 취소 실행은 실패율에서 뺀다, 앱 측정 가져오기·e2e 결합표 열(§3.1, 서버 uploadMs 는 `serverUploadMs`), 품질 평가 저장·CSV 열(§4.1).
- 내부(계약 밖): `/infer` 업로드 전송 모드(REVIEW_CHECKLIST #6 (b), main 에 있음), `queuedAt` 은 서버 안에서 항상 증가(QueueClock).

## v1.1 — 2026-11-21 (git tag api-v1.1)

v1.0 에 작업 대기열 상한을 더한 것이다. 2026-11-21 안건 #21 결정(계약 회의 대신 Backend 담당, [REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #21 결정 칸, 근거와 D1~D12 는 [perf/load/2026-11-20_queue-cap.md](../perf/load/2026-11-20_queue-cap.md) 9절). D11 대로 `api-v1.0` 태그 뒤의 다음 버전이다. 반영은 PR #19(`review/queue-cap-2`), [openapi.yaml](openapi.yaml) `info.version` 1.1.0.

- 변경(상태 코드 추가): `POST /api/v1/jobs`(createJob)와 `POST /api/v1/jobs/{jobId}/retry`(retryJob)에 **503 `QUEUE_FULL` + `Retry-After` 헤더**(정수 초, 1 이상, openapi `components.responses.QueueFull`)(D1·D2). PENDING 작업 + 접수 중인 요청이 서버 상한 N 이상일 때만 오고(D5), 거절은 사진·작업·`Idempotency-Key` 를 남기지 않는다. v1 상태 코드는 400 / 404 / 409 / 413 / 500 / 503 여섯 가지가 됐다(429 `RATE_LIMITED` 는 v1.0 처럼 이름만 예약).
- 추가(오류 코드): `QUEUE_FULL`(HTTP 503). 앱 문구 "지금 생성 요청이 많아요. 잠시 후 다시 시도해 주세요."(D8, GPU 가 꺼져 대기열이 찬 때도 같음). 모르는 코드를 일반 문구로 처리하는 옛 앱도 깨지지는 않지만 다시 시도할 수 없는 오류로 보인다.
- 규칙: 거절된 retry 의 작업은 FAILED 그대로이고 `error.jobId` 에 그 작업을 담는다. 거절된 생성은 `error.jobId` 가 null(D7). `POST /jobs` 의 검사 순서는 입력 검사(400) → 같은 `Idempotency-Key` 재요청(상한과 상관없이 기존 작업 202) → 상한(503) → 대표 사진 검사(400)(D6). 세는 대상의 예외 두 가지(D12): `admission.stale-after-ms` 보다 오래 기다린 PENDING 은 세지 않고, 같은 키의 요청이 접수 중이면 상한과 상관없이 함께 접수한다. 앱은 `Retry-After` 전에는 생성·retry 를 다시 보내지 않고 자동으로 다시 보내지도 않는다. 자세한 규칙은 [ERROR_CODES.md](ERROR_CODES.md) '대기열 상한'.
- 값: N=19(`admission.max-pending`), `Retry-After` 165 s(`admission.retry-after-seconds`)(D3·D4) — 목표 최악 대기 W=180 s 규칙, c=1, 4차 부하 시험 S_busy 8.67 s 기준 [계산]. 5차 부하 시험에서 S_busy 를 다시 재면(GLB 에 털 맵이 들어가 처리 시간이 바뀔 수 있다) 같은 규칙으로 다시 계산할 수 있다. 서버 설정 값이라 바꿔도 응답 형태는 그대로다.
- 서버 설정·환경 변수(openapi 밖): `admission.max-pending`(기본 0 = 꺼짐) ↔ `ADMISSION_MAXPENDING`, `admission.retry-after-seconds`(기본 165) ↔ `ADMISSION_RETRYAFTERSECONDS`, `admission.stale-after-ms`(비우면 정상 작업이 PENDING 으로 기다릴 수 있는 가장 긴 시간, prod·N 19·c 1 이면 2시간 56분 — `inference.timeout-ms` 120 s 기준). VM compose 는 `deploy/.env` 의 `ADMISSION_MAX_PENDING`·`ADMISSION_RETRY_AFTER_SECONDS` 를 넘기고(비우면 0·165), 부하 시험 덮어쓰기는 셸의 `LOADTEST_ADMISSION_MAX_PENDING`·`LOADTEST_ADMISSION_RETRY_AFTER_SECONDS` 만 읽는다.
- 측정 이름(추가, [METRICS.md](../METRICS.md) §5.1): `beside.jobs.refused{op=create|retry}`(Prometheus `beside_jobs_refused_total`), `beside.jobs.pending.stale`(`beside_jobs_pending_stale`). §5.3 의 5xx 비율은 `QUEUE_FULL` 거절을 뺀다(D9). 부하 시험 A3 는 503 `QUEUE_FULL` 을 실패로 세지 않고 `load_rejectedCount`(그중 `load_queueFullCount`)로 따로 센다(D10).
- 기본 꺼짐: 서버와 compose 의 기본값이 모두 0 이라, 설정하지 않은 v1.1 서버는 v1.0 과 똑같이 받는다. 운영 서버(VM)는 `QUEUE_FULL` 을 아는 Unity 새 빌드를 팀·시연 기기에 깔고 5차 부하 시험이 N 을 확인할 때까지 꺼 둔다(`deploy/.env` 에 두지 않음) **[계획]**.
- 구현 상태: 서버(`AdmissionGate`)·웹 Mock UI 와 시험 **[구현됨]**. Unity 대응은 컴파일만 확인했고 에디터·기기 확인은 **[계획]**.

## v1.0 — 2026-11-21 확정 (git tag api-v1.0, 2026-11-21)

2026-10-04 초기 동결에 아래 하위 절(2026-10-04 문서화 이후 추가, 2026-11-04 대표 사진 검사 오류 코드, 2026-11-21 확정)을 더한 것이 v1.0 이다. [openapi.yaml](openapi.yaml) 전체가 기준이다.

- 엔드포인트: `POST /api/v1/jobs`, `GET /api/v1/jobs`, `GET /api/v1/jobs/{jobId}`, `GET /api/v1/jobs/{jobId}/asset?variant=base`, `POST /api/v1/jobs/{jobId}/retry`, `GET /api/v1/healthz`. (2026-10-04 초안의 `variant=base|hair` 에서 `hair` 는 2026-11-21 에 뺐다 — 아래 '2026-11-21 확정'.)
- 스키마: `JobResponse`(id, status, progress, createdAt, updatedAt, timings, asset, error, uploadedFiles), `Timings`, `AssetInfo`, `JobError`, `UploadedFile`, `JobCreatedResponse`, `JobListResponse`, `ErrorResponse`/`ErrorDetail`, `HealthResponse`.
- 오류 봉투 `{ error: { code, message, jobId? } }`, 상태 코드 400 / 404 / 409 / 413 / 500. 코드 목록은 [ERROR_CODES.md](ERROR_CODES.md).
- v0 `/api/jobs` 와의 차이: 응답 본문에 `jobId` 추가, `resultPath`·서버 경로 제거(→ `asset.url`), `uploadedFiles` 가 경로 리스트에서 `{name, bytes}` 로, `durationMs` → `timings`, 미완료 에셋 요청 400 → 409, `retry`·목록·healthz 추가, 사진 타입에서 GIF 제외.
- v0 `/api/jobs` 는 동결 상태로 유지한다. 제거 시점: 2026-11-21 #10 — 공개 서버는 Caddy 404 유지, 코드 삭제는 Unity 1단계 실기기 확인 뒤(날짜는 그때 정한다).

### v1.0 문서화 이후 추가 (2026-10-04, 응답 형태 변경 없음 — api-v1.0 태그에 함께 포함)

- 설명 갱신: 작업과 `Idempotency-Key` 가 서버 DB(H2)에 저장돼 재시작 후에도 유지된다(`createJob` 설명). "같은 키 → 같은 jobId" 계약은 그대로이고 보장 범위만 넓어졌다.
- 추가: `JobResponse.error.code` 에 `INTERNAL_ERROR` 가 올 수 있다. 서버 재시작·워커 중단으로 끊긴 작업이다. Mock 워커가 인터럽트 때 이미 쓰던 값을 Job 실패 코드 표에 올린 것이며, 모르는 코드를 일반 문구로 처리하는 앱은 바꿀 것이 없다. 2026-11-21 에 그대로 확정했다(REVIEW_CHECKLIST 7번).
- 설명 갱신(real 워커 구현): Job 실패 코드가 생기는 조건을 구체화했다. `INFERENCE_UNAVAILABLE` 에서 "스텁 기본값"을 지우고 모델 미준비(501~504)를 적었고, `CONVERSION_FAILED` 에 real 워커가 받은 GLB 가 없거나 glTF 2.0 이 아닌 경우를, `INTERNAL_ERROR` 에 real 워커의 예기치 못한 오류를 넣었다. `error.message` 에 서버 경로·내부 주소를 싣지 않는다는 규칙도 ERROR_CODES 에 적었다. 코드 값·HTTP 상태·응답 형태는 그대로다.

### 2026-11-04 — 대표 사진 검사 오류 코드 (응답 형태 변경 없음 — 2026-11-21 #19 로 api-v1.0 태그에 포함)

- 추가: `POST /api/v1/jobs` 의 400 코드 7개 — `UNREADABLE_IMAGE`, `IMAGE_TOO_SMALL`, `NO_ANIMAL_DETECTED`, `TOO_MANY_ANIMALS`, `ANIMAL_TOO_SMALL`, `IMAGE_TOO_DARK`, `IMAGE_TOO_BLURRY`. 첫 장(대표 사진)을 202 전에 검사해 처음 걸린 이유 하나를 돌려준다. 봉투·상태 코드(400)는 그대로이고, 모르는 코드를 일반 문구로 처리하는 앱은 깨지지 않는다.
- 설명 갱신: openapi 의 createJob 400 은 새 응답 `CreateJobBadRequest`(설명만 다름)를 가리키고, `BadRequest` 는 다른 엔드포인트가 그대로 쓴다.
- real·prod 기본 켬, mock 기본 끔, 기준값은 제안값 → [REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #19. 서버 검사 구현은 [구현됨](2026-11-05, 브랜치 `feat/preprocess`). 둘째 동물 점수 기준은 같은 날 0.50 → 0.40(사용자 결정, 실제 두 마리 사진을 놓쳐서).

### 2026-11-21 확정 (api-v1.0 태그에 포함 — 안건 #1~#23 중 v1.0 몫, 계약 회의 대신 Backend 담당 결정)

결정 전문은 [REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) '결정' 칸. 여기에는 계약을 바꾸거나 확정하는 것만 적는다. 반영 **[구현됨]**: 브랜치 `feat/contract-v1.0`(openapi·코드·문서 반영, `JobV1ContractTest`·`ProfileContractSettingsTest` 를 포함한 backend 시험 통과)을 main 에 병합했고(PR #26), 태그 `api-v1.0` 을 2026-11-21 에 올렸다. 아래 항목은 모두 반영돼 그 태그에 들어 있다.

- 삭제: `AssetVariant` 의 `hair`(`GET /jobs/{jobId}/asset?variant=hair`) — 만드는 곳이 없음(#4). openapi `Variant`·`AssetInfo.variant` 의 enum 은 `[base]`, `?variant=hair` 는 400 `INVALID_REQUEST`.
- 변경(GLB 에셋 계약): GLB_SPEC 에 스킨(36 joints)·클립 5개를 선택 항목으로, 털 맵은 변환기가 base GLB 안 `extras.beside` 에 넣음(#14, 변환기 코드는 PR #25). 에셋 숫자 상한은 제안값 그대로, hair 100k 는 삭제(#3).
- 확인: 위 2026-11-04 대표 사진 검사 400 코드 7개(#19). prod 만 하는 사진 시그니처 검사 등 프로파일 차이를 openapi·ERROR_CODES 에 적음(#17·#19).
- 예약(구현 없음): 429 `RATE_LIMITED` + `Retry-After`(#22). ERROR_CODES 에 이름만 있고 서버는 내지 않는다.
- 그대로 확정: #1·#2·#5·#8·#9·#10·#13·#15·#20.
- 내부 계약(openapi 밖): 추론 업로드 모드 확정(#6), `inference.timeout-ms` 600 s → 120 s(#7, `application-real.properties` — 코드는 반영, 운영 VM 배포는 [계획]), `+rig` 는 그 작업에서 리깅이 성공했을 때만(#18). 측정 문서 표기 정리(#16).

## 다음 태그 전 추가 (태그 없음)

### 2026-11-21 — 안건 #1~#23 결정 중 v1.0 뒤 몫 (계약 회의 대신 Backend 담당 결정)

v1.0 몫은 위 'v1.0 — 2026-11-21 확정'(태그 `api-v1.0`), v1.1 몫은 위 'v1.1 — 2026-11-21' 절이다. 아래에서 `api-v1.1` 을 뺀 나머지는 반영 [계획].

- **`api-v1.1`**: 작업 대기열 상한 — 생성·retry 에 503 `QUEUE_FULL` + `Retry-After`(N=19, 165 s), PR #19(#21). 반영: 위 'v1.1 — 2026-11-21' 절(운영에서 켜기는 [계획]).
- **`api-v1.2`**: v1.1 검토 브랜치(`review/api-v1.1`)에서 고른 것 — 대기 정보·작업 취소·공유 링크·반려동물 보관함·관리 화면·품질 평가와 오류 코드 `CANCELED`·`JOB_NOT_PENDING`·`SHARE_NOT_FOUND`·`PET_NOT_FOUND`·`JOB_IN_PROGRESS`(#11·#12). 작업 삭제·`thumbnail`·`photos` 엔드포인트는 미룸. 그 브랜치의 'v1.1 (제안)' 절은 v1.2 로 바꾼다.
- 보존 정책(#23, 응답 형태 변경 없음): 사진 7일·GLB 30일, 지운 작업은 404 `ASSET_NOT_FOUND`. 구현은 공개 베타 전.
