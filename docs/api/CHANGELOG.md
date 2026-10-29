# API 계약 변경 이력

형식: `## vX.Y — YYYY-MM-DD (git tag api-vX.Y)` 아래에 `추가 / 변경 / 삭제` 로 나눠 적는다. "변경·삭제"는 주 1회 계약 회의에서 결정된 것만 올린다. 필드 "추가"는 PR 에 한 줄 기록하면 되지만, 다음 태그를 올릴 때 여기에 모아 적는다.

## v1.1 (제안) — 2026-10-30, 검토 브랜치 `review/api-v1.1` (계약 회의 전, 태그 없음)

모두 **추가**다. 기존 필드 이름·타입, `JobStatus` enum, 기존 엔드포인트의 상태 코드는 그대로다. 회의 안건은 [REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #11~#14. 회의에서 받아들이면 이 절을 `v1.1 — 날짜 (git tag api-v1.1)` 로 바꾼다.

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

## v1.0 — 2026-10-04 (git tag api-v1.0 — 커밋 후 직접 올릴 것)

초기 동결. [openapi.yaml](openapi.yaml) 전체가 기준이다.

- 엔드포인트: `POST /api/v1/jobs`, `GET /api/v1/jobs`, `GET /api/v1/jobs/{jobId}`, `GET /api/v1/jobs/{jobId}/asset?variant=base|hair`, `POST /api/v1/jobs/{jobId}/retry`, `GET /api/v1/healthz`.
- 스키마: `JobResponse`(id, status, progress, createdAt, updatedAt, timings, asset, error, uploadedFiles), `Timings`, `AssetInfo`, `JobError`, `UploadedFile`, `JobCreatedResponse`, `JobListResponse`, `ErrorResponse`/`ErrorDetail`, `HealthResponse`.
- 오류 봉투 `{ error: { code, message, jobId? } }`, 상태 코드 400 / 404 / 409 / 413 / 500. 코드 목록은 [ERROR_CODES.md](ERROR_CODES.md).
- v0 `/api/jobs` 와의 차이: 응답 본문에 `jobId` 추가, `resultPath`·서버 경로 제거(→ `asset.url`), `uploadedFiles` 가 경로 리스트에서 `{name, bytes}` 로, `durationMs` → `timings`, 미완료 에셋 요청 400 → 409, `retry`·목록·healthz 추가, 사진 타입에서 GIF 제외.
- v0 `/api/jobs` 는 동결 상태로 유지한다. 제거 시점은 Unity 가 v1 으로 옮긴 뒤 계약 회의에서 정한다.

### v1.0 문서화 이후 추가 (2026-10-04, 응답 형태 변경 없음 — api-v1.0 태그에 함께 포함)

- 설명 갱신: 작업과 `Idempotency-Key` 가 서버 DB(H2)에 저장돼 재시작 후에도 유지된다(`createJob` 설명). "같은 키 → 같은 jobId" 계약은 그대로이고 보장 범위만 넓어졌다.
- 추가: `JobResponse.error.code` 에 `INTERNAL_ERROR` 가 올 수 있다. 서버 재시작·워커 중단으로 끊긴 작업이다. Mock 워커가 인터럽트 때 이미 쓰던 값을 Job 실패 코드 표에 올린 것이며, 모르는 코드를 일반 문구로 처리하는 앱은 바꿀 것이 없다. 회의에서 확인한다(REVIEW_CHECKLIST 7번).
- 설명 갱신(real 워커 구현): Job 실패 코드가 생기는 조건을 구체화했다. `INFERENCE_UNAVAILABLE` 에서 "스텁 기본값"을 지우고 모델 미준비(501~504)를 적었고, `CONVERSION_FAILED` 에 real 워커가 받은 GLB 가 없거나 glTF 2.0 이 아닌 경우를, `INTERNAL_ERROR` 에 real 워커의 예기치 못한 오류를 넣었다. `error.message` 에 서버 경로·내부 주소를 싣지 않는다는 규칙도 ERROR_CODES 에 적었다. 코드 값·HTTP 상태·응답 형태는 그대로다.
