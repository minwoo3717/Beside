# backend — Spring Boot 3.2 / Java 17

Beside 의 공개 API 서버. 트랙 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 계약은 [docs/api/openapi.yaml](../docs/api/openapi.yaml).

- **[구현됨]** API v1 `/api/v1` (Unity 가 보는 계약), API v0 `/api/jobs` (브라우저 Mock UI 전용, 동결), Mock 워커(`PENDING → PROCESSING → COMPLETED | FAILED`, retry), 계약 테스트(openapi.yaml ↔ springdoc), 프로파일 `mock | real`.
- **[계획]** Real 워커의 Python `/infer` 호출(PLAN 4단계), H2 파일 DB·`events.jsonl`·app.js v1 전환(PLAN 0단계).
- **직접 할 일**: `storage/results/sample-dog.glb` 는 **0바이트**다. [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) 를 만족하는 유효한 GLB 로 교체해야 Unity 로드 검증이 가능하다. 그 전까지 v1 `asset.bytes` 는 0 이 온다.

## 실행

Java 17 환경에서 `backend` 폴더의 PowerShell 로 실행한다.

```powershell
.\gradlew.bat clean build
.\gradlew.bat bootRun                                            # profile mock (기본)
.\gradlew.bat bootRun --args="--spring.profiles.active=real"      # real: 워커는 TODO 스텁, 모든 작업이 INFERENCE_UNAVAILABLE 로 FAILED
```

`Started MockBackendApplication` 이후:

| 주소 | 내용 |
|---|---|
| http://localhost:8080/ | 브라우저 Mock UI (v0 사용) |
| http://localhost:8080/swagger-ui/index.html | v1 스펙 브라우저 (springdoc) |
| http://localhost:8080/v3/api-docs | 생성된 OpenAPI JSON (계약 테스트가 openapi.yaml 과 비교) |
| http://localhost:8080/api/v1/healthz | `{ "status": "ok", "profile": "mock", "workerType": "mock" }` |

기존 서버가 실행 중이면 해당 터미널에서 `Ctrl+C` 로 종료한 뒤 다시 실행한다. `bootRun` 이 EXECUTING 상태로 유지되는 것은 서버가 실행 중이라는 뜻이다.

## API v1 요약

| 메서드·경로 | 응답 | 비고 |
|---|---|---|
| `POST /api/v1/jobs` (multipart `photos` 1~10장, jpeg/png/webp, 장당 ≤5MB, 전체 ≤20MB, 헤더 `Idempotency-Key` 선택) | 202 + `Location` + `{ jobId }` | 400 `NO_PHOTOS`/`TOO_MANY_PHOTOS`/`UNSUPPORTED_IMAGE_TYPE`/`INVALID_REQUEST`, 413 |
| `GET /api/v1/jobs/{id}` | 200 `JobResponse` | 404 `JOB_NOT_FOUND` |
| `GET /api/v1/jobs/{id}/asset?variant=base\|hair` | 200 `model/gltf-binary`, `Content-Disposition: attachment` | 409 `JOB_NOT_COMPLETED`, 404 `ASSET_NOT_FOUND`(Mock 은 hair 없음), 400 잘못된 variant |
| `POST /api/v1/jobs/{id}/retry` | 202 + `{ jobId }` | FAILED 만, 그 외 409 `JOB_NOT_FAILED` |
| `GET /api/v1/jobs?limit=&cursor=` | 200 `{ items, nextCursor }` | 개발·디버그용, 최신순 |
| `GET /api/v1/healthz` | 200 `{ status, profile, workerType }` | |

오류는 `{ "error": { "code", "message", "jobId?" } }` 봉투, 코드와 앱 표시 문구는 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md). `JobResponse` 는 서버 경로를 노출하지 않는다(`asset.url` 은 `/api/v1/jobs/{id}/asset?variant=base` 상대 경로).

```powershell
curl.exe -i -F "photos=@C:\photos\dog1.jpg" -F "photos=@C:\photos\dog2.jpg" http://localhost:8080/api/v1/jobs
curl.exe -i http://localhost:8080/api/v1/jobs/<jobId>
curl.exe -i -o model.glb "http://localhost:8080/api/v1/jobs/<jobId>/asset?variant=base"
curl.exe -i -X POST http://localhost:8080/api/v1/jobs/<jobId>/retry
..\scripts\e2e_mock.ps1        # 위 흐름을 한 번에
```

### Mock 워커 동작 (profile `mock`)

- 접수 후 `mock.worker.pending-ms`(기본 2000) 동안 PENDING(progress 0.0) → `mock.worker.processing-ms`(기본 3000) 동안 PROCESSING(progress 0.5) → COMPLETED(progress 1.0, `asset` = `storage.result.sample`).
- **실패 재현**: 업로드 파일명에 `fail` 이 들어가면 FAILED, `error.code = INFERENCE_FAILED`. Unity 의 실패 UI 와 retry 흐름을 Mock 으로 테스트하기 위한 장치다. 토큰은 `mock.worker.fail-when-filename-contains`, 빈 값이면 비활성. 같은 사진으로 retry 하면 다시 실패한다(결정적).
- `Idempotency-Key` 는 서버 프로세스가 살아 있는 동안만 기억한다(메모리). 영속화는 H2 전환과 함께.

### 프로파일 `real` (TODO 스텁)

`application-real.properties` 의 `inference.base-url`(기본 `http://localhost:8001`), `inference.timeout-ms`(기본 600000) 만 정의돼 있다. `RealJobWorker` 는 아직 Python 서비스를 호출하지 않고 모든 작업을 `INFERENCE_UNAVAILABLE` 로 FAILED 처리한다. 구현 순서는 클래스 Javadoc 과 [docs/PLAN.md](../docs/PLAN.md) 4단계, Python 쪽 계약은 [generation/inference_service/README.md](../generation/inference_service/README.md).

## 구조

```text
src/main/
├─ java/com/example/mockbackend/
│  ├─ api/v1/JobV1Controller.java        # v1 엔드포인트 (operationId = 메서드명, @ApiResponses = 계약 상태 코드)
│  ├─ api/v1/JobResponseMapper.java      # Job → JobResponse (timings 계산, asset.url)
│  ├─ api/v1/ApiV1Paths.java             # 경로 상수
│  ├─ api/v1/dto/                        # JobResponse, Timings, AssetInfo, JobError, UploadedFile, JobCreatedResponse,
│  │                                     # JobListResponse, ErrorResponse, ErrorDetail, HealthResponse (= openapi.yaml 스키마 이름)
│  ├─ controller/JobController.java      # v0 /api/jobs (동결) + LegacyJobResponse
│  ├─ service/JobServiceImpl.java        # 검증, 저장, Idempotency-Key, retry, 목록, 에셋 경로
│  ├─ service/JobWorker.java             # 워커 인터페이스 — MockJobWorker(@Profile mock) / RealJobWorker(@Profile real)
│  ├─ repository/MemoryJobRepository.java
│  ├─ exception/GlobalExceptionHandler.java  # /api/v1/ 는 ErrorResponse 봉투, 그 외는 v0 평면 오류
│  ├─ exception/ErrorCode.java           # ERROR_CODES.md 와 1:1
│  └─ config/OpenApiConfig.java
└─ resources/
   ├─ application.properties             # 공통 (spring.profiles.default=mock, multipart 제한, springdoc)
   ├─ application-mock.properties        # Mock 지연·실패 토큰·샘플 GLB 경로
   ├─ application-real.properties        # inference.base-url, timeout
   └─ static/                            # 브라우저 Mock UI (v0)
```

- Controller 가 요청을 받고 Service 가 파일과 Job 을 저장한다. 별도 Spring Bean 인 워커의 `@Async` 메서드가 백그라운드 작업을 수행한다.
- Repository 는 메모리 기반이다. 서버를 재시작하면 Job 정보는 사라지고 업로드 파일은 디스크에 남는다. H2 파일 DB 전환은 PLAN 0단계.
- 서버 업로드 제한은 파일당 5MB, 요청 전체 20MB (v0·v1 공통, 초과 시 413).

## v0 `/api/jobs` (동결) — 브라우저 Mock UI

브라우저 Mock UI(`static/index.html`, `app.js`)가 사용하는 원래 API. Unity 가 v1 으로 옮긴 뒤 계약 회의에서 제거 시점을 정한다. 그때까지 바꾸지 않는다.

- `POST /api/jobs`: multipart `photos` → **202 + 빈 본문 + Location: /api/jobs/{jobId}**. 타입 검증 없음(GIF 허용).
- `GET /api/jobs/{jobId}`: `id, status, createdAt, updatedAt, uploadedFiles(서버 경로), resultPath, durationMs`. 브라우저는 `PENDING` 을 `WAITING` 으로 표시한다.
- `GET /api/jobs/{jobId}/result`: 샘플 GLB 다운로드. 미완료 시 **400**(v1 은 409).
- 오류는 평면 `{ "error": "문자열" }`. 없는 페이지 404, 큰 업로드 413, 잘못된 방식 405.

브라우저 테스트 순서: 홈에서 **사진 선택** → JPG/PNG/WEBP/GIF (장당 5MB, 최대 10장, 합계 18MB) → **3D 모델 생성** → `WAITING → PROCESSING → COMPLETED` → 대표 사진을 쓴 Mock 결과 확인 → **다른 사진으로 다시 만들기**. API 조회는 1초 간격, 최대 60초, 개별 요청 제한 15초.

## 검증

```powershell
.\gradlew.bat test                                               # Java 전체
.\gradlew.bat test --tests "com.example.mockbackend.JobV1ContractTest"
node --test src/test/js/mock-mvp.test.cjs                        # Node.js 20 이상, npm 설치 불필요
$env:BESIDE_MVP_URL = 'http://localhost:8080'                    # 실행 중 서버와 함께 app.js 흐름 검증
node --test src/test/js/mock-mvp.test.cjs
Remove-Item Env:BESIDE_MVP_URL
```

| 테스트 | 확인 내용 |
|---|---|
| `JobAsyncIntegrationTest` | v0: 홈페이지·정적 파일·404·잘못된 요청·비동기 상태 전이·다운로드 API (동결 보호) |
| `JobV1ApiTest` | v1: 202/Location/jobId, 폴링, timings, asset 409→200, hair 404, retry 202/409, 검증 400, Idempotency-Key, 목록 cursor, healthz, 오류 봉투 |
| `JobV1ContractTest` | `/v3/api-docs` 와 `docs/api/openapi.yaml` 의 경로×메서드·operationId·상태 코드·스키마 이름·JobResponse 속성·enum·오류 봉투 비교 |
| `GlobalExceptionHandlerTest` | 413 이 v1 봉투 / v0 평면으로 나뉘는지 (멀티파트 한도는 MockMvc 로 재현 불가) |
| `mock-mvp.test.cjs` | 브라우저 Mock UI 의 성공·오류·시간 초과·중복 클릭 (작은 DOM 대역에서 실제 app.js 실행) |

계약을 바꿀 때는 `openapi.yaml` → 코드 → 계약 테스트 → `CHANGELOG.md` 순서를 지킨다([CLAUDE.md](CLAUDE.md)).
