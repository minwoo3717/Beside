# 측정 항목 정의

세 트랙이 **같은 이름**으로 측정값을 남긴다. 이름은 여기서만 정의하고, 보고서·발표·실험 기록은 이 이름을 그대로 쓴다. 단위는 시간 ms(정수), 크기 bytes 또는 MB(정수), 비율 0~1. 측정 시점이 다른 값(예: 서버가 잰 `processingMs` 와 앱이 잰 `waitMs`)은 이름이 다르므로 섞지 않는다.

상태: `JobResponse.timings`(queuedMs, processingMs, totalMs) 와 서버 `events.jsonl` 은 **[구현됨]**(2026-10-04). 실행별 측정 테이블 `job_runs` 와 `/infer` 응답 수치의 서버 저장은 **[구현됨]**(2026-10-06). 추론 서비스가 수치를 **채우는** 것과 앱 `metrics.csv` 는 **[계획]** — 각 트랙이 PLAN 의 해당 단계에서 구현한다. 아직 **[실험 결과]** 로 인용할 측정값은 없다(Mock·패스스루 동작 확인뿐). 앱 `metrics.csv` 를 서버로 가져와 서버 측정과 합치는 API(§3.1)와 품질 평가 저장(§4.1)은 **[구현됨]**(2026-10-30, 테스트로 확인) — 실제 폰에서 뽑은 파일·실제 평가로는 아직 돌려 보지 않았다.

## 1. 서버 `events.jsonl` (Backend, [구현됨] PLAN 0단계)

작업의 한 실행(run)이 COMPLETED/FAILED 로 끝날 때마다 한 줄씩 추가한다. retry 하면 같은 jobId 로 줄이 하나 더 생긴다(`attempt` 로 구분). 위치 `backend/storage/events.jsonl`(gitignore), 설정 `storage.events.path`(빈 값이면 꺼짐, 테스트는 끔). Mock·real 모두 같은 파일에 쓰고 `workerType` 으로 구분한다. 기록은 `JobFinisher` 한 곳에서만 하며, 줄과 §1.1 의 `job_runs` 행은 같은 객체(`JobRun`)에서 나온다. 2026-10-06 부터 처음 10개 필드 뒤에 추론 수치(§2 이름 그대로)와 실행 사실 3개가 붙는다. 값이 없으면 `null` 이고 키는 항상 있다.

| 필드 | 타입 | 정의 | 측정 시점 |
|---|---|---|---|
| `jobId` | string | 작업 ID | — |
| `attempt` | int | 1 = 첫 실행, retry 마다 +1 (서버 전용 추가 필드) | — |
| `finishedAt` | string | 실행이 끝난 시각, ISO 8601 UTC (서버 전용 추가 필드) | 종료 시 |
| `uploadBytes` | int | 업로드된 사진 바이트 합. retry 해도 처음 업로드 값 | 접수 시 |
| `uploadMs` | int \| null | 업로드 요청이 서버에 도착한 때(필터, multipart 파싱 전) → 사진 저장 완료. 수신·파싱·저장 포함. HTTP 밖에서 만든 작업은 null. retry 해도 처음 업로드 값 | 접수 처리 중 |
| `queuedMs` | int | PENDING 유지 시간 (= `timings.queuedMs` 최종값, 같은 계산 `JobTimings`) | PROCESSING 전이 시 |
| `processingMs` | int \| null | PROCESSING 유지 시간 (= `timings.processingMs` 최종값). PROCESSING 없이 끝나면 null | 종료 시 |
| `totalMs` | int | 접수(또는 retry) → 종료 (= `timings.totalMs`) | 종료 시 |
| `workerType` | string | `mock` \| `real` | — |
| `result` | string | `COMPLETED` \| `FAILED:<error.code>` | 종료 시 |
| `inferMs`, `gpuPeakMB`, `modelParams`, `outputVertices`, `outputTriangles`, `convertMs` | int \| null | §2 의 수치를 `/infer` 응답에서 그대로 복사. real COMPLETED 에만 있고 mock·FAILED 는 null. 숫자가 아닌 값은 null | 종료 시 |
| `glbBytes` | int \| null | 서버가 저장한 base GLB 의 크기(= `asset.bytes`). mock·FAILED 는 null | 종료 시 |
| `passthrough` | bool \| null | true 면 추론 서비스가 모델 대신 샘플을 돌려준 것 → 측정에서 **제외**. `/infer` 응답이 없으면(mock·FAILED) null | 종료 시 |
| `modelVersion` | string \| null | `/infer` 선택 필드. 베이스라인과 개선안을 구분하는 모델·가중치 이름(예 `animallift-baseline@3f2a1c9`) | 종료 시 |

예 (2026-10-04 동작 확인 때 실제로 남은 줄, 측정값 아님 — mock, real 패스스루, real 추론 서비스 꺼짐. 2026-10-06 이후 줄에는 위 9개 키가 더 붙는다):

```json
{"jobId":"ee1985af-…","attempt":1,"finishedAt":"2026-10-04T08:35:21.148009200Z","uploadBytes":136,"uploadMs":46,"queuedMs":2122,"processingMs":3011,"totalMs":5133,"workerType":"mock","result":"COMPLETED"}
{"jobId":"9457aac8-…","attempt":1,"finishedAt":"2026-10-04T09:47:46.553329600Z","uploadBytes":136,"uploadMs":47,"queuedMs":110,"processingMs":71,"totalMs":181,"workerType":"real","result":"COMPLETED"}
{"jobId":"2cd8dfbc-…","attempt":1,"finishedAt":"2026-10-04T09:49:13.554646700Z","uploadBytes":12,"uploadMs":3,"queuedMs":5,"processingMs":5,"totalMs":10,"workerType":"real","result":"FAILED:INFERENCE_UNAVAILABLE"}
```

주의: 서버 재시작으로 끊긴 실행은 `FAILED:INTERNAL_ERROR` 로 남고, `queuedMs`/`processingMs`/`totalMs` 에 서버가 꺼져 있던 시간이 들어간다. 측정표를 만들 때는 이런 줄을 빼고 계산한다. 서버 `uploadMs` 는 앱의 `uploadMs`(§3, 요청 시작 → 202 수신)보다 작다. 연결 설정과 응답 왕복이 빠지기 때문이며, 두 값은 섞지 않는다. 추론 서비스가 패스스루(`BESIDE_SAMPLE_GLB`)로 답한 real 실행도 `workerType=real`, `COMPLETED` 로 남는다. 모델이 돌지 않았으므로 측정이 아니다(서버 로그에 jobId 마다 WARN). `passthrough=true` 로 표시되므로 측정표에서는 이 값으로 거르고, `modelVersion` 으로 어떤 모델의 실행인지 구분한다.

### 1.1 `job_runs` 테이블 (Backend, [구현됨] 2026-10-06)

같은 내용이 H2 파일 DB 의 테이블 `job_runs` 에도 실행마다 한 행씩 남는다(PK `{jobId}:{attempt}`, 삽입 전용). 컬럼은 위 줄의 필드를 snake_case 로 쓴 것(`job_id, attempt, finished_at, upload_bytes, upload_ms, queued_ms, processing_ms, total_ms, worker_type, infer_ms, gpu_peak_mb, model_params, output_vertices, output_triangles, convert_ms, glb_bytes, passthrough, model_version`)에 `status`(COMPLETED|FAILED)와 `error_code` 를 더한 것이다. `result` 는 `status`+`error_code` 로 대신한다. 서버 경로는 없다. 프로파일마다 DB 파일이 다르므로(`beside-mock.mv.db`, `beside-real.mv.db`) 측정표는 real 파일에서 뽑는다.

**측정표 내보내기** — 5단계 측정표는 관리 API 로 뽑는다 **[구현됨]**(2026-10-30, `AdminApiTest`). `GET /api/v1/admin/runs?format=csv` 가 헤더 행이 있는 CSV(`job_runs.csv`)를 돌려준다. 열 이름과 순서는 아래 SQL 의 별칭과 같다: `jobId, attempt, finishedAt, workerType, status, errorCode, uploadBytes, uploadMs, queuedMs, processingMs, totalMs, inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs, glbBytes, passthrough, modelVersion`. 기본으로 패스스루 행을 빼고(`includePassthrough=true` 로 포함), `workerType`, `modelVersion`(`(none)` = 값 없음), `from`/`to`(finishedAt, ISO 8601 instant, `to` 는 제외)로 거른다. FAILED 행은 들어 있으므로 시간 통계는 `status = COMPLETED` 행으로 낸다. `GET /api/v1/admin/stats` 가 그렇게 계산한 modelVersion × workerType 별 성공률과 p50/p95(nearest-rank)를 준다. `format=json` 은 같은 이름의 객체 배열이다. 브라우저에서는 관리 페이지 `/admin` 의 "측정표 CSV 받기" 버튼을 쓴다. 배포 서버에서는 `/admin*`·`/api/v1/admin/*` 가 Caddy Basic 인증 뒤에 있다.

```powershell
curl.exe -o job_runs.csv "http://localhost:8080/api/v1/admin/runs?format=csv&workerType=real"                  # 개발·GPU PC
curl.exe -u <사용자> -o job_runs.csv "https://<도메인>/api/v1/admin/runs?format=csv&workerType=real&from=2026-11-06T00:00:00Z"   # 배포 VM
```

CSV 값 형식: null 은 빈 칸, `finishedAt` 은 ISO 8601 UTC(`2026-11-06T09:47:46.553329Z`), `passthrough` 는 `true|false`, 쉼표·따옴표가 든 값만 큰따옴표로 감싼다(RFC 4180, 줄 끝 CRLF).

개발 PC 대안 — h2-console. 배포 서버의 `prod` 프로파일(PLAN S1)은 콘솔을 끄므로 거기서는 위 API 를 쓴다. 개발 PC 에서 서버가 켜진 상태라면 `http://localhost:8080/h2-console`(mock·real 프로파일, localhost 전용; JDBC URL `jdbc:h2:file:./storage/db/beside-real`, 사용자 `sa`, 비밀번호 빈칸)에 들어가 아래를 실행해도 헤더가 이 문서의 이름과 같은 CSV 가 나온다. 이때는 H2 가 모든 값을 따옴표로 감싸고 시각을 H2 형식으로 쓴다. 경로는 서버 작업 폴더(`backend/`) 기준이다. 모든 열에 따옴표 별칭을 붙인다 — 별칭이 없는 열은 H2 가 `ATTEMPT` 처럼 대문자로 내보낸다(2026-10-06 확인).

```sql
CALL CSVWRITE('../generation/experiments/YYYY-MM-DD_exp-NN/job_runs.csv',
 'SELECT job_id AS "jobId", attempt AS "attempt", finished_at AS "finishedAt", worker_type AS "workerType", status AS "status", error_code AS "errorCode",
         upload_bytes AS "uploadBytes", upload_ms AS "uploadMs", queued_ms AS "queuedMs", processing_ms AS "processingMs", total_ms AS "totalMs",
         infer_ms AS "inferMs", gpu_peak_mb AS "gpuPeakMB", model_params AS "modelParams", output_vertices AS "outputVertices",
         output_triangles AS "outputTriangles", convert_ms AS "convertMs", glb_bytes AS "glbBytes", passthrough AS "passthrough", model_version AS "modelVersion"
  FROM job_runs
  WHERE worker_type = ''real'' AND status = ''COMPLETED'' AND passthrough = FALSE
  ORDER BY finished_at');
```

제외 규칙: FAILED 행(실패 통계는 `error_code` 로 따로 센다), `passthrough = TRUE`(모델이 돌지 않음), 재시작으로 끊긴 `INTERNAL_ERROR`. 사용자가 대기 중에 취소한 실행(`error_code = CANCELED`)은 실패율에도 넣지 않는다(관리 API stats 의 `canceled`). 서버를 끈 상태에서는 H2 Shell(`java -cp h2-2.2.224.jar org.h2.tools.Shell -url jdbc:h2:file:./storage/db/beside-real -user sa -sql "..."`)로 같은 문장을 실행할 수 있다. 서버가 켜져 있을 때 다른 프로세스가 파일을 열 수는 없다. 테이블이 생기기 전(2026-10-06 이전)에 끝난 실행은 행이 없고 `events.jsonl` 줄만 있다.

## 2. 추론 서비스 (3D Generation, 수치 채우기는 [계획] PLAN 3·4단계)

`POST /infer` 응답의 `metrics` 와 실험 기록 표에 같은 이름을 쓴다. Spring real 워커는 응답 `metrics`·`passthrough`·`modelVersion` 을 실행마다 `job_runs` 행과 `events.jsonl` 줄에 저장한다 **[구현됨]**(2026-10-06, §1·§1.1). 추론 서비스가 이 값을 실제로 채우는 것은 모델 연결(PLAN 3단계) 뒤의 일이다. 작업별 파일 저장(`results/{jobId}/*.metrics.json`)은 [계획]. `convert_asset.py` 의 `metrics.json`(vertices, triangles, textureSize, bytes, convertMs) 과 겹치는 이름은 같은 의미다.

| 필드 | 타입 | 정의 | 측정 방법 |
|---|---|---|---|
| `inferMs` | int | 이미지 입력 → `mesh.obj`/`uv.png` 생성 완료까지 | 서비스 내부 타이머 |
| `gpuPeakMB` | int | 추론 중 GPU 메모리 최대 사용량 | `torch.cuda.max_memory_allocated()` 를 MB 로 |
| `modelParams` | int | 모델 파라미터 수 | `sum(p.numel())` |
| `outputVertices` | int | 생성 메시 정점 수 (base 기준) | 변환기 `vertices` |
| `outputTriangles` | int | 생성 메시 삼각형 수 (base 기준) | 변환기 `triangles` |
| `convertMs` | int | OBJ/PNG → GLB 변환 시간 | 변환기 타이머 |

환경 기록 필수: GPU 모델, 드라이버/CUDA 버전, 입력 사진 수·해상도, 배치 크기, 코드 커밋.

## 3. 앱 `metrics.csv` (Unity, [계획] PLAN 1·2단계)

위치 `Application.persistentDataPath/metrics/metrics.csv`. 한 작업당 한 줄, 헤더는 아래 순서 그대로.

```csv
jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device
```

| 필드 | 정의 | 측정 시점 |
|---|---|---|
| `uploadMs` | POST 요청 시작 → 202 수신 | 업로드 |
| `waitMs` | 202 수신 → status 가 COMPLETED/FAILED 로 처음 관측됨 | 폴링 |
| `downloadMs` | asset GET 시작 → 파일 저장 완료 | 다운로드 |
| `loadMs` | glTFast `Load()` 시작 → 씬에 인스턴스화 완료 | 로드 |
| `e2eMs` | 사진 선택 완료(업로드 시작) → 모델이 화면에 보임 (= 위 네 값 + 폴링 간격 손실) | 전체 |
| `avgFps`, `minFps` | 모델 배치 후 30초 동안의 평균/최저 FPS | AR 배치 중 |
| `memMB` | 배치 후 `Profiler.GetTotalAllocatedMemoryLong()` 을 MB 로 | AR 배치 중 |
| `device` | `SystemInfo.deviceModel` | — |

### 3.1 서버로 가져오기와 e2e 결합표 (Backend, [구현됨] 2026-10-30, `ClientMetricsApiTest`)

앱은 바꾸지 않는다. 팀원이 `adb pull /sdcard/Android/data/<패키지명>/files/metrics/metrics.csv .` 로 받은 파일을 관리 페이지 `/admin` 의 "앱 metrics.csv 올리기" 로 올린다(API `POST /api/v1/admin/client-metrics`, multipart 파트 `file`).

- 첫 줄이 위 헤더와 **정확히** 같아야 한다. 아니면 아무것도 저장하지 않고 400. 데이터 줄은 줄마다 검사해 잘못된 줄만 결과의 `errors[{line, reason}]` 에 담고(줄 번호는 헤더 = 1) 나머지는 저장한다: 칸 10개, `uploadMs, waitMs, downloadMs, loadMs, e2eMs, memMB` 는 0 이상 정수, `avgFps, minFps` 는 0 이상 소수(`.`), `device` 는 빈 칸 허용.
- `(jobId, device, e2eMs)` 가 이미 저장된 행(또는 같은 파일의 앞 줄)과 같으면 중복으로 건너뛴다(`skipped`). 같은 파일을 여러 번 올려도 한 번만 들어간다. 폰마다 파일이 따로이므로 폰 여러 대의 파일을 차례로 올리면 된다.
- 저장 위치: H2 테이블 `client_metrics`(컬럼 `job_id, upload_ms, wait_ms, download_ms, load_ms, e2e_ms, avg_fps, min_fps, mem_mb, device` + `id, imported_at`). jobId 가 이 서버에 없는 작업이어도 저장한다.
- 내보내기: `GET /api/v1/admin/client-metrics?format=csv` → `client_metrics.csv`, 열은 위 헤더 그대로(다시 올려도 전부 중복으로 건너뛴다).

**e2e 결합표** — `GET /api/v1/admin/e2e?format=csv` → `e2e.csv`. 앱 측정 한 행마다 그 jobId 의 마지막 COMPLETED 실행(`job_runs`, §1.1)의 서버·추론 열을 붙인 표다. 열 이름은 이 문서의 이름 그대로이고 **겹치는 `uploadMs` 만 서버 것을 `serverUploadMs`** 로 쓴다(서버 `uploadMs` 는 §1 의 요청 도착 → 사진 저장, 앱 `uploadMs` 는 §3 의 POST 시작 → 202 수신으로 다른 값이다). 열 순서:

```csv
jobId,modelVersion,workerType,passthrough,attempt,finishedAt,uploadBytes,serverUploadMs,queuedMs,processingMs,totalMs,inferMs,gpuPeakMB,modelParams,outputVertices,outputTriangles,convertMs,glbBytes,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device
```

COMPLETED 실행이 없으면(다른 서버·프로파일의 작업, 실패한 작업) 서버·추론 열은 빈 칸이다. 기본으로 패스스루 실행의 행을 뺀다(`includePassthrough=true` 로 포함). CSV 형식(RFC 4180, null 빈 칸, CRLF)은 §1.1 과 같다.

## 4. 3D 품질 체크리스트 (사람 평가, 1~5점)

평가자 2명 이상이 독립 채점하고 평균을 쓴다. 기준 사진(입력)과 렌더(정면·측면·위) 를 나란히 놓고 채점한다. 1 = 전혀 다름, 3 = 종을 알아볼 수 있음, 5 = 그 개체로 알아볼 수 있음.

| 항목 | 이름 | 보는 것 |
|---|---|---|
| 얼굴 형태 | `faceShape` | 주둥이 길이, 이마·눈 위치, 전체 윤곽 |
| 귀 모양/위치 | `ears` | 선 귀/접힌 귀, 크기, 붙은 위치 |
| 체형 비율 | `bodyProportion` | 다리 길이, 몸통 길이/높이 비, 꼬리 |
| 털 색상 | `furColor` | 주 색상과 밝기 |
| 무늬 재현 | `pattern` | 반점·줄무늬·얼굴 마스크의 위치와 경계 |
| 텍스처 번짐/seam | `textureArtifacts` | UV 경계선, 늘어남, 뭉개짐 (5 = 결함 없음) |

실험 기록 표 예시(`generation/experiments/TEMPLATE.md` 와 같은 형식):

| 실험 | inferMs | gpuPeakMB | outputTriangles | faceShape | ears | bodyProportion | furColor | pattern | textureArtifacts |
|---|---|---|---|---|---|---|---|---|---|
| baseline | | | | | | | | | |
| 개선안 A | | | | | | | | | |

### 4.1 평가 저장과 내보내기 (Backend, [구현됨] 2026-10-30, `RatingApiTest`)

평가자는 작업 상세 웹 페이지 `/job/?id={jobId}` 에서 입력 사진과 3D 모델을 나란히 보고 6항목을 채점한다(평가자 이름은 브라우저 localStorage 에 기억). 저장은 API `PUT /api/v1/jobs/{jobId}/ratings/{rater}`(본문 = 위 6개 이름, 각 1~5 정수 + 선택 `comment` ≤ 500자), COMPLETED 작업만 받는다. **같은 작업·같은 평가자는 덮어쓴다** — 평가자 한 명당 작업마다 한 행이므로 "평가자 2명 이상"은 서로 다른 이름 2개 이상이다.

- 저장 위치: H2 테이블 `job_ratings`(컬럼 `job_id, rater, face_shape, ears, body_proportion, fur_color, pattern, texture_artifacts, comment, created_at, updated_at` + `id`, `(job_id, rater)` 유일).
- 작업별 평균: `GET /api/v1/jobs/{jobId}/ratings` → `items`(평가자 이름순) + `average`(6항목 평균과 `raters`). 평가가 없으면 `average = null`.
- 측정표: `GET /api/v1/admin/ratings?format=csv` → `job_ratings.csv`. 평가 한 건 = 한 행, 그 작업의 마지막 실행(§1.1 `job_runs`)의 `modelVersion`·`workerType`·`passthrough` 를 붙인다. 열 순서:

```csv
jobId,modelVersion,rater,faceShape,ears,bodyProportion,furColor,pattern,textureArtifacts,comment,updatedAt,workerType,passthrough
```

- 보고서 표(베이스라인 vs 개선안): `GET /api/v1/admin/ratings/summary` → modelVersion 별(`(none)` = 값 없음, §1.1 stats 와 같은 이름) 6항목 평균, `ratings`(평가 행 수), `raters`(서로 다른 평가자 수), `jobs`(평가된 작업 수). 평균은 **평가 행 전체의 산술 평균**이다(작업별 평균의 평균이 아님). 관리 페이지 `/admin` 의 "평가 요약" 표가 이 값을 보여 준다.
- 제외 규칙: 기본으로 패스스루 실행(§1 `passthrough = TRUE`)의 평가는 측정표·요약에서 뺀다(`includePassthrough=true` 로 포함). `from`/`to` 는 평가의 `updatedAt` 기준(ISO 8601 instant, `to` 는 제외).
