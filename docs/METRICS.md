# 측정 항목 정의

세 트랙이 **같은 이름**으로 측정값을 남긴다. 이름은 여기서만 정의하고, 보고서·발표·실험 기록은 이 이름을 그대로 쓴다. 단위는 시간 ms(정수), 크기 bytes 또는 MB(정수), 비율 0~1. 측정 시점이 다른 값(예: 서버가 잰 `processingMs` 와 앱이 잰 `waitMs`)은 이름이 다르므로 섞지 않는다.

상태: `JobResponse.timings`(queuedMs, processingMs, totalMs) 와 서버 `events.jsonl` 은 **[구현됨]**(2026-10-04). 실행별 측정 테이블 `job_runs` 와 `/infer` 응답 수치의 서버 저장은 **[구현됨]**(2026-10-06). 추론 서비스가 수치를 **채우는** 것과 앱 `metrics.csv` 는 **[계획]** — 각 트랙이 PLAN 의 해당 단계에서 구현한다. 아직 **[실험 결과]** 로 인용할 측정값은 없다(Mock·패스스루 동작 확인뿐).

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

5단계 측정표는 이 테이블에서 SQL 로 뽑는다. 서버가 켜진 상태에서 `http://localhost:8080/h2-console`(real 프로파일도 켜져 있음, localhost 전용; JDBC URL `jdbc:h2:file:./storage/db/beside-real`, 사용자 `sa`, 비밀번호 빈칸)에 들어가 아래를 실행하면 헤더가 이 문서의 이름과 같은 CSV 가 나온다. 경로는 서버 작업 폴더(`backend/`) 기준이다. 모든 열에 따옴표 별칭을 붙인다 — 별칭이 없는 열은 H2 가 `ATTEMPT` 처럼 대문자로 내보낸다(2026-10-06 확인).

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

제외 규칙: FAILED 행(실패 통계는 `error_code` 로 따로 센다), `passthrough = TRUE`(모델이 돌지 않음), 재시작으로 끊긴 `INTERNAL_ERROR`. 서버를 끈 상태에서는 H2 Shell(`java -cp h2-2.2.224.jar org.h2.tools.Shell -url jdbc:h2:file:./storage/db/beside-real -user sa -sql "..."`)로 같은 문장을 실행할 수 있다. 서버가 켜져 있을 때 다른 프로세스가 파일을 열 수는 없다. 테이블이 생기기 전(2026-10-06 이전)에 끝난 실행은 행이 없고 `events.jsonl` 줄만 있다.

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

## 5. 운영 지표 (Micrometer/Prometheus, Backend, [구현됨] 2026-11-01)

2026-11-01 에 추가한 절이다(필드 추가라 PR 노트로 기록, 이름 변경·삭제는 계약 회의). §1~§3 이 실행·작업 한 건마다 남기는 기록이라면, 이 절은 서버가 **지금 어떤 상태인지** 보는 누적 지표다. 멘토링 보고서의 지표 5종(생성 성공률, 생성 시간 평균/p95, 대기열 길이, API 응답 시간, 에셋 다운로드 실패율)을 Prometheus·Grafana 로 보기 위해 둔다.

- **노출**: Spring Actuator 가 관리 포트 `8081`(`management.server.port`, 환경변수 `MANAGEMENT_SERVER_PORT`)의 `/actuator/prometheus` 와 `/actuator/health` 에서만 내보낸다. 공개 포트 8080(Caddy 뒤)에는 `/actuator` 가 아예 없다. compose 는 8081 을 publish 하지 않고, Prometheus 가 compose 내부망에서 `backend:8081` 을 긁는다.
- **이름**: Prometheus 이름은 Micrometer 이름의 `.` 을 `_` 로 바꾸고 카운터에는 `_total`, 시간에는 `_seconds` 를 붙인 것이다. 모든 시계열에 `env` 태그(`local` | `prod` | `loadtest`, 환경변수 `METRICS_ENV`, 기본 `local`)가 붙어 부하 시험 값이 실제 사용 값과 섞이지 않는다.
- **단위 예외**: 이 절의 시간은 Prometheus 관례대로 **초(소수)** 다(이 문서 머리의 "시간 ms 정수" 규칙의 예외). 값 자체는 §1 과 같다. 예를 들어 `beside_job_processing_seconds` 에 기록되는 값은 같은 실행의 `processingMs / 1000` 이다(둘 다 `JobRun` 한 객체에서 나온다). 보고서 표에 옮길 때는 ms 로 바꾸고 §1 이름을 쓴다.
- **정확도**: 백분위수는 아래 고정 버킷 사이를 `histogram_quantile` 이 선형 보간한 **근사값**이다. 정확한 분포와 실행별 값은 §1.1 `job_runs` 에서 SQL 로 뽑는다. 카운터는 서버를 재시작하면 0 부터 다시 센다(`rate`·`increase` 가 이를 보정한다).
- 부하 시험 도구(k6) 쪽 값 `load.*` 는 `bench.*` 처럼 `docs/perf` 전용이며 여기 등록하지 않는다(REVIEW_CHECKLIST 안건 #15).

### 5.1 서버 지표 (`beside.*`)

| Micrometer 이름 | Prometheus 이름 | 종류 | 태그 | 정의 | 기록하는 곳 |
|---|---|---|---|---|---|
| `beside.jobs.finished` | `beside_jobs_finished_total` | counter | `result`(`COMPLETED`\|`FAILED`), `code`(error.code, 성공이면 `none`), `worker`(`mock`\|`real`), `passthrough`(`true`\|`false`) | 끝난 실행(attempt) 수. retry 마다 하나 더 | `JobMetrics`(JobFinisher 가 내는 `JobFinishedEvent`) |
| `beside.job.queued` | `beside_job_queued_seconds_{bucket,sum,count,max}` | timer | `worker`, `result`, `passthrough` | 그 실행의 §1 `queuedMs` | 같은 곳 |
| `beside.job.processing` | `beside_job_processing_seconds_*` | timer | 같음 | §1 `processingMs`. PROCESSING 없이 끝난 실행은 기록하지 않는다 | 같은 곳 |
| `beside.job.total` | `beside_job_total_seconds_*` | timer | 같음 | §1 `totalMs` | 같은 곳 |
| `beside.jobs.queue` | `beside_jobs_queue` | gauge | `state`(`pending`\|`processing`) | 지금 PENDING / PROCESSING 인 작업 수. 5초(`metrics.queue.refresh-ms`)마다 DB 에서 센 값을 들고 있어 스크레이프는 DB 를 읽지 않는다(최대 5초 묵은 값) | `JobQueueGauge` |
| `beside.jobs.created` | `beside_jobs_created_total` | counter | — | 접수해 워커에 넘긴 작업(v1·v0). Idempotency-Key 재요청과 종료 중 거절은 세지 않는다 | `JobServiceImpl` |
| `beside.jobs.retried` | `beside_jobs_retried_total` | counter | — | 접수해 워커에 넘긴 retry | `JobServiceImpl` |
| `beside.asset.downloads` | `beside_asset_downloads_total` | counter | `outcome`(아래) | `GET /api/v1/jobs/{id}/asset` 한 번마다 하나 | `AssetDownloadMetricsFilter` |
| `beside.asset.download` | `beside_asset_download_seconds_*` | timer | `outcome` | 요청 도착 → 마지막 바이트를 쓴 때 | 같은 곳 |

`beside.asset.downloads` 의 `outcome`:

| 값 | 뜻 |
|---|---|
| `ok` | 2xx 이고 `Content-Length` 만큼 모두 연결에 넘김 |
| `client_abort` | 쓰는 중 연결이 끊김(IOException, 마지막 flush 실패) 또는 `Content-Length` 보다 적게 씀 — 앱이 화면을 떠났거나 네트워크가 끊긴 경우. 서버 로그에는 ERROR 대신 DEBUG 한 줄만 남는다 |
| `not_found` | 404 (`JOB_NOT_FOUND`, `ASSET_NOT_FOUND`) |
| `not_completed` | 409 (`JOB_NOT_COMPLETED`, 완료 전에 받으려 함) |
| `error` | 5xx, 처리 중 예외, 그 밖의 상태(400 잘못된 variant) |

바이트는 서버가 Tomcat 에 넘긴 기준이다. 마지막 바이트까지 소켓 버퍼에 넘긴 뒤 클라이언트가 떠나면 `ok` 로 남는다. 다섯 값은 기동할 때 0 으로 등록되고, `beside_jobs_created_total`·`beside_jobs_retried_total`·`beside_jobs_queue` 도 처음부터 있다. `beside_jobs_finished_total` 과 작업 타이머는 해당 태그 조합의 첫 실행이 끝날 때 생긴다.

버킷(SLO, 초): 작업 타이머 1, 5, 10, 30, 60, 120, 300, 600, 1800(앱은 600초에 포기하고, real,prod 의 오프라인 대기는 최대 1800초). 에셋 다운로드 0.1, 0.25, 0.5, 1, 2.5, 3, 5, 6, 10, 30, 60(부하 합격 기준 3초·6초 포함). API 응답 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30.

주의: 재시작으로 끊긴 실행(`code="INTERNAL_ERROR"`, §1 주의)도 `FAILED` 로 세어지고 시간에는 서버가 꺼져 있던 시간이 들어간다. 패스스루 실행은 `passthrough="true"` 로 거른다. Mock 실행은 `worker="mock"` 이다.

### 5.2 내장 지표 (Spring Boot / Micrometer)

| Prometheus 이름 | 보는 것 |
|---|---|
| `http_server_requests_seconds_{bucket,sum,count,max}` (`uri`, `method`, `status`, `outcome`, `exception`) | API 응답 시간. `uri` 는 경로 템플릿(예 `/api/v1/jobs/{jobId}`)이라 jobId 마다 시계열이 생기지 않는다. 에셋 경로의 시간에는 본문 전송 시간도 들어간다 |
| `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `jvm_gc_memory_allocated_bytes_total` | 힙, GC(합격 기준 'GC 뒤 힙 < 75 %') |
| `process_cpu_usage`, `system_cpu_usage` | CPU |
| `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | 요청 스레드 포화(`server.tomcat.mbeanregistry.enabled=true` 가 필요해 켜 둠) |
| `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds_*` | DB 커넥션 풀 |
| `executor_active_threads`, `executor_queued_tasks` (`name="applicationTaskExecutor"`) | 워커가 쓰는 `@Async` 풀 |
| `disk_free_bytes` | 저장소 디스크 여유 |

### 5.3 멘토 지표 5종 → PromQL

예시는 `env="prod"` 기준이다. 부하 시험은 `env="loadtest"` 로 바꾼다. 범위(`[1h]`, `[15m]`)는 대시보드 기간에 맞춘다.

| 멘토 지표 | PromQL |
|---|---|
| 생성 성공률 | `sum(increase(beside_jobs_finished_total{env="prod",passthrough="false",result="COMPLETED"}[1h])) / sum(increase(beside_jobs_finished_total{env="prod",passthrough="false"}[1h]))` · 실패 코드별 `sum by (code) (increase(beside_jobs_finished_total{env="prod",result="FAILED"}[1h]))` |
| 생성 시간 평균 | `sum(rate(beside_job_processing_seconds_sum{env="prod",result="COMPLETED",passthrough="false"}[15m])) / sum(rate(beside_job_processing_seconds_count{env="prod",result="COMPLETED",passthrough="false"}[15m]))` |
| 생성 시간 p95 | `histogram_quantile(0.95, sum by (le) (rate(beside_job_processing_seconds_bucket{env="prod",result="COMPLETED",passthrough="false"}[15m])))` · 앱이 느끼는 시간은 `beside_job_total_seconds_bucket`, 대기만은 `beside_job_queued_seconds_bucket`(GPU 증설 기준 'p95 queuedMs > 60초') |
| 대기열 길이 | `beside_jobs_queue{env="prod",state="pending"}` · 10분 최대 `max_over_time(beside_jobs_queue{env="prod",state="pending"}[10m])` · 들어온 양 대 끝난 양 `sum(rate(beside_jobs_created_total{env="prod"}[5m]))` 대 `sum(rate(beside_jobs_finished_total{env="prod"}[5m]))` |
| API 응답 시간 | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{env="prod",uri=~"/api/v1/.*"}[5m])))` · 5xx 비율 `sum(rate(http_server_requests_seconds_count{env="prod",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{env="prod"}[5m]))` |
| 에셋 다운로드 실패율 | `sum(increase(beside_asset_downloads_total{env="prod",outcome=~"client_abort\|error"}[1h])) / sum(increase(beside_asset_downloads_total{env="prod",outcome=~"ok\|client_abort\|error"}[1h]))` · `not_found`·`not_completed` 는 따로 본다(완료 전 요청·잘못된 id 는 서버 실패가 아니다. 다만 완료된 작업의 파일이 없어진 `ASSET_NOT_FOUND` 도 `not_found` 에 들어가므로 0 이 아니면 로그를 확인한다) |

표 안의 `\|` 는 표 구분자를 피하려고 쓴 것이고, PromQL 에는 `|` 로 쓴다.

확인 범위 **[구현됨]**: §5.1 의 이름·태그·버킷, 관리 포트 분리(공개 포트 `/actuator*` 404), 실제 Tomcat 연결을 RST 로 끊은 다운로드의 `client_abort` 는 backend 테스트(`ObservabilityEndpointTest`, `JobMetricsTest`, `OperationalMetricsFlowTest`, `AssetDownloadMetricsFilterTest`, `RealJobWorkerTest` 의 패스스루)로 확인했다. §5.2 중 스크레이프에 있는지 테스트가 보는 것은 `http_server_requests_seconds_bucket`(SLO 버킷·`env`), `jvm_memory_used_bytes`, `jvm_gc_memory_allocated_bytes_total`, `process_cpu_usage`, `tomcat_threads_busy_threads`, `hikaricp_connections`, `executor_active_threads`, `disk_free_bytes` 이고 나머지는 Spring Boot 3.2 기본 이름이다. **[계획]**: 위 PromQL 을 실제 Prometheus·Grafana 에 넣어 보는 것과 VM 에서 잰 값(PLAN 4b 부하 시험).
