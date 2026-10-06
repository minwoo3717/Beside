# backend — Spring Boot 3.2 / Java 17

Beside 의 공개 API 서버. 트랙 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 계약은 [docs/api/openapi.yaml](../docs/api/openapi.yaml).

- **[구현됨]** API v1 `/api/v1` (Unity 가 보는 계약), API v0 `/api/jobs` (동결, 리포 안 사용처 없음 — 제거 대기), 브라우저 Mock UI(v1 사용), Mock 워커(`PENDING → PROCESSING → COMPLETED | FAILED`, retry), 계약 테스트(openapi.yaml ↔ springdoc), 프로파일 `mock | real`, H2 파일 DB(작업·Idempotency-Key 를 재시작 후에도 보존), 서버 측정 로그 `storage/events.jsonl` 과 실행별 측정 테이블 `job_runs`(서버 타이밍 + `/infer` 수치 + 패스스루·모델 버전, 측정표는 SQL 로), real 워커(Python `/infer` 호출·순차 처리·결과 GLB 저장 — 가짜 추론 서버 테스트와 FastAPI 스텁 패스스루로 확인).
- **[계획]** 실제 AnimalLift 모델과의 연동(PLAN 3·4단계), hair variant 결과, Spring↔Python 분리 배포 여부(REVIEW_CHECKLIST #6).
- **직접 할 일**: `storage/results/sample-dog.glb` 는 **0바이트**다. [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) 를 만족하는 유효한 GLB 로 교체해야 Unity 로드 검증이 가능하다. 그 전까지 v1 `asset.bytes` 는 0 이 온다.

## 실행

Java 17 환경에서 `backend` 폴더의 PowerShell 로 실행한다.

```powershell
.\gradlew.bat clean build
.\gradlew.bat bootRun                                            # profile mock (기본)
.\gradlew.bat bootRun --args="--spring.profiles.active=real"      # real: Python 추론 서비스 필요 (아래 '프로파일 real')
```

`Started MockBackendApplication` 이후:

| 주소 | 내용 |
|---|---|
| http://localhost:8080/ | 브라우저 Mock UI (v1 사용) |
| http://localhost:8080/swagger-ui/index.html | v1 스펙 브라우저 (springdoc) |
| http://localhost:8080/v3/api-docs | 생성된 OpenAPI JSON (계약 테스트가 openapi.yaml 과 비교) |
| http://localhost:8080/api/v1/healthz | `{ "status": "ok", "profile": "mock", "workerType": "mock" }` |
| http://localhost:8080/h2-console | DB 보기 (mock·real 모두, localhost 전용). JDBC URL `jdbc:h2:file:./storage/db/beside-mock` 또는 `beside-real`, 사용자 `sa`, 비밀번호 빈칸. 측정표 내보내기는 아래 '측정 내보내기' |

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
- `Idempotency-Key` 는 DB(`idempotency_keys`)에 저장돼 재시작 후에도 같은 키 → 같은 jobId 가 유지된다.

### 프로파일 `real` (Python 추론 서비스 호출)

`RealJobWorker` 가 [내부 계약 v0](../generation/inference_service/README.md) 대로 `POST {inference.base-url}/infer` 를 부른다 **[구현됨]**(2026-10-04, PLAN 4단계 선행). 실제 AnimalLift 모델은 아직 없으므로(3단계) 지금은 추론 서비스의 패스스루 모드로 연동만 확인한다.

1. 추론 슬롯을 기다린다. `inference.max-concurrency`(기본 1 = GPU 1장)건만 동시에 `/infer` 를 부르고, 나머지 작업은 PENDING 으로 기다린다(`queuedMs` = 대기열 시간).
2. PROCESSING(progress `null` — 추론 서비스가 진행률을 주지 않는다) → `{ jobId, imagePaths(절대 경로), options.hair=false }` 전송.
3. 돌아온 `glbPath` 가 glTF 2.0 바이너리인지 헤더만 확인하고 `storage/results/{jobId}/base.glb` 로 복사 → COMPLETED. 전체 검증(gltf-validator·상한)은 변환기 몫이다([GLB_SPEC](../docs/asset/GLB_SPEC.md)).

| 상황 | `error.code` |
|---|---|
| 오류 본문 `detail.code` 가 Job 실패 코드 | 그 코드 |
| 그 밖의 HTTP 501~504 / 4xx·500 | `INFERENCE_UNAVAILABLE` / `INFERENCE_FAILED` |
| 연결 거부·끊김·5초 안에 연결 못 함 | `INFERENCE_UNAVAILABLE` |
| `inference.timeout-ms`(기본 600000) 안에 답 없음 | `INFERENCE_TIMEOUT` |
| 200 인데 `glbPath` 없음·파일 없음·GLB 아님 | `CONVERSION_FAILED` |
| 서버 종료로 끊김, 예기치 못한 오류 | `INTERNAL_ERROR` |

`error.message` 에는 서버 경로·내부 주소·추론 서비스의 원문 메시지를 넣지 않는다. 자세한 원인은 서버 로그의 `stage=FAILED` 줄에 있다.

GPU 없이 이 PC 에서 시험하기(패스스루). 8080 에 mock 서버가 떠 있으면 먼저 끈다.

```powershell
# 터미널 1 — 추론 서비스 스텁: 모델 대신 샘플 GLB 경로를 돌려준다
cd generation\inference_service
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
$env:BESIDE_SAMPLE_GLB = 'C:\path\to\valid.glb'     # 유효한 glTF 2.0 .glb. 0바이트 sample-dog.glb 는 CONVERSION_FAILED
.\.venv\Scripts\python -m uvicorn app:app --port 8001

# 터미널 2 — Spring real
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=real"

# 터미널 3 — 리포 루트
.\scripts\e2e_mock.ps1        # healthz workerType=real → PROCESSING → COMPLETED → GLB 다운로드
```

패스스루로 끝난 실행도 `events.jsonl` 과 `job_runs` 에 `workerType=real`, `COMPLETED` 로 남는다. 모델이 돌지 않았으므로 측정이 아니다(서버 로그에 jobId 마다 WARN). `passthrough=true` 로 기록되므로 측정표에서는 이 값으로 거른다.

### 측정 내보내기 (`job_runs`)

실행(attempt)이 끝날 때마다 `JobFinisher` 가 `job_runs` 에 한 행을 넣는다: 서버 타이밍(`upload_ms, queued_ms, processing_ms, total_ms`) + `/infer` 가 돌려준 수치(`infer_ms, gpu_peak_mb, model_params, output_vertices, output_triangles, convert_ms`) + `glb_bytes, passthrough, model_version` + `status, error_code`. 같은 객체로 `events.jsonl` 줄도 쓴다(필드 정의 [docs/METRICS.md](../docs/METRICS.md) §1·§1.1). 5단계 측정표는 서버가 켜진 상태에서 `/h2-console`(JDBC URL `jdbc:h2:file:./storage/db/beside-real`)에 들어가 다음을 실행해 뽑는다. 경로는 `backend/` 기준, 헤더는 METRICS 이름과 같다.

```sql
CALL CSVWRITE('../generation/experiments/YYYY-MM-DD_exp-NN/job_runs.csv',
 'SELECT job_id AS "jobId", attempt, finished_at AS "finishedAt", worker_type AS "workerType", status, error_code AS "errorCode",
         upload_bytes AS "uploadBytes", upload_ms AS "uploadMs", queued_ms AS "queuedMs", processing_ms AS "processingMs", total_ms AS "totalMs",
         infer_ms AS "inferMs", gpu_peak_mb AS "gpuPeakMB", model_params AS "modelParams", output_vertices AS "outputVertices",
         output_triangles AS "outputTriangles", convert_ms AS "convertMs", glb_bytes AS "glbBytes", passthrough, model_version AS "modelVersion"
  FROM job_runs
  WHERE worker_type = ''real'' AND status = ''COMPLETED'' AND passthrough = FALSE
  ORDER BY finished_at');
```

빠르게 보려면 `SELECT * FROM job_runs ORDER BY finished_at`. 서버가 켜져 있는 동안 다른 프로세스는 DB 파일을 열 수 없다(AUTO_SERVER 없음). 측정 행을 넣지 못해도(디스크·DB 오류) 서버 로그에 ERROR 만 남고 작업 결과는 바뀌지 않는다. 같은 attempt 를 두 번 끝내는 것은 버그이며 첫 행이 유지된다.

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
│  ├─ service/RealJobWorker.java         # real: 추론 슬롯 대기 → PROCESSING → /infer → GLB 헤더 확인·복사 → JobFinisher
│  ├─ service/InferenceClient.java       # /infer 전송과 응답 → Job 실패 코드 매핑, metrics·passthrough·modelVersion → RunDetails (전송 방식이 바뀌면 여기만)
│  ├─ repository/JobRepository.java      # Spring Data JPA (H2). IdempotencyKeyRepository·JobRunRepository 도 같은 폴더
│  ├─ service/InterruptedJobRecovery.java  # 시작 시 PENDING/PROCESSING 으로 남은 작업을 FAILED 로
│  ├─ service/JobFinisher.java           # 실행 종료(COMPLETED/FAILED)는 여기서만 → job_runs 행 + events.jsonl 줄 (같은 JobRun)
│  ├─ domain/JobRun.java                 # job_runs 행: 서버 타이밍 + /infer 수치 + glbBytes·passthrough·modelVersion (삽입 전용)
│  ├─ domain/RunDetails.java             # 워커가 JobFinisher 에 넘기는 추론 결과 값 객체 (mock 은 NONE)
│  ├─ domain/JobTimings.java             # queuedMs/processingMs/totalMs 계산 (API 응답과 events.jsonl 공용)
│  ├─ config/RequestStartFilter.java     # 업로드 요청 도착 시각 (서버 uploadMs)
│  ├─ exception/GlobalExceptionHandler.java  # /api/v1/ 는 ErrorResponse 봉투, 그 외는 v0 평면 오류
│  ├─ exception/ErrorCode.java           # ERROR_CODES.md 와 1:1
│  └─ config/OpenApiConfig.java
└─ resources/
   ├─ application.properties             # 공통 (spring.profiles.default=mock, H2 데이터소스, multipart 제한, springdoc)
   ├─ application-mock.properties        # Mock 지연·실패 토큰·샘플 GLB 경로, DB 파일 beside-mock, h2-console
   ├─ application-real.properties        # inference.base-url·timeout-ms·max-concurrency, storage.result.dir, DB 파일 beside-real, h2-console
   └─ static/                            # 브라우저 Mock UI (v1)
```

- Controller 가 요청을 받고 Service 가 파일과 Job 을 저장한다. 별도 Spring Bean 인 워커의 `@Async` 메서드가 백그라운드 작업을 수행한다.
- 저장소는 H2 파일 DB 다. 테이블은 `jobs`(작업), `idempotency_keys`, `job_runs`(실행별 측정) 세 개. 프로파일마다 파일이 따로 있다: `storage/db/beside-mock.mv.db`, `storage/db/beside-real.mv.db` (gitignore). 위치는 `--storage.db.path=...` 로 바꾼다. 스키마는 `ddl-auto=update` 가 기동 때 맞춘다(테이블·컬럼 추가만). `job_runs` 가 생기기 전에 끝난 실행은 행이 없다.
- 서버를 재시작해도 작업과 Idempotency-Key 는 남는다. 다만 처리 중이던 워커 스레드는 사라지므로, 시작할 때 PENDING/PROCESSING 으로 남은 작업은 FAILED(`INTERNAL_ERROR`)가 되고 앱이 retry 로 다시 실행한다.
- DB 파일 하나는 서버 하나만 연다. 같은 파일로 두 번째 서버를 띄우면 시작 단계에서 실패한다. 개발 DB 를 비우려면 서버를 끄고 `storage/db/` 를 지운다.
- 서버 업로드 제한은 파일당 5MB, 요청 전체 20MB (v0·v1 공통, 초과 시 413).
- 측정 로그: 실행이 끝날 때마다 `storage/events.jsonl` 에 한 줄(19개 필드, 정의 [docs/METRICS.md](../docs/METRICS.md) §1)과 `job_runs` 에 한 행. 위치는 `--storage.events.path=...`, 빈 값이면 파일 기록만 꺼진다. 재시작으로 끊긴 실행(`FAILED:INTERNAL_ERROR`)과 패스스루 실행(`passthrough=true`)은 측정표에서 뺀다.

## 브라우저 Mock UI (`static/`, API v1)

서비스 흐름을 눈으로 확인하는 개발용 화면이다(제품의 웹 버전이 아니다). 2026-10-04 부터 v1 을 쓴다: `POST /api/v1/jobs` → `Location` 이 `/api/v1/jobs/{uuid}` 인지 확인 → 1초 간격 폴링(최대 60초, 요청당 15초 제한) → 완료 시 대표 사진으로 Mock 결과 표시. 오류와 실패는 `error.code` 로 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md) 문구를 보여 주고, 서버의 개발자용 `message` 는 보여 주지 않는다.

테스트 순서: 홈에서 **사진 선택** → JPG/PNG/WEBP (장당 5MB, 최대 10장, 합계 18MB) → **3D 모델 생성** → `WAITING → PROCESSING → COMPLETED` (API 의 `PENDING` 을 `WAITING` 으로 표시) → Mock 결과 확인 → **다른 사진으로 다시 만들기**. 파일명에 `fail` 이 들어간 사진을 고르면 실패 문구까지 확인할 수 있다.

## v0 `/api/jobs` (동결)

처음 브라우저 Mock UI 가 쓰던 API. 지금은 리포 안의 사용처가 보호 테스트(`JobAsyncIntegrationTest`)뿐이다. Unity 가 v1 으로 옮긴 뒤 계약 회의에서 제거 시점을 정하고, 그때까지 바꾸지 않는다.

- `POST /api/jobs`: multipart `photos` → **202 + 빈 본문 + Location: /api/jobs/{jobId}**. 타입 검증 없음(GIF 허용).
- `GET /api/jobs/{jobId}`: `id, status, createdAt, updatedAt, uploadedFiles(서버 경로), resultPath, durationMs`.
- `GET /api/jobs/{jobId}/result`: 샘플 GLB 다운로드. 미완료 시 **400**(v1 은 409).
- 오류는 평면 `{ "error": "문자열" }`. 없는 페이지 404, 큰 업로드 413, 잘못된 방식 405.

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
| `JobEventsTest` | events.jsonl: 완료·실패·retry 실패가 각각 한 줄, 19개 필드 이름·순서(mock 은 추론 필드 null), uploadBytes/uploadMs/타이밍/result, `job_runs` 행 3개가 줄과 같은 값 |
| `JobPersistenceTest` | H2: 같은 DB 파일로 컨텍스트를 두 번 띄워 재시작 재현. 작업·업로드·타이밍 보존, 같은 Idempotency-Key 재요청, 키 삽입 전용, 끊긴 작업 FAILED → retry. `job_runs`: 삽입 전용, 모든 컬럼 보존, 같은 attempt 두 번 종료해도 첫 행 유지·예외 없음, attempt 1·2 두 행 |
| `InferenceClientTest` | `/infer` 요청 형태(jobId, 절대 경로, hair=false), 응답 12종 → Job 실패 코드, 타임아웃·끊김·연결 거부, 응답 메시지에 경로·내부 주소 없음, `modelVersion`·숫자 아닌 metric → null·선택 필드 기본값 (가짜 추론 서버) |
| `RealJobWorkerTest` | profile real 전체 흐름: PROCESSING 의 progress null, GLB 복사·서빙, 실패 4종 코드, retry, `/infer` 한 번에 1건, `job_runs` 행과 events 줄이 같은 수치(inferMs·glbBytes·passthrough·modelVersion), 패스스루 실행 표시 (가짜 추론 서버) |
| `GlobalExceptionHandlerTest` | 413 이 v1 봉투 / v0 평면으로 나뉘는지 (멀티파트 한도는 MockMvc 로 재현 불가) |
| `mock-mvp.test.cjs` | 브라우저 Mock UI(v1): 성공·오류·시간 초과·중복 클릭, 오류 봉투와 `error.code` → ERROR_CODES 문구, v0 Location 거부, GIF 거부 (작은 DOM 대역에서 실제 app.js 실행, `BESIDE_MVP_URL` 이 있으면 실제 서버와 함께) |

계약을 바꿀 때는 `openapi.yaml` → 코드 → 계약 테스트 → `CHANGELOG.md` 순서를 지킨다([CLAUDE.md](CLAUDE.md)).
