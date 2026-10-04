# API 계약 변경 이력

형식: `## vX.Y — YYYY-MM-DD (git tag api-vX.Y)` 아래에 `추가 / 변경 / 삭제` 로 나눠 적는다. "변경·삭제"는 주 1회 계약 회의에서 결정된 것만 올린다. 필드 "추가"는 PR 에 한 줄 기록하면 되지만, 다음 태그를 올릴 때 여기에 모아 적는다.

## v1.0 — 2026-10-04 (git tag api-v1.0 — 커밋 후 직접 올릴 것)

초기 동결. [openapi.yaml](openapi.yaml) 전체가 기준이다.

- 엔드포인트: `POST /api/v1/jobs`, `GET /api/v1/jobs`, `GET /api/v1/jobs/{jobId}`, `GET /api/v1/jobs/{jobId}/asset?variant=base|hair`, `POST /api/v1/jobs/{jobId}/retry`, `GET /api/v1/healthz`.
- 스키마: `JobResponse`(id, status, progress, createdAt, updatedAt, timings, asset, error, uploadedFiles), `Timings`, `AssetInfo`, `JobError`, `UploadedFile`, `JobCreatedResponse`, `JobListResponse`, `ErrorResponse`/`ErrorDetail`, `HealthResponse`.
- 오류 봉투 `{ error: { code, message, jobId? } }`, 상태 코드 400 / 404 / 409 / 413 / 500. 코드 목록은 [ERROR_CODES.md](ERROR_CODES.md).
- v0 `/api/jobs` 와의 차이: 응답 본문에 `jobId` 추가, `resultPath`·서버 경로 제거(→ `asset.url`), `uploadedFiles` 가 경로 리스트에서 `{name, bytes}` 로, `durationMs` → `timings`, 미완료 에셋 요청 400 → 409, `retry`·목록·healthz 추가, 사진 타입에서 GIF 제외.
- v0 `/api/jobs` 는 동결 상태로 유지한다. 제거 시점은 Unity 가 v1 으로 옮긴 뒤 계약 회의에서 정한다.
