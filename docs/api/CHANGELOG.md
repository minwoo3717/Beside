# API 계약 변경 이력

형식: `## vX.Y — YYYY-MM-DD (git tag api-vX.Y)` 아래에 `추가 / 변경 / 삭제` 로 나눠 적는다. "변경·삭제"는 주 1회 계약 회의에서 결정된 것만 올린다. 필드 "추가"는 PR 에 한 줄 기록하면 되지만, 다음 태그를 올릴 때 여기에 모아 적는다.

## v1.0 — 2026-11-21 확정 (git tag api-v1.0 — 반영 브랜치 `feat/contract-v1.0` 을 main 에 병합한 뒤 직접 올릴 것, 태그 [계획])

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

결정 전문은 [REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) '결정' 칸. 여기에는 계약을 바꾸거나 확정하는 것만 적는다. 반영 **[구현됨]**: 브랜치 `feat/contract-v1.0`(openapi·코드·문서 반영, `JobV1ContractTest`·`ProfileContractSettingsTest` 를 포함한 backend 시험 통과). 태그 `api-v1.0` 은 [계획](main 병합 뒤).

- 삭제: `AssetVariant` 의 `hair`(`GET /jobs/{jobId}/asset?variant=hair`) — 만드는 곳이 없음(#4). openapi `Variant`·`AssetInfo.variant` 의 enum 은 `[base]`, `?variant=hair` 는 400 `INVALID_REQUEST`.
- 변경(GLB 에셋 계약): GLB_SPEC 에 스킨(36 joints)·클립 5개를 선택 항목으로, 털 맵은 변환기가 base GLB 안 `extras.beside` 에 넣음(#14, 변환기 코드는 PR #25). 에셋 숫자 상한은 제안값 그대로, hair 100k 는 삭제(#3).
- 확인: 위 2026-11-04 대표 사진 검사 400 코드 7개(#19). prod 만 하는 사진 시그니처 검사 등 프로파일 차이를 openapi·ERROR_CODES 에 적음(#17·#19).
- 예약(구현 없음): 429 `RATE_LIMITED` + `Retry-After`(#22). ERROR_CODES 에 이름만 있고 서버는 내지 않는다.
- 그대로 확정: #1·#2·#5·#8·#9·#10·#13·#15·#20.
- 내부 계약(openapi 밖): 추론 업로드 모드 확정(#6), `inference.timeout-ms` 600 s → 120 s(#7, `application-real.properties` — 운영 VM 배포는 [계획]), `+rig` 는 그 작업에서 리깅이 성공했을 때만(#18). 측정 문서 표기 정리(#16).

## 다음 태그 전 추가 (태그 없음)

### 2026-11-21 — 안건 #1~#23 결정 중 v1.0 뒤 몫 (계약 회의 대신 Backend 담당 결정, 반영은 [계획])

v1.0 몫은 위 'v1.0 — 2026-11-21 확정' 에 있다.

- **`api-v1.1`**: 작업 대기열 상한 — 생성·retry 에 503 `QUEUE_FULL` + `Retry-After`(N=19, 165 s), PR #19(#21).
- **`api-v1.2`**: v1.1 검토 브랜치(`review/api-v1.1`)에서 고른 것 — 대기 정보·작업 취소·공유 링크·반려동물 보관함·관리 화면·품질 평가와 오류 코드 `CANCELED`·`JOB_NOT_PENDING`·`SHARE_NOT_FOUND`·`PET_NOT_FOUND`·`JOB_IN_PROGRESS`(#11·#12). 작업 삭제·`thumbnail`·`photos` 엔드포인트는 미룸. 그 브랜치의 'v1.1 (제안)' 절은 v1.2 로 바꾼다.
- 보존 정책(#23, 응답 형태 변경 없음): 사진 7일·GLB 30일, 지운 작업은 404 `ASSET_NOT_FOUND`. 구현은 공개 베타 전.
