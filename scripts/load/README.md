# scripts/load — 목표 트래픽 모델과 k6 부하·장애 시험

쏘카 수준 목표 트래픽의 계산·시험 도구다. 가정·목표 등급·공식·합격 기준·시험 구성의 근거는 [docs/perf/load/TRAFFIC_MODEL.md](../../docs/perf/load/TRAFFIC_MODEL.md) 한 곳이고, 이 README 의 "§N" 은 모두 그 문서의 절이다(§2 가정, §3 목표 등급, §4 공식, §7 디스크, §8 합격 기준, §9 시험 구성, §10 정직성 규칙, §11 이름). 트래픽 모델(`model.py`)이 T1/T2/T3 의 요청률과 GPU 대수를 계산하고, k6 스크립트가 그 요청률을 배포 VM 에 건다. 결과는 k6 실행 하나마다 폴더 하나(`docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/`)에 모으고, `collect.py` 가 그 폴더에 조건과 함께 `run.json` 을 쓴다.

**상태**
- 스크립트: **[구현됨]**. 단위 테스트(Python 84개, Node 82개)와 `node --check` 문법 검사를 통과했다. 개발 PC 에 설치한 k6 v2.3.0(아래 "k6 설치")으로 루프백에 짧고 줄인 실행을 돌려 스크립트 동작을 확인했다(A 는 로컬 mock jar, G·F 일부는 real,prod jar 와 패스스루 스텁, 가드·`collect.py` 는 대역 Prometheus — 아래 "k6 v2.3.0 에서 확인한 것"). 동작 확인용이며 용량 결과가 아니다.
- 트래픽 모델: **[파생]** — `tiers.json` 의 **[가정]** 으로 계산한 값이다. 측정값이 아니다.
- 측정(A0~A6, G1~G3, F1~F5): **[계획]**. VM·Caddy·실제 Prometheus·Docker·GPU 에서는 아직 한 번도 돌리지 않았다.

## 파일

| 파일 | 내용 |
|---|---|
| `tiers.json` | [가정] 한 곳(§2): 티어별 MAU, DAU/MAU, 피크 비율, 세션 분, DAU당 생성, 스파이크, 사진·GLB 크기, 실패율, 폴링 2초, 앱 최대 대기 600초, 예시 T(30/60/120/300초), 목표 ρ 0.7, 그리고 k6 단계 목록(`load`) |
| `model.py` | tiers.json → 파생 표(DAU, 피크 동시 사용자, λ, RPS(GPU 충분/포화/스파이크), 에셋·업로드 Mbps, 디스크 GB/일, T별 필요 GPU, 1대 용량, Q_max, G1 예측). Markdown 출력, `--json` 은 JSON. `--queue-rate-per-h λ --queue-service-s S --queue-slots c` 는 G 시리즈 예측(ρ, M/M/c Wq, c = 1 이면 M/D/1 Wq)을 측정한 도착률과 평균 슬롯 시간으로 다시 계산한다 |
| `smoke.js` | **A0** 연기 시험: 10초에 흐름 1개, 3분. healthz p50 = **RTT_base** 를 요약에 남긴다 |
| `api_staged.js` | **A1** 단계형 혼합 흐름(동시 25→200→1,000→1,800→3,600→5,400→7,200→9,000, 단계마다 증가 1분 + 유지 8분). `SCENARIO=spike` 는 **A5**, `SCENARIO=soak` 는 **A6** |
| `poll_saturation.js` | **A2** 폴링 포화: GET /jobs/{id} 100→300→600→900→1,200→1,800 RPS, 각 5분. id 는 `IDS_FILE`(절대 경로 여러 개를 쉼표로 잇고 목록을 합친다)이나 setup 의 시드에서 온다. `SEED_ONLY=1` 은 시드해 `IDS_OUT` 만 쓰고 부하는 걸지 않는다(A2 의 세 실행은 아래 VM 절) |
| `upload.js` | **A3** 업로드: `SIZE=small`(0.8 MB, 1→20/s), `SIZE=large`(약 20 MB, 0.5→2/s). 단계마다 시나리오 하나(`lvl0`, `lvl1`, …) |
| `asset.js` | **A4** 에셋 송신 상한: 1→40 다운로드/s, 단계마다 시나리오 하나 |
| `gpu_queue.js` | **G1~G3**, **F1a**, **F1b**, **F2** GPU 큐: λ = RHO·SLOTS/T_SEC 의 푸아송 도착(솎아내기 `ARRIVAL_THIN`)으로 흐름을 건다(스텁 패스스루). F1b 는 `FAIL_TOKEN=slow` 로 스스로 알아보고, 실패를 스텁·VM 쪽에서 거는 F1a·F2 는 `SCENARIO` 로 이름을 붙인다. 요약에 측정한 도착률과 평균 슬롯 시간으로 예측을 다시 계산하는 명령(`meta.recompute`)을 남긴다 |
| `failure/f3_uploads.js` | **F3** 잘못된 업로드 9종의 상태·`error.code` 일치(계획한 업로드를 모두 판정했는지 포함), 그동안 폴링 p95. 끝에 사례별 줄과 남은 파일 확인 명령을 출력한다 |
| `failure/f3_throttled.py` | **F3** 느린 업로드: 유효한 사진 하나(0.4 MB)를 10 kB/s 로 올려 202·걸린 시간(약 40초)·k6 폴링 구간 안인지·그동안 폴링 p95 를 확인하고, 로컬 서버면 남은 파일도 센다(표준 라이브러리). k6 바로 앞에 백그라운드로 띄운다. 종료 코드 0 통과, 1 확인 실패, 2 시작 못 함 |
| `failure/f4_abort.py` | **F4** 에셋 다운로드 N회를 본문 중간에 끊고, 작업 COMPLETED 유지와 다시 받은 파일의 SHA-256 일치를 확인(표준 라이브러리) |
| `collect.py` | 결과 수집: 실행 폴더 하나에 `run.json`(조건·판정·끝난 방식 `outcome`·인용 후보 `resultCandidate`) + Prometheus query_range CSV 18개 + 첨부 복사 |
| `make_fixtures.py` | 입력 파일: `photo-400k.png`(0.40 MB), `photo-5mb.png`(4.99 MB), `glb-1.5mb.glb`(1,500,000 B) → `scripts/load/out/fixtures/`(커밋 안 함). GLB 는 부하 시험의 유일한 합성 GLB 다: `scripts/perf/fixtures.py` 의 `glb_synthetic(1_500_000, seed=14)` 와 같은 바이트, sha256 `03a0c8f2b05aee204237cee07ddb41c42173014b33ef0007a43c7b83e2fc0ee9`(deploy/loadtest/README.md 3번의 `/tmp/lt-sample-1500000.glb` 와 같다) |
| `lib/` | k6 공용: `config`(환경·사용자→도착률), `stages`(경과 시간으로 단계 태그, 단계마다 시나리오 하나), `multipart`, `bodies`(사진은 VU 마다 한 번만 열기), `flow`(사용자 흐름), `classify`, `thresholds`(§8 기준), `summary`(handleSummary), `seed`, `guard`(Prometheus 중단 감시), `queue`, `metrics` |
| `testing/`, `*.test.mjs` | Node 테스트: k6 모듈을 대역으로 바꿔 각 스크립트의 init(단계·기준)과 흐름을 가짜 API 서버로 돌린다 |

## 사용자 흐름과 태그

`lib/flow.js` 는 Unity `JobManager` 와 같은 순서로 공개 경로만 쓴다(Basic 인증 경로는 부르지 않는다).

1. `GET /api/v1/healthz`
2. `POST /api/v1/jobs` — 사진 2장(multipart `photos` × 2), 파일명 `lt-<RUN_ID>-1.png`, 헤더 `Idempotency-Key: lt-<RUN_ID>-<uuid>`
3. `GET /api/v1/jobs/{id}` 를 응답 뒤 2초마다, COMPLETED/FAILED 까지. 600초가 지나면 앱처럼 포기(`load_flowTimeoutRatio`)
4. COMPLETED → 에셋 한 번(`Content-Length` = `asset.bytes` 확인). FAILED → 3초 뒤 `POST /retry` 한 번, 다시 3
5. `FAIL_RATIO`(A1 기본 0.05) 만큼의 흐름은 파일명에 `FAIL_TOKEN`(`fail`)을 넣는다. mock 워커와 스텁(`BESIDE_PASSTHROUGH_FAIL_TOKEN`)이 그 작업을 실패시키므로 retry 경로가 실제로 돈다. 그래서 `RUN_ID` 에 `FAIL_TOKEN` 이 들어가면 시작을 거부한다. F1b 는 `FAIL_TOKEN=slow` 로 같은 장치를 스텁의 `BESIDE_PASSTHROUGH_SLOW_TOKEN` 에 쓴다(아래 VM 절)

모든 요청에 `endpoint=healthz|create|poll|asset|retry`, 고정 `name`(작업 ID 없음), 단계 태그 `stage=<단계 번호>`·`phase=ramp|hold|drain` 이 붙는다. 단계 태그는 원격 jslib 없이 `exec.scenario.startTime` 부터의 경과 시간으로 계산한다(`lib/stages.js`; 단계마다 시나리오인 A3·A4 는 그 시나리오가 시작한 초만큼 되돌려 전체 시간표에 맞춘다). A2·A0·G 는 시나리오 `tags` 로 붙인다.

k6 값 이름은 `load_` 접두사다(§11 의 `load.*`, k6 는 점을 못 쓴다): `load_postMs`, `load_pollMs`, `load_assetMs`, `load_assetTtfbMs`, `load_healthzMs`, `load_retryMs`, `load_flowMs`, `load_waitMs`, `load_pollsPerWait`, `load_serverQueuedMs`·`load_serverProcessingMs`·`load_serverTotalMs`(서버 `timings.queuedMs`·`processingMs`·`totalMs`), `load_errorRatio`, `load_flowTimeoutRatio`, `load_flowOkRatio`, `load_flowStarted`·`load_flowEnded`(흐름이 시작할 때와 끝날 때 1씩, 아래 "실행이 어떻게 끝났는지"), `load_unexpectedCount`, `load_rejectedCount`, `load_assetBytes`, `load_bytesSent`(202 를 받은 POST 본문 바이트, 단계별 업링크용 하한 — 거부·끊긴 업로드는 빠짐), A2 의 `load_pendingRechecked`·`load_pendingLeft`(teardown 이 다시 본 pending id 수와 그중 PENDING 이 아니게 된 수), 가드의 `load_guardQueries`·`load_guardFailures`·`load_guardLastFailure`·`load_guardErrorRatio`·`load_guardDiskRatio`·`load_guardAbort`, F3 의 `load_f3MatchRatio`·`load_f3ResetCount`. 기준(threshold)이 걸린 것은 `load_errorRatio`, `load_flowTimeoutRatio`·`load_flowOkRatio`(흐름 스크립트), `load_unexpectedCount`(A2·A3), `load_pendingLeft`(A2), `load_f3MatchRatio`(F3)이고, 지연 기준은 `http_req_duration{endpoint:…}` 에 건다. `load_bytesSent`·`load_assetBytes`·`load_flowStarted`·`load_flowEnded` 의 단계 하위 지표에는 요약에 남기려는 빈 기준(`count>=0`)만 있고, 기준이 없는 지표는 표본이 있을 때만 요약에 나온다. `bench.*` 처럼 docs/perf 전용이며 METRICS.md 에는 올리지 않는다(안건 #15).

## 합격 기준 (§8, [제안값 — 계약 회의 전])

`RTT_BASE_MS` 는 같은 대상에 돌린 A0 의 healthz p50(`meta.rttBaseMsMeasured`)이다. 원격 `BASE_URL` 에서 이 값 없이 A1~A6·G·F3 을 돌리면 시작을 거부한다(0 ms 로 판정하지 않게). A0 는 이 값을 재는 실행이라 원격에서 이 값이 없으면 지연 기준 없이 돌고, A2 의 시드 실행(`SEED_ONLY`)은 판정이 없어 필요 없다.

| 엔드포인트 태그 | p95 | p99 |
|---|---|---|
| `healthz` | RTT + 50 ms | RTT + 150 ms |
| `poll` | RTT + 100 ms | RTT + 300 ms |
| `create` (0.8 MB) | 1,500 ms | 3,000 ms |
| `asset` | 첫 바이트(`http_req_waiting`) RTT + 150 ms, 전체 3,000 ms | 전체 6,000 ms |

- `load_errorRatio`(5xx + 연결 오류) < 0.5 %. 429 `RATE_LIMITED`·503 `QUEUE_FULL`([계획] R1~R3, 아직 계약에 없음)은 `load_rejectedCount` 로 따로 세고 기준을 걸지 않는다. 이런 거부가 유지 구간(특히 A3)을 실패로 만들어야 하는지는 정하지 않았다: 계약 안건 #19(큐 상한·503)·#20(요청 제한·429)과 함께 정한다.
- 흐름 스크립트(A0·A1·A5·A6·G)는 `load_flowTimeoutRatio == 0`, `load_flowOkRatio >= 0.95`(G 는 ρ < 1 일 때만). 모든 스크립트(F3 포함)는 `dropped_iterations == 0`.
- **A2** 는 `load_unexpectedCount == 0` 도 전체와 단계마다 건다. 폴링이 404 같은 기대하지 않은 상태를 받으면 오류(5xx)가 아니라 'unexpected' 로만 세어지고, 빠른 404 는 지연 기준도 통과하기 때문이다. id 는 상태까지 확인한다:
  - setup 이 id 약 20개(두 목록에 다 있으면 completed 10 + pending 10)를 GET 해, 200 과 그 목록의 상태(completed → COMPLETED, pending → PENDING)가 아니면 시작하지 않는다(k6 종료 코드 107). 메시지는 찾은 것을 묶어 원인을 적는다: pending → FAILED 는 PENDING 시드 뒤 backend 재시작, pending → PROCESSING·COMPLETED 는 `mock.worker.pending-ms` 만료, 404 는 다른 서버나 비운 볼륨. 방금 시드한 PENDING 작업도 1초 뒤 같은 방법으로 확인한다. setup 로그(`setup: the oldest sampled PENDING id has waited …`)가 이 실행에 필요한 `pending-ms` 의 최솟값을 적는다.
  - 부하가 끝나면 teardown 이 pending id 를 최대 20개 다시 GET 한다. 다른 상태나 404 는 `load_pendingLeft` 로 세고(응답 없음·5xx 는 어느 쪽에도 세지 않는다) `load_pendingLeft == 0` 이 기준이다: 실행 중에 pending-ms 가 다 됐거나 backend 가 재시작했으면 실패(종료 코드 99)이고 요약 글에 `pending ids: …` 줄이 나온다(`meta.ids.endCheck` = `{ checked, leftPending }`).
  - 요약 글은 실패한 check 수와 `load_unexpectedCount` 가 0 이 아니면 출력한다.
- **A3** 도 `load_unexpectedCount == 0` 을 전체와 유지 구간마다 건다. 모든 업로드가 유효하므로 202 가 아닌 4xx(Spring·프록시의 413, 400 `INVALID_REQUEST`·`UNSUPPORTED_IMAGE_TYPE`, 프록시 404, `RATE_LIMITED` 코드가 없는 429)는 오류가 아니라 'unexpected' 로만 세어지고, 빠른 거부는 지연 기준도 통과하기 때문이다. 5xx·연결 오류는 `load_errorRatio`, 429 `RATE_LIMITED`·503 `QUEUE_FULL` 은 `load_rejectedCount`(기준 없음, 위)로만 세므로 한 응답을 두 번 세지 않는다. `SIZE=large`(20 MB)의 create 지연은 판정하지 않고 유지 구간마다 `verdict.stages[].latency.create` 와 요약 글의 열에 보고만 한다.
- 단계가 있는 스크립트(A1~A6)는 같은 기준을 `{stage:N,phase:hold}` 로 단계마다 한 번 더 건다. 요약의 `verdict.stages`(단계마다 `pass | fail | not_run | invalid | incomplete`)와 `verdict.breakingPoint`(마지막 통과 단계, 처음 실패 단계, 운영 상한 = 마지막 통과 사용자 수 × 7 / 10 의 내림)가 여기서 나온다. `not_run` 은 표본이 없는 단계, `invalid` 는 단계에 묶지 못한 drop 때문에 통과로 칠 수 없는 단계, `incomplete` 는 중단된 실행에서 잘린 유지 구간이나 흐름이 잘린 유지 구간이다(아래 "실행이 어떻게 끝났는지"). 표본이 없는 엔드포인트는 지연 항목이 없고(글에서 `-`), 돌지 않은 단계의 `heldS`·`sentMbps`·`assetMbps` 는 0 이 아니라 null 이다.
- `breakingPoint` 는 단계가 올라가기만 하는 탐색(A1~A4)에 있고, 운영 상한은 사용자 수가 있는 A1 만 낸다. A5(스파이크 1,800 → 5,400 → 1,800)와 A6(소크 한 단계)는 null 이고 `breakingPointNote` 가 "not an increasing search" 라고 적는다. 단계별 판정만 읽는다.
- **drop 이 있는 단계는 통과가 아니다.** 단계마다 시나리오가 따로인 A2(`rps0`…)·A3·A4(`lvl0`…)는 `dropped_iterations{scenario:…}` 이 그 단계만 `fail` 로 만들고 앞 단계의 판정은 그대로다(`verdict.stages[].dropped`). 한 `ramping-arrival-rate` 시나리오로 모든 단계를 도는 A1·A5·A6(`api_staged.js`)은 k6 가 drop 에 시나리오 이름만 붙여 단계를 알 수 없으므로, drop 이 하나라도 있으면 `pass` 단계를 모두 `invalid` 로 바꾸고 `breakingPoint` 를 null 로 둔다(이유는 `verdict.breakingPointNote`). 다시 돌릴 때는 `PRE_VUS` 를 올린다: k6 v2.3.0 은 미리 만든 VU 가 모자라면 반복을 drop 하고서야 VU 를 하나 더 시작하므로 `MAX_VUS` 만 올려서는 drop 이 없어지지 않는다(메모리는 아래 "디스크와 VU").
- **업링크**: `verdict.uplinkRatio` = max(가장 바쁜 단계 유지 구간의 업로드 본문 Mbps, 전체 평균 `data_sent` Mbps) / `UPLINK_MBPS`, 0.7 을 넘으면 `uplinkSaturated`. 단계 값은 `load_bytesSent{stage:N,phase:hold}` × 8 / 그 유지 구간이 실제로 돈 초 `heldS`(A1·A3·A5·A6, 단계 항목의 `sentMbps`, `peakStage`·`peakStageSentMbps`)이고, 전체 평균은 `uplinkRatioWholeRun` 으로 따로 남는다. 202 를 받은 본문만 센다: 거부·리셋·타임아웃·잘린 업로드는 본문을 얼마나 보냈는지 몰라 빠지고 헤더·폴링·TLS 몫도 빠지므로, 단계 값은 하한이다.
- **중단**: `load_errorRatio` 누적 > 5 % 를 첫 1분 뒤부터 `abortOnFail`(A0·F3 제외, k6 종료 코드 99). k6 기준은 누적값이라 "1분 지속"을 정확히 못 본다. `PROM_URL` 을 주면 `lib/guard.js` 가 15초(`GUARD_EVERY_S`)마다 서버의 최근 1분 5xx 비율(> 5 % `GUARD_MAX_ERROR_RATIO` 가 `GUARD_BREACHES` 2번 연속)과 디스크 사용률(> 80 % `GUARD_MAX_DISK_RATIO`)을 보고 `exec.test.abort()` 한다(k6 종료 코드 108; A0·F3 에는 가드가 없다). 무엇이 멈췄는지(가드, `abortOnFail`, Ctrl+C)는 `verdict.aborted` 에 남는다(아래).
  - 가드는 `per-vu-iterations` 의 반복 한 번(VU 1개)이다. 부하의 예정 끝이 지나고 부하 VU 가 모두 끝나면(드레인 포함) 같이 끝나므로, 실행을 gracefulStop 상한까지 붙잡지 않는다. 가드의 Prometheus 요청은 `endpoint=guard` 라 `load_*` 에는 들어가지 않지만 k6 의 `http_reqs`·`data_sent`(요약의 `httpReqs`·`dataSentMbps`)에는 들어간다.
  - Prometheus 를 못 읽은 응답(HTTP 오류, 응답 없음, Prometheus 결과가 아닌 본문, `disk_free_bytes` 시리즈가 없는 빈 디스크 결과)은 가드를 조용히 끄지 않는다. 못 읽는 구간이 시작될 때와 다시 읽힐 때 경고를 한 번씩 내고 `load_guardQueries`·`load_guardFailures`·`load_guardLastFailure` 로 센다. 5xx 결과가 비어 있으면 5xx 0 으로 본다.
  - 한 번도 읽지 못한 채 `GUARD_BLIND_ABORT`(기본 3, 0 이면 끔)번 연속 못 읽으면 실행을 멈춘다(`PROM_URL`·터널·`PROM_ENV` 설정 실수를 시작하자마자 알리려고). 한 번이라도 읽은 뒤에는 못 읽어도 멈추지 않고 경고와 수만 남긴다.
  - 요약 `verdict.guard` = `{ promUrl, env, queries, failures, lastError, maxErrorRatio, maxDiskRatio }`(`PROM_URL` 이 없으면 null). `failures` > 0 이면 "가드가 다 지키지 못한 실행"이라고 글에 적지만 `thresholdsOk` 는 바꾸지 않는다.

### 실행이 어떻게 끝났는지 (`verdict.aborted`)

k6 v2.3.0 은 어떤 끝(정상 0, setup 실패 107, 가드 108, `abortOnFail` 99, Ctrl+C 105, REST API 정지 103)에도 `handleSummary` 를 부르지만 어떻게 끝났는지는 알려 주지 않고, 표본이 없는 지표의 기준은 통과로 친다. 그래서 `lib/summary.js` 가 끝을 다시 짜 맞춘다. 정상 끝이면 `verdict.aborted` 는 null, 아니면 `{ by, reason, atS, plannedS, cutStages, unfinishedFlows }` 다.

| `by` | 언제 |
|---|---|
| `setup` | setup() 이 끝나지 않았다(`setup_data` 없음). 부하는 돌지 않았다 |
| `guard` | 가드가 멈췄다(`load_guardAbort`: 1 = 5xx, 2 = 디스크, 3 = 처음부터 Prometheus 를 못 읽음) |
| `threshold` | 부하가 예정 끝 전에 멈췄거나 흐름이 잘렸고, `abortOnFail` 기준이 실패했다 |
| `interrupted` | 부하가 예정 끝(`plannedS`)보다 1초 넘게 일찍 멈췄고 위의 어느 것도 아니다(Ctrl+C, REST API 정지) |
| `unfinished` | 부하는 예정 끝까지 갔지만 k6 가 끝날 때 아직 돌던 흐름이 있다: 드레인(gracefulStop) 중의 Ctrl+C·REST 정지, 또는 gracefulStop 이 다 됨 |

- 부하 길이(`atS`, `meta.loadRanS`)는 요약 시각 − `setup_data.readyAtMs`(setup 끝)다. `testRunDurationMs` 는 setup(asset.js·poll_saturation.js 는 수 분의 시드)을 포함해서 쓰지 않는다. 예정 길이 `plannedS`(`meta.loadPlannedS`)는 단계형은 마지막 유지 구간의 끝, smoke·gpu_queue 는 `DURATION_S`, F3 은 `F3_BG_S + 10` 이다.
- **잘린 흐름**: k6 는 중간에 끊은 반복에 아무 표본도 남기지 않는다. 그래서 흐름(user flow, upload.js 의 업로드 하나, asset.js 의 다운로드 하나, F3 의 라운드 — VU 하나가 모든 사례를 한 번씩 보내는 반복)마다 시작할 때 `load_flowStarted`, 끝날 때 `load_flowEnded` 를 더하고, 그 차이(`verdict.unfinishedFlows`, 세지 않는 A2 는 null)가 결과를 모르는 흐름 수다. 잘린 흐름은 가장 느린 흐름(과부하 단계의 타임아웃)이라 하나라도 있으면 통과가 아니다. 그래서 gracefulStop 기본값은 정상 끝에서 어떤 흐름도 자르지 않게 잡았다: 흐름 스크립트(A0·A1·A5·A6·G)는 가장 긴 흐름(FAILED 로 끝난 대기 `MAX_WAIT_S` + `RETRY_WAIT_S` + retry 뒤 대기 `MAX_WAIT_S`) + 120초 = 1,323초(A1·A5·A6 은 `GRACEFUL_STOP_S` 로 바꿀 수 있고, healthz 만 하는 browse 시나리오는 30초), upload.js 는 `REQUEST_TIMEOUT`(120s) + 60초, asset.js 는 120초(요청 타임아웃 60초 + 60초), F3 의 잘못된 업로드는 `GRACEFUL_STOP_S`(기본 180초 = 요청 타임아웃 120초 + 60초)이고, A2 의 단계는 10초다. 정상 끝에서는 모든 흐름이 끝난다.
- 잘린 유지 구간(`cutStages`): 예정 끝이 멈춘 시각보다 뒤인 구간(`heldS` 는 실제로 돈 초), 그리고 자기 흐름이 잘린 구간(A1 의 흐름은 유지 구간보다 길 수 있다; `heldS` 는 `holdS` 그대로). 그때까지 통과였으면 `incomplete`, 이미 실패였으면 `fail` 로 남는다. `sentMbps`·`assetMbps` 는 `heldS` 로 나눈다.
- 중단된 실행은 `thresholdsOk` false 이고, 요약 글 첫머리에 `SETUP FAILED`·`ABORTED by …`·`STOPPED EARLY …`·`FLOWS CUT OFF …` 줄이 나오며 "thresholds: all passed" 는 나오지 않는다.
- `breakingPoint`: abortOnFail 오류 기준(`threshold`)이나 가드의 5xx 기준(`guard`, `load_guardAbort` 1)이 멈춘 실행은 그대로 계산한다. A1 의 탐색은 깨지는 단계에서 이렇게 멈추는 것이 정상 경로이기 때문이다. 처음 `fail` 또는 `incomplete` 단계가 첫 실패 단계이고, 마지막 통과 단계는 끝까지 돌고 흐름도 모두 끝난 단계다(`breakingPointNote` 에 이유). 같은 규칙은 서버 재시작·재배포·네트워크 끊김에도 걸리므로, 용량 한계로 읽기 전에 그 시각을 확인한다(아래 "정직성 규칙"). 그 밖의 중단(setup, 디스크, Prometheus 를 못 읽음, Ctrl+C·REST 정지, 드레인에서 잘린 흐름)은 용량과 무관하므로 null 이다. A5·A6 과 단계에 묶지 못한 drop 도 위 규칙대로 null 이다.
- VM CPU < 85 %, GC 뒤 힙 < 75 % 는 k6 가 아니라 `collect.py` 의 Prometheus CSV(`process_cpu`, `system_cpu`, `jvm_heap_after_gc` — 마지막 GC 뒤 long-lived(G1 Old Gen) 사용/최대, 0~1, < 0.75 이면 통과)로 판정한다. `jvm_heap_used/max` 는 Eden 을 포함한 순간값이라 판정에 쓰지 않는다.

## k6 설치

개발 PC 에는 winget 패키지 `GrafanaLabs.k6` 로 설치한 k6 v2.3.0 이 `C:\Program Files\k6\k6.exe` 에 있다. PATH 에 잡히지 않은 셸(설치 전에 연 셸 등)에서는 전체 경로로 부르거나 새 셸에서 `k6 version` 으로 확인한다. 돌려 본 버전은 v2.3.0 하나뿐이고, 끝난 방식의 판정과 아래 "k6 v2.3.0 에서 확인한 것"은 이 버전의 동작이다. 다른 버전은 확인하지 않았다.

```powershell
winget install --id GrafanaLabs.k6 --source winget
k6 version          # Windows 는 "k6.exe v2.3.0 (commit/e088784614, go1.26.8, windows/amd64)" 처럼 나온다
```

Linux(두 번째 VM 등)는 k6 문서의 apt 저장소 절차를 따른다([계획], 아직 설치하지 않았다). 설치한 버전은 `collect.py --k6-version "$(k6 version)"` 로 run.json 에 남긴다.

## 준비

```powershell
python scripts/load/make_fixtures.py          # 사진·GLB 를 scripts/load/out/fixtures/ 에 만든다 (GLB sha256 03a0c8f2…)
python scripts/load/model.py                  # 목표 표 확인 (--json 으로 JSON)
```

- 명령은 리포 루트에서 실행한다. k6 는 `open()` 의 상대 경로를 스크립트 위치 기준으로 읽으므로 기본 사진 경로(`./out/fixtures/...`)는 어디서 실행해도 `scripts/load/out/fixtures/` 를 가리킨다. 다른 사진과 `IDS_FILE` 은 절대 경로로 준다(`PHOTOS=a.png,b.png` 또는 `PHOTO_1`/`PHOTO_2`). A2 `SEED_ONLY` 의 `IDS_OUT` 도 절대 경로다(시드 전에 그 폴더를 확인한다).
- `SUMMARY_PATH`(와 `IDS_OUT`)는 현재 폴더 기준으로 쓰이고, 그 폴더는 미리 만든다. k6 v2.3.0 은 폴더를 만들지 않는다: 없으면 `failed to handle the end-of-test summary … could not open` 만 남기고 종료 코드는 그대로 끝난다. 기본값은 `scripts/load/out/k6-summary-<RUN_ID>.json`.
- `RUN_ID` 는 1~40자 `A-Z a-z 0-9 _ . -`, 실행마다 바꾼다(예: `a1r1`, `g1rho07`). 파일명과 Idempotency-Key 에 들어가 부하 작업을 `lt-` 로 찾고 지울 수 있다. 결과 폴더 이름에도 들어간다.

## 로컬 smoke (동작 확인용 — 결과로 쓰지 않는다)

jar(mock)를 포트 18080 에 띄우고 VU 5개로 A0 를 돈다. 리포 밖 빈 폴더에서 띄우면 `storage/` 가 그 폴더에 생긴다. mock 이 내주는 GLB 는 생성한 1.5 MB 합성 GLB 로 바꾼다(저장소의 `sample-dog.glb` 는 0 B).

```powershell
cd backend; .\gradlew.bat bootJar; cd ..
# 다른 창, 빈 폴더에서 (B1 이후 관리 포트가 8081 이므로 겹치면 MANAGEMENT_SERVER_PORT=18081)
java -jar <리포>\backend\build\libs\mock-backend-0.0.1-SNAPSHOT.jar --server.port=18080 --spring.profiles.active=mock `
     --storage.result.sample=<리포>\scripts\load\out\fixtures\glb-1.5mb.glb
# 리포 루트에서
k6 run -e BASE_URL=http://localhost:18080 -e RUN_ID=local1 -e SMOKE_VUS=5 -e MIN_ASSET_BYTES=1000000 `
       -e SUMMARY_PATH=scripts/load/out/smoke-local1.json scripts/load/smoke.js
```

로컬 mock 기본값(대기 2초 + 처리 3초)이면 흐름 하나가 약 5초다. 기준식이 도는지 보려면 `-e RTT_BASE_MS=<smoke 의 rttBaseMsMeasured>` 를 붙여 `api_staged.js -e USERS=900,1800 -e RAMP_S=10 -e HOLD_S=30` 처럼 짧게 돌린다(λ 0.5·1/s 라 흐름 약 55개에 `FAIL_RATIO` 0.05 의 retry 도 돈다 [파생]. `USERS=25,200` 이면 흐름이 4개 남짓이라 단계 0 에는 흐름이 없을 수 있다). F3 을 로컬 jar 에 돌릴 때는 jar 를 `mock,prod` 로 띄우거나 `-e VERIFY_MAGIC=0` 을 준다: 사진 시그니처 검사(`upload.verify-magic`)는 prod 프로파일에만 있어 mock 만으로는 fake_jpg·gif_as_jpeg 가 202 를 받는다. `scripts/perf` 나 다른 측정이 도는 동안에는 걸지 않는다.

## VM (보고용)

전제: `deploy/docker-compose.loadtest.yml` 덮어쓰기로 부하용 backend 가 떠 있다(절차는 `deploy/loadtest/README.md`). A 시리즈는 `mock,prod` + `pending-ms=0`, `processing-ms=60000`, `@Async` 풀 400(A2 의 PENDING 시드 때만 `MOCK_WORKER_PENDINGMS` 를 길게 둔다, 아래). 1.5 MB 합성 GLB 를 부하용 볼륨에 미리 넣는다: `make_fixtures.py` 의 `glb-1.5mb.glb` 와 deploy/loadtest/README.md 3번의 `/tmp/lt-sample-1500000.glb` 는 같은 파일이다(sha256 `03a0c8f2…`, 어느 쪽으로 만들어도 해시 확인이 같다). 부하는 개발 PC 에서 유선으로 걸고, 시작 전 iperf3/speedtest 업링크와 k6 클라이언트 CPU 를 적어 둔다.

**결과 폴더 규칙**: k6 실행 하나에 폴더 하나, `docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/`. 그 안에 그 실행의 k6 요약(`k6-summary.json`)과 `collect.py` 가 쓰는 `run.json` 이 있고, 그 실행의 VM 쪽 파일(events.jsonl, docker stats, glb.sha256 등 — deploy/loadtest/README.md 5번)은 하위 폴더 `vm/` 에 둔다. VM 파일은 `collect.py --attach` 로만 run.json 에 들어간다. 그래서 `RUN` 은 k6 명령마다 다시 정하고(G1 은 ρ 마다 폴더 하나), `collect.py` 는 폴더마다 한 번 돌린다. 한 폴더에 여러 실행을 쓰면 `k6-summary.json` 과 `run.json` 이 덮어써진다.

아래는 bash 다. 개발 PC 에서는 Git Bash 로 돌린다. Git Bash 는 `-e NAME=/c/…` 값의 맨 앞 경로만 Windows 경로로 바꿔 k6 에 넘기고 쉼표 뒤 경로는 `/c/…` 그대로 두므로, A2 의 절대 경로는 `cygpath -m` 으로 만든다(Linux 에는 `cygpath` 가 없어 `$PWD` 를 그대로 쓴다). 리포 경로에 공백이 있으면 그 경로가 든 값은 따옴표로 감싼다.

```bash
# Prometheus 는 VM 의 127.0.0.1:9090 에만 게시된다 → 다른 터미널에서 터널: ssh -N -L 9090:127.0.0.1:9090 <user@VM>
export BASE_URL=https://<domain> UPLINK_MBPS=<측정값> PROM_URL=http://localhost:9090
# k6 명령마다 먼저 부른다: RUN_ID 와 그 실행의 결과 폴더 RUN 을 새로 정한다(vm/ 은 VM 파일을 가져올 때 scp 가 만든다)
newrun() { RUN_ID=$1; RUN=docs/perf/load/data/$(date +%Y-%m-%d_%H%M)-$1; mkdir -p "$RUN"; echo "$RUN"; }

# A0 — RTT_base
newrun a0r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/smoke.js
export RTT_BASE_MS=<위 요약의 meta.rttBaseMsMeasured>

# A1 (깨질 때까지), A5 스파이크, A6 소크
newrun a1r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js
newrun a5r1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=spike -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js
newrun a6r1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=soak  -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js

# A2 — 같은 부하 볼륨에서 세 번, 이 순서로(poll_saturation.js 머리 주석). mock 은 한 설정으로 COMPLETED 와 PENDING 을 함께
#      만들지 못한다: COMPLETED 시드는 pending-ms 0, PENDING 시드는 긴 pending-ms 가 필요하고, backend 재시작은 PENDING 작업을
#      모두 FAILED 로 닫는다(InterruptedJobRecovery). SEED_ONLY=1 은 시드해 IDS_OUT 만 쓰고 부하는 걸지 않는다(RTT_BASE_MS 불필요).
#  (a) 지금 backend(MOCK_WORKER_PENDINGMS=0)에서 COMPLETED 1,000건
newrun a2seedc; SEEDC=$(cygpath -m "$PWD/$RUN" 2>/dev/null || echo "$PWD/$RUN"); k6 run -e RUN_ID=$RUN_ID -e SEED_ONLY=1 -e SEED_COMPLETED=1000 -e IDS_OUT="$SEEDC/ids.json" \
  -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/poll_saturation.js
#  (b) VM: backend 를 한 번만 긴 pending-ms 로 다시 띄운다(MOCK_WORKER_PENDINGMS=3600000 $L up -d backend). (b) 의 시드와 (c) 의
#      실행(부하 약 33분)을 합한 것보다 길어야 하고, (c) 의 setup 로그가 필요한 최솟값을 적는다. A2 가 끝날 때까지 다시 띄우지 않는다.
newrun a2seedp; SEEDP=$(cygpath -m "$PWD/$RUN" 2>/dev/null || echo "$PWD/$RUN"); k6 run -e RUN_ID=$RUN_ID -e SEED_ONLY=1 -e SEED_PENDING=1000 -e IDS_OUT="$SEEDP/ids.json" \
  -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/poll_saturation.js
#  (c) 곧바로 잰다. IDS_FILE 은 쉼표로 이은 절대 경로들이고 목록을 합친다
newrun a2r1; k6 run -e RUN_ID=$RUN_ID -e IDS_FILE="$SEEDC/ids.json,$SEEDP/ids.json" -e SUMMARY_PATH=$RUN/k6-summary.json \
  scripts/load/poll_saturation.js
#  (d) VM: 기본값으로 되돌린다(MOCK_WORKER_PENDINGMS 없이 $L up -d backend), 예를 들어 A2 뒤 부하 볼륨을 비울 때.
#  기대: (a)·(b) 는 종료 코드 0 과 'SEED ONLY: …' 줄. (c) 가 setup 에서 107 로 멈추면 메시지의 원인(재시작, pending-ms 0 에서 한
#  PENDING 시드, 다른 서버·비운 볼륨)을 고쳐 (b) 부터, 99 와 load_pendingLeft 면 pending-ms 를 늘려 (b) 부터 다시 한다.
#  시드 폴더(ids.json + 시드 전용 k6-summary.json)는 결과가 아니다: collect.py 를 돌리지 않는다.

# A3, A4 — 단계마다 시나리오 하나. drop 이 난 단계만 fail 이고, 느린 단계를 drop 없이 재려면 PRE_VUS 를 올린다(아래 표)
newrun a3s1; k6 run -e RUN_ID=$RUN_ID -e SIZE=small -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/upload.js
newrun a3l1; k6 run -e RUN_ID=$RUN_ID -e SIZE=large -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/upload.js
newrun a4r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/asset.js

# G1 (c=1, T=10 s, ρ 마다 한 번 — 폴더도 ρ 마다 하나), G2 (ρ=0.7, c=1/2/4), G3 (ρ=1.5)
#   real,prod + 스텁: BESIDE_PASSTHROUGH_DELAY_MS=10000, BESIDE_PASSTHROUGH_SLOTS=c, backend inference.max-concurrency=c
#   도착은 푸아송(솎아내기 ARRIVAL_THIN=20, 요약 meta.arrivalProcess). 예측 비교는 아래 "G 시리즈 예측" 참고
for rho in 0.5 0.7 0.9 1.2; do
  newrun g1rho${rho/./}; k6 run -e RUN_ID=$RUN_ID -e RHO=$rho -e SLOTS=1 -e T_SEC=10 -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js
done
#   G2 는 c 마다 스텁 BESIDE_PASSTHROUGH_SLOTS·backend LOADTEST_INFERENCE_MAX_CONCURRENCY 를 c 로 다시 띄우고
#   -e SCENARIO=G2 -e RHO=0.7 -e SLOTS=<c> (SLOTS=1 은 SCENARIO 가 없으면 G1 으로 적힌다). G3 는 -e RHO=1.5 (G3 로 적힌다).

# F1b 타임아웃 — 스텁: BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1 BESIDE_PASSTHROUGH_SLOW_TOKEN=slow
#   BESIDE_PASSTHROUGH_SLOW_MS=30000 (BESIDE_PASSTHROUGH_FAIL_TOKEN 은 설정하지 않는다), backend LOADTEST_INFERENCE_TIMEOUT_MS=20000.
#   흐름의 20 % 만 사진 파일명에 slow 가 들어가고(FAIL_TOKEN=slow FAIL_RATIO=0.2), 그 흐름이 FAILED(INFERENCE_TIMEOUT)로
#   끝나면 k6 는 의도한 결과로 센다. RUN_ID 에는 slow 가 들어가면 안 된다(들어가면 모든 작업이 느려지므로 시작을 거부한다).
#   RHO=0.3: slow 흐름은 retry 까지 두 번 30초씩 슬롯을 잡아 스텁 이용률이 0.6 이 된다. 0.5 면 1.0 이 되어 타임아웃이 아니라
#   과부하로 실패가 이어진다(계산은 deploy/loadtest/README.md 의 F1b).
newrun f1b1; k6 run -e RUN_ID=$RUN_ID -e RHO=0.3 -e SLOTS=1 -e T_SEC=10 -e FAIL_TOKEN=slow -e FAIL_RATIO=0.2 \
  -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js
#   바로 이어서 보통 G1 한 번: 다음 작업이 연쇄로 실패하지 않는지 본다 (slow 이름이 없으므로 스텁 설정은 그대로 둬도 된다)
newrun g1postf1b1; k6 run -e RUN_ID=$RUN_ID -e RHO=0.7 -e SLOTS=1 -e T_SEC=10 -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js

# F1a 생성 실패(스텁 BESIDE_PASSTHROUGH_FAIL_RATE=0.2), F2 GPU 꺼짐(시험 중 스텁을 끄고 켠다, backend
#   LOADTEST_INFERENCE_OFFLINE_WAIT_MS=1800000) — VM·스텁 값은 deploy/loadtest/README.md '시리즈별 값'. k6 쪽은 G1 값 그대로라
#   k6 env 로는 G1 과 구별되지 않으므로 SCENARIO 로 이름을 붙인다
newrun f1a1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=F1a -e RHO=0.7 -e SLOTS=1 -e T_SEC=10 -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js
newrun f2r1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=F2  -e RHO=0.7 -e SLOTS=1 -e T_SEC=10 -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js

# F3 잘못된 업로드 (prod 프로파일: upload.verify-magic=true). 30 MB 는 Caddy(25MB)가 끊는다.
#   느린 업로드(10 kB/s)는 k6 가 요청 하나의 속도를 못 줄이므로 failure/f3_throttled.py 가 k6 의 폴링 중에 올린다: 도우미를 먼저 띄운다
newrun f3r1
python scripts/load/failure/f3_throttled.py --base-url $BASE_URL --run-id $RUN_ID --k6-summary $RUN/k6-summary.json \
    --k6-exit-file $RUN/k6-exit.txt --json-out $RUN/f3-throttled.json & F3T=$!
k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/failure/f3_uploads.js; echo $? > $RUN/k6-exit.txt
wait $F3T; echo "f3_throttled exit $?"
# 기대: 사례마다 match 1·업로드 수 계획대로(기본 20/20), 느린 업로드 202 약 40초(0.4 MB at 10 kB/s)·폴링 구간 안, f3_throttled 종료
#   코드 0. k6-exit.txt 가 있어야 k6 가 시작을 거부했을 때 도우미가 바로 끝난다.
#   VM 에서 곧바로(부하 볼륨을 비우거나 backend 를 다시 띄우기 전에) k6 출력 끝의 'VM:' 줄(= 요약 meta.leftoverCheckVm)을 그대로 돌린다:
#   $OUT/f3-leftovers.txt 에 "f3bad stored 0", "tomcat dirs" 1 이상, "upload_tmp 0" 이어야 한다(줄이 없거나 dirs 0 이면 경로가 틀린 것).

# F4 다운로드 1,000회 중단 (COMPLETED 작업 하나, 1.5 MB GLB)
newrun f4r1; python scripts/load/failure/f4_abort.py --base-url $BASE_URL --job-id <uuid> --n 1000 --concurrency 20 --json-out $RUN/f4.json
```

- F1b 는 `FAIL_TOKEN=slow` 에 `FAIL_RATIO` > 0 이라 요약·글이 스스로 F1b 로 적고, 큐 예측 재계산(`meta.recompute`)은 남기지 않는다(슬롯 시간에 타임아웃이 섞인다). 실효 이용률은 요청한 ρ=0.3 보다 크다: backend 는 20초에 끊지만 스텁은 slow 시도마다 30초를 다 채우며 슬롯을 쥐어, 스텁 이용률이 약 0.6 이다 [파생].
- F1b 는 MODEL_LOCK 문제로 **실패가 예상되며, 실패하면 그대로 기록**한다(§9). 실행 중의 연쇄 실패(slow 가 아닌 작업의 시간 초과)는 k6 로는 retry 뒤에도 FAILED 인 흐름만 보이므로(`load_flowOkRatio`), `vm/events.jsonl` 의 `INFERENCE_TIMEOUT` 수에서 스텁 로그의 `slow token` 줄 수를 빼서 본다(deploy/loadtest/README.md 의 F1b). 뒤따르는 G1 의 `load_flowOkRatio`·`queuedMs` 는 F1b 뒤에 연쇄 실패가 남는지를 보여 준다.
- F1a·F2 는 `-e SCENARIO=` 가 없으면 `meta.scenario` 가 G1 이다. retry 가 하나라도 있으면 재계산은 지워지지만, FAILED 가 하나도 없던 F2(모든 작업이 offline-wait 안에 처리됨)는 이름 없이는 G1 처럼 재계산까지 남는다. F1a 의 판정은 API 5xx 0 과 retry 뒤 성공(§9)이다: `FAIL_RATIO` 가 0 이라 k6 는 실패가 주입된 흐름을 모르므로, 두 번 다 실패한 흐름(약 0.2 × 0.2 = 4 % [파생])이 `load_flowOkRatio >= 0.95` 를 깎아 이 기준은 우연히 통과하거나 실패한다. F1a 의 판정으로 읽지 않는다.

환경 변수는 각 스크립트 머리 주석에 있다. 기본값과 함께:

| 스크립트 | 환경 변수(기본값) |
|---|---|
| 공통(`lib/config.js`) | `BASE_URL`(`http://localhost:18080`), `RUN_ID`(`local`), `RTT_BASE_MS`, `SUMMARY_PATH`, `UPLINK_MBPS`, `TIER`, `FAIL_TOKEN`(`fail`), `POLL_INTERVAL_S`(2), `MAX_WAIT_S`(600), `RETRY_WAIT_S`(3), `MIN_ASSET_BYTES`(0), `HEALTHZ_PER_FLOW`(1), `PHOTOS=a.png,b.png` 또는 `PHOTO_1`/`PHOTO_2`; 가드 `PROM_URL`, `PROM_ENV`(`loadtest`), `GUARD_EVERY_S`(15), `GUARD_BREACHES`(2), `GUARD_MAX_ERROR_RATIO`(0.05), `GUARD_MAX_DISK_RATIO`(0.8), `GUARD_BLIND_ABORT`(3, 0 이면 끔) |
| `smoke.js` | `SMOKE_EVERY_S`(10), `SMOKE_DURATION_S`(180), `SMOKE_VUS`(5), `SMOKE_MAX_VUS`(20), `FAIL_RATIO`(0) |
| `api_staged.js` | `SCENARIO`(`staged`·`spike`·`soak`), `USERS`(A1 단계 일부), `RAMP_S`/`HOLD_S`, `SOAK_HOLD_S`, `CONVERSION_TIER`(T3), `BROWSE`(1; 0 이면 healthz 만 하는 세션 끔), `BROWSE_MAX_VUS`(200), `FAIL_RATIO`(0.05), `EXPECTED_FLOW_S`(75), `PRE_VUS`(최고 λ × `EXPECTED_FLOW_S` × 1.2, 최소 5: A1 450, A5 270, A6 90), `MAX_VUS`(최고 λ × (`MAX_WAIT_S` + 60초), 4,000 이하: A1 3,300, A5 1,980, A6 660), `GRACEFUL_STOP_S`(1,323) |
| `poll_saturation.js` | `RPS`(단계 일부), `HOLD_S`(300), `GAP_S`(30), `IDS_FILE`(절대 경로, 쉼표로 여럿 — 한 id 가 두 목록에 다 있으면 거부), `IDS_OUT`, `SEED_ONLY`(1: 시드하고 `IDS_OUT` 만 쓴다; `IDS_FILE` 과 함께, `IDS_OUT` 없음·상대 경로·없는 폴더·폴더 자체, 시드 0건이면 시작 거부), `SEED_COMPLETED`(1000, `SEED_PENDING` 이 있으면 0), `SEED_PENDING`(0), `SEED_WAIT_S`(900), `PENDING_SHARE`(pending id 가 있으면 0.5; `SEED_ONLY` 없이 `SEED_PENDING` 만 시드하는 부하 실행은 `PENDING_SHARE=1` 일 때만 받는다), `EXPECTED_LATENCY_MS`(100, VU 크기), `MAX_VUS`(단계마다 상한 2,000) |
| `upload.js` | `SIZE`(`small`), `RATES`(/s, 단계 일부), `RAMP_S`/`HOLD_S`, `LARGE_PHOTO`(`./out/fixtures/photo-5mb.png`), `DISK_BUDGET_GB`(8)·`ALLOW_DISK`, `REQUEST_TIMEOUT`(120s), `PRE_VUS`(최고 단계의 미리 만든 VU = 최고 도착률 × create p99 한도: small 3초 → 60, large 10초 → 20; 아래 단계는 도착률에 비례), `MAX_VUS`(모든 단계 합의 상한, 600 과 `meta.vus.peakPreAllocated` 중 큰 값; 그보다 작게 주면 시작 거부) |
| `asset.js` | `RATES`, `RAMP_S`/`HOLD_S`, `ASSET_JOBS`(10), `MIN_ASSET_BYTES`(1000000), `SEED_WAIT_S`(900), `PRE_VUS`(최고 도착률 × 6초 → 240), `MAX_VUS`(800 과 peak 중 큰 값) |
| `gpu_queue.js` | `RHO`(0.7), `SLOTS`(1), `T_SEC`(10), `DURATION_S`(1800), `ARRIVAL_THIN`(20, 1 이면 같은 간격 도착), `FAIL_RATIO`(0)·`FAIL_TOKEN`, `SCENARIO`(G1·G2·G3·F1a·F1b·F2, 대소문자 무관; 없으면 `FAIL_RATIO` > 0 일 때 `slow` 토큰은 F1b·다른 토큰은 F1a, 아니면 ρ ≥ 1.5 G3·`SLOTS` > 1 G2·G1. G 에 `FAIL_RATIO` > 0, `FAIL_RATIO` 없는 F1b, 모르는 이름은 시작 거부), `MAX_VUS`(1 이상 정수; 미리 만드는 VU 도 이 값으로 자른다) |
| `failure/f3_uploads.js` | `F3_VUS`(2), `F3_REPEATS`(VU 당 라운드 10), `POLL_RPS`(20), `F3_BG_S`(180), `GRACEFUL_STOP_S`(180), `VERIFY_MAGIC`(1), `PROXY_LIMIT_BYTES`(https 면 25000000, 아니면 0), `CASES`(사례 일부) |

### G 시리즈 예측

- k6 의 arrival-rate 실행기는 반복을 같은 간격으로 시작한다. 그대로면 큐가 D/D/c 라 ρ < 1 에서 대기가 거의 0 이 되고, 푸아송 도착을 가정한 M/D/1·M/M/1 예측과 비교할 수 없다. 그래서 `gpu_queue.js` 는 λ·K 의 반복을 걸고 반복마다 1/K 확률로만 흐름을 시작한다(솎아내기, K = `ARRIVAL_THIN`, 기본 20). 흐름 사이 간격은 틱의 기하분포 배수라 K 가 크면 지수분포(푸아송 도착)에 가깝다. 요약 `meta.arrivalProcess` 가 `poisson (thinned x20)` 처럼 적는다.
- `meta.prediction`(그리고 §9 표의 5 / 11.7 / 45초)은 도착률이 요청한 값이고 슬롯 시간이 정확히 `T_SEC` 일 때의 값이다. 실행은 둘 다 다르다:
  - 도착률: 솎아내기라 유한한 실행이 만든 작업 수는 λ × `DURATION_S` 둘레의 푸아송 수다(표준편차 = 그 제곱근). 그래서 요약이 실제로 만든 양을 남긴다: `meta.measuredJobsPerHour` = round(`load_postMs` count × 3600 ÷ `meta.measuredArrivalWindowS`), 창은 `DURATION_S`(일찍 멈춘 실행은 setup 이 끝난 뒤 부하가 돈 초)이고 `meta.measuredCreates` 가 그 count 다.
  - 슬롯 시간: backend 는 작업 하나의 슬롯을 잡은 채 prod 의 `inference.offline-wait-ms` > 0 이면 스텁의 `/healthz` 를 새로 부르고(그 작업의 `queuedMs` 에 들어간다), 사진 업로드·스텁 대기·GLB 내려받기(이 셋만 서버 `processingMs`)를 하고, 끝난 뒤 job_runs/events.jsonl 쓰기와 정리 DELETE 를 한다. 그래서 평균 `processingMs` 는 슬롯보다 조금 짧고(작업마다 스텁 왕복 두 번 + 쓰기 정도), 큐가 비어도 `queuedMs` 에 스텁 왕복 한 번쯤이 남는다.
- **예측은 측정한 두 값으로 다시 계산해서** `measuredQueuedMs` 와 비교한다. 요약의 `meta.recompute` 가 그 명령이다:
  `python scripts/load/model.py --queue-rate-per-h <meta.measuredJobsPerHour> --queue-service-s <meta.measuredProcessingMs.mean / 1000> --queue-slots <SLOTS>` (`--json` 도 된다). 출력: ρ, M/M/c Wq, c = 1 이면 M/D/1 Wq.
- `meta.recompute` 가 null 인 경우: F1a·F1b·F2(retry 가 도착을 늘리고 실패·타임아웃이 슬롯 시간에 섞인다), retry 가 하나라도 있는 G 실행(`meta.measuredRetries` > 0: 이름 없이 돈 F1a·F2 이거나 실패가 났다), 만든 작업이 없거나 `processingMs` 가 없을 때, setup 이 실패했을 때(측정 필드도 null).
- 요약 글은 공통 보고 아래에 `queue <label>: <n> jobs created in <w> s = <r>/h (requested <q>/h), <k> retries; mean slot (server processingMs) <x> ms, mean queuedMs <y> ms` 를 적고, G 실행이면 `  recompute: python scripts/load/model.py …`(만들 수 없으면 `  recompute: - (no job created or no processingMs)`, retry 가 있으면 `  <k> retries: failures were injected or occurred (an F1a/F2 run without SCENARIO?), so no recompute`)를 붙인다. F 실행은 그 한 줄이 `; fault run, so no queue prediction or recompute` 로 끝난다.
- 서버 쪽 성공률 주의: 실패한 실행은 항상 `passthrough="false"` 로 붙는다(backend 는 스텁의 주입 실패와 모델 실패를 구분하지 못한다). 그래서 `passthrough="false"` 로 거른 성공률은 추론 서비스가 실제 모델을 돌릴 때만 맞다. G·F 스텁 실행에서는 거르지 않은 성공률(`beside_jobs_finished_total` 의 result 비율, `collect.py` 의 `jobs_finished_rate` CSV)이나 k6 의 `load_flowOkRatio` 를 쓴다.

## 결과 수집

실행 폴더마다 한 번(위 `newrun` 이 만든 `$RUN`). VM 쪽 파일은 먼저 그 폴더의 `vm/` 에 가져다 둔다(deploy/loadtest/README.md).

```bash
python scripts/load/collect.py $RUN --scenario A1 --tier T3 --summary $RUN/k6-summary.json --k6-version "$(k6 version)" \
  --vm-provider <회사> --vm-type <유형> --vm-vcpu 2 --vm-ram-gb 4 --uplink-mbps $UPLINK_MBPS --client-cpu-max-pct <값> \
  --server-sha <배포 커밋> --photo scripts/load/out/fixtures/photo-400k.png \
  --prom-url $PROM_URL --attach $RUN/vm/events.jsonl --attach $RUN/vm/docker-stats-end.jsonl
# F3 은 --scenario F3 에 도우미 보고와 VM 의 남은 파일 확인을 붙인다: --attach $RUN/f3-throttled.json --attach $RUN/vm/f3-leftovers.txt
```

`collect.py` 는 `run.json written to <run>/run.json`, `status: <run.json 의 status>`, `prometheus: N of 18 CSV written to <run>/prometheus`(실패한 질의는 `; failed: …`, 머리줄만 온 CSV 는 `; no data: …`; `--prom-url` 이 없으면 `prometheus: no --prom-url`)를 출력한다. `no data` 는 `PROM_ENV` 가 서버의 `METRICS_ENV` 와 다르면 전부, 5xx 가 하나도 없으면 `http_5xx_ratio`(빈 합), Windows 서버면 `process_open_files` 에 나온다. 종료 코드는 0 완료, 1 썼지만 질의 일부 실패, 2 run.json 을 쓰지 않음(잘못된 인자, 읽을 수 없는 요약, 모르는 구간)이다.

`docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/` 에 남는 것:

| 파일 | 내용 |
|---|---|
| `k6-summary.json` | handleSummary 출력. `meta`: 스크립트, 시나리오, RUN_ID, 시작·끝, `testRunDurationMs`, 부하 예정·실제 길이 `loadPlannedS`·`loadRanS`, RTT(`rttBaseMsUsed`·`rttBaseMsMeasured`), 단계 `stages`(사용자 수·도착률·`holdS`·`holdEndS`, A2·A3·A4 는 그 단계의 시나리오 `scenario`), 스크립트별 항목 — A1·A5·A6 `vus`(`preAllocated`, `max`), A3·A4 `vus`(`preAllocated`, `max`, `byLevel`, `peakPreAllocated`, `maxByLevel`), A3 `expectedUploads`·`expectedUploadGB`, A2 `mode`(`load`·`seed-only`)·`rps`·`ids`(IDS_FILE: `file`·`files`·`completed`·`pending`·`checkedInSetup`·`pendingAgeS`·`endCheck`; 시드: `seeded`·`pending`·`idsOut`·`endCheck`), G `arrivalProcess`·`prediction`·`predictionNote`·`measuredQueuedMs`·`measuredProcessingMs`·`measuredCreates`·`measuredRetries`·`measuredArrivalWindowS`·`measuredJobsPerHour`·`recompute`·`preAllocatedVUs`·`maxVUs`, F3 `cases[]`(`expectedStatus`·`expectedCode`·`matchRate`·`checks`·`expectedChecks`·`photoPrefix`)·`resets`·`pollJob`·`photoNames`·`leftoverCheckVm`·`gracefulStopS`. `verdict`: `thresholdsOk`, 끝난 방식 `aborted`(정상이면 null, 위 "실행이 어떻게 끝났는지"), `unfinishedFlows`, 실패 기준, 단계별 판정 `stages[]`(`status`·`holdS`·`heldS`·`dropped`(A2·A3·A4)·`latency`·`sentMbps`·`assetMbps`), 깨지는 지점 `breakingPoint`·`breakingPointNote`, 가드 `guard`, `httpReqs`, `dataSentMbps`·`peakStageSentMbps`·`uplinkRatio`·`uplinkRatioWholeRun`, `droppedIterations`, `checksFailed`, `unexpectedCount`, `vusMax`. `k6`: 원본 지표와 `setup_data`(setup 이 끝난 시각 `readyAtMs` 포함). 중단된 실행은 `thresholdsOk` false 다 |
| `run.json` | `status`(인용 여부를 적은 글), `resultCandidate`(true 면 [실험 결과] 후보), `outcome`(`ended`: `normal` · `ruleStop`(abortOnFail 오류 기준이나 가드의 5xx 기준이 멈춤) · `aborted`(그 밖의 중단) · `unknown`(요약에 `verdict.aborted` 가 없음) · null(`--summary` 없음, 예 F4), `abortedBy`, `reason`, `guardFailures`, `guardLastError`), 조건: VM 사양, 도구 커밋(`git rev-parse HEAD`, scripts/load 미커밋 변경 여부), 서버 커밋, k6 버전, 업링크, 클라이언트 CPU, 사진 크기·SHA-256, 티어, 시나리오, 구간, k6 판정 사본, Prometheus 질의 목록 |
| `prometheus/*.csv` | `unix,time,series,value`, 18개: http p95/p99(uri별), RPS(uri·status별), 5xx 비율, `beside_jobs_queue`(state별), 작업 종료율, 에셋 다운로드(outcome별), process/system CPU, 힙 사용·최대, GC 뒤 힙 비율(`jvm_heap_after_gc`, 0~1), GC, Tomcat 바쁜 스레드, Hikari 대기, 열린 파일, 디스크 여유 |
| `vm/` | 그 실행의 VM 쪽 원본(events.jsonl, `docker stats`, glb.sha256, F3 의 f3-leftovers.txt 등). run.json 에는 `--attach` 로 준 것만 들어간다 |
| `attachments/` | `--attach` 로 준 파일의 사본(F3 의 f3-throttled.json, F4 보고 등) |

`docs/perf/notes/` 가 아니라 여기에 둔다(`scripts/perf/report.py` 가 notes/*.md 를 루프백 보고서에 넣기 때문).

## 정직성 규칙 (§10)

- 루프백·로컬 결과는 버전 비교나 동작 확인에만 쓴다. 개발 PC 에서 k6 v2.3.0 으로 돌린 실행(루프백, mock jar, 대역 스텁·대역 Prometheus, 짧고 줄인 단계)은 모두 동작 확인이고 [실험 결과] 가 아니다.
- `collect.py` 는 run.json 에 `outcome.ended`(normal | ruleStop | aborted | unknown, `--summary` 가 없으면 null)와 `resultCandidate` 를 적는다. `localhost` 대상, 용량과 무관하게 중단된 실행(setup·디스크·Prometheus 를 못 읽음·Ctrl+C/REST 정지·드레인에서 잘린 흐름), `verdict.aborted` 가 없는 요약은 `resultCandidate` false 이고 `status` 가 "인용하지 않는다"로 시작한다(로컬은 "동작 확인용 로컬 실행 — 보고서에 인용하지 않는다").
- abortOnFail 오류 기준·가드의 5xx 기준으로 멈춘 실행(ruleStop)은 후보로 남지만 `status` 는 멈춘 규칙만 적는다. 그 시각에 서버 재시작·재배포·네트워크 끊김이 없었음을 VM 쪽 기록(`vm/backend.log`)과 k6 출력으로 확인한 뒤에만 `breakingPoint` 를 용량 한계로 쓴다.
- 가드가 Prometheus 를 못 읽었으면(`verdict.guard.failures` > 0) 후보로 남지만 `status` 에 "주의: 가드가 Prometheus 응답 N개를 읽지 못했다" 가 붙는다: 그동안 중단 조건을 지켜보지 못했고 CSV 에 빈 구간이 있을 수 있다.
- A2 의 시드 실행(`SEED_ONLY`)은 결과가 아니다: 기준이 없어 "thresholds: all passed" 로 끝나지만 `collect.py` 를 돌리지 않는다.
- mock 결과는 "생성을 뺀 API 처리량", 스텁 결과는 "큐 동작 검증(생성 시간은 가정 T)"이다.
- k6 VU 수는 동시 사용자 수가 아니다. 사용자 수는 도착률로 환산해 적는다: λ = U / 1,800 흐름/s(T3 비율), 그리고 healthz 만 하는 세션 U / 450 /s. 이 환산은 모든 단계에 T3 비율을 쓰므로 "동시 25(T2)" 단계의 λ 는 0.014/s 로, T2 자체 가정의 0.033/s 보다 작다(`CONVERSION_TIER=T2` 로 바꿀 수 있다).
- "API 계층이 T3 를 통과했다"는 T3(1,800) 단계 유지 구간 통과, `dropped_iterations` 0, 기준 충족, 클라이언트 CPU < 70 %, 업링크 비포화(`uplinkRatio` ≤ 0.7, 가장 바쁜 단계 기준)를 모두 만족할 때만 쓴다. 못 넘으면 "최대 지속 처리량 Y RPS(T3 의 k %)"로 쓴다.
- G 시리즈의 예측은 측정한 도착률과 평균 슬롯 시간으로 다시 계산한 값(`meta.recompute`)과 비교하고, 도착 방식(`meta.arrivalProcess`)을 함께 적는다.
- 결과는 [실험 결과] 로 적고 조건(VM 사양, 커밋 SHA, k6 버전, 업링크, 사진 크기)을 붙인다 — run.json 의 해당 필드.

## 디스크와 VU

- 업로드는 서버 디스크에 남는다. 기본 단계의 예상 업로드량: A1 약 6.7 GB(작업 약 8,300건 × 0.8 MB), A3 small 약 6.1 GB, A3 large 약 7.2 GB. `upload.js` 는 예상량이 `DISK_BUDGET_GB`(8)를 넘으면 시작을 거부하고, 요약 `meta.expectedUploadGB` 에 적는다. 시험이 끝나면 부하용 볼륨을 비운다(deploy/loadtest 절차).
- A1 은 최대 단계(λ = 5/s)에서 흐름이 600초까지 길어지면 동시에 도는 흐름이 약 3,300개다: `MAX_VUS` 기본값 = 최고 λ × (`MAX_WAIT_S` + 60초) = 3,300(4,000 을 넘지 않게 자른다). 미리 만드는 VU 는 `PRE_VUS` 기본 450(최고 λ × 75초 × 1.2)이라, 최고 단계에서 흐름이 평균 약 90초를 넘으면 k6 가 VU 를 더 시작하며 drop 이 생기고 요약은 그 실행의 `pass` 를 모두 `invalid` 로 바꾼다 [파생]. 과부하 단계까지 drop 없이 보려면 `PRE_VUS` 를 올린다.
- A3·A4 는 최고 단계의 `PRE_VUS`(A3 small 60, A3 large 20, A4 240)를 아래 단계에 도착률 비례로 나누고, 단계마다 드레인(A3 `REQUEST_TIMEOUT` + 60초, A4 120초) 동안 VU 를 쥐고 있어 겹치는 단계의 VU 를 k6 가 함께 처음에 만든다: `meta.vus.peakPreAllocated` 는 기본값에서 A3 small 135, A3 large 50, A4 420 이다. `MAX_VUS` 는 모든 단계 합의 상한을 단계마다 나눈 값이다(`meta.vus.maxByLevel`, 기본값에서 최고 단계 A3 small 266, A3 large 240, A4 457). p99 한도보다 느린 단계를 drop 없이 재려면 `PRE_VUS` = 최고 도착률 × 필요한 초(요청 타임아웃 이하)로 준다.
- A2 는 단계마다 미리 만드는 VU = max(20, 초당 요청 × `EXPECTED_LATENCY_MS` × 2), 최대 그 4배(최소 50)이고 둘 다 `MAX_VUS`(2,000) 이하다: 1,800 RPS 단계는 360 / 1,440.
- 클라이언트 메모리: 개발 PC 의 k6 v2.3.0 에서 초기화한 VU 하나가 약 1.1 MB 였다(`api_staged.js` 를 `PRE_VUS` = `MAX_VUS` = 20 과 300 으로 돌린 k6 의 최대 working set 83.9 MB·393.2 MB 의 차이 ÷ 280; init 에서 연 0.4 MB 사진은 들어 있고 흐름이 만드는 0.8 MB 본문은 따로다 — 로컬 동작 확인). 이 값으로 계산하면 [파생]: A1 은 VU 3,300개에 약 3.6 GB 와 흐름을 돈 VU 마다 본문 0.8 MB, A4 는 처음에 만드는 420 VU 에 약 0.46 GB 다. A3 large 는 VU 마다 init 의 `open(p, 'b')` 로 5 MB 사진 사본을 쥐고 첫 업로드에서 20 MB multipart 본문을 만들어(`lazyBodies`), 업로드한 VU 하나가 약 25 MB 다: 기본 peak 50 VU 가 모두 업로드하면 최악 약 1.25 GB(측정하지 않음). 실행 전에 부하 PC 의 남은 메모리를 본다.
- `gpu_queue.js` 의 VU: 진행 중 흐름이 대략 푸아송(m), m = λ × 예상 흐름 시간 × 1.5 이므로 m + 3√m, 그리고 솎아낸 반복(바로 끝남) 몫으로 2개를 더 둔다. `MAX_VUS` 를 주면 미리 만드는 VU 도 그 값으로 자르고(`meta.preAllocatedVUs`·`maxVUs`), 모자라서 drop 이 나면 `dropped_iterations count==0` 이 실패한다.

## 테스트

```powershell
python -I -m unittest discover -s scripts/load -p "test_*.py"   # model(--queue-* 포함), collect, f3_throttled, f4_abort, make_fixtures
node --test "scripts/load/*.test.mjs"                             # k6 스크립트(대역 모듈 + 가짜 API 서버)
```

`node --test` 는 `testing/k6/` 의 대역으로 `k6`, `k6/http`, `k6/metrics`, `k6/execution`, `k6/data` 를 바꾸고 `testing/harness.mjs` 의 가짜 서버(Spring 의 업로드 검사 순서와 크기 상한을 흉내 냄)에 흐름을 돌린다. 이것은 스크립트의 논리 검사이고 k6 실행이 아니다. 대역은 실행기(executor)의 반복 시작 시각·VU 할당·drop, 하위 지표 집계와 기준 평가를 흉내 내지 않는다. 요약 판정 시험은 손으로 만든 summary 객체를 쓴다. 그 부분은 아래처럼 실제 k6 v2.3.0 으로 따로 확인했다.

### k6 v2.3.0 에서 확인한 것 (개발 PC 로컬 — 동작 확인이며 측정이 아니다)

개발 PC(Windows 11)의 k6 v2.3.0 으로 루프백에 짧고 줄인 실행을 돌려 확인했다(A 는 mock jar, G·F 일부는 real,prod jar 와 패스스루 스텁, 가드·`collect.py` 는 대역 Prometheus). 아래 수치는 스크립트가 맞게 도는지 보는 데만 쓴다.

- **끝과 요약**: `handleSummary` 는 정상 끝(종료 코드 0), setup 예외(107), `exec.test.abort()`(108), `abortOnFail`(99; `delayAbortEval: '1m'` 이라 약 62초에 멈췄다), Ctrl+C(105; Python 이 보낸 CTRL_BREAK 로 대신했다), REST API 정지(103) 모두에서 불린다. `data.state` 는 `testRunDurationMs`·`isStdOutTTY`·`isStdErrTTY` 뿐이고, setup 이 예외로 끝나면 `data.setup_data` 가 없다. `handleSummary` 안의 `options` 는 host 객체 사본이라 목록이 JS 배열이 아니고(`forEach` 없음) `data.options` 에는 기준이 없다: 그래서 스크립트가 기준을 넘기고 `lib/thresholds.js` 가 JSON 으로 복사해 읽는다. `exec.test.abort()` 바로 앞에 더한 Gauge 표본(`load_guardAbort`)은 요약에 닿는다.
- **기준**: 태그 두 개로 좁힌 하위 지표 기준(`http_req_duration{endpoint:poll,stage:3,phase:hold}`)이 평가된다. 기준이 걸린 하위 지표는 표본이 없어도 요약에 모든 값 0·count 0 으로 남고 기준은 통과(ok)로 친다 — 그래서 `lib/summary.js` 가 표본 수로 `not_run` 과 빈 지연을 가린다. Counter 의 `count==0` 도 표본 없이 통과하고 `values.count` 0 이 나온다. 요약 `checks` 는 `values.passes`·`values.fails` 를, 지연 지표는 `summaryTrendStats` 의 `count` 를 가진다.
- **단계 태그**: `exec.scenario.startTime` 으로 계산한 `stage`·`phase`(ramp·hold·drain)가 모든 요청·사용자 지표 표본에 붙고, 단계별 create 수가 도착률의 적분과 맞았다(A1 `timeUnit: '1h'`, A3 `'1m'`). `startTime` 을 준 시나리오(A3·A4 의 `lvl1`…)의 `exec.scenario.startTime` 은 그 시나리오의 시작이다. 시나리오 `tags`(A2 의 단계)도 사용자 지표 표본(`load_unexpectedCount{stage:N,phase:hold}`)에 붙는다.
- **drop 과 VU**: `dropped_iterations` 표본에는 `scenario` 태그만 붙는다(시나리오 `tags` 의 stage 는 없다). `ramping-arrival-rate` 는 미리 만든 VU 가 모자라면 반복을 drop 하고서야 VU 를 하나 더 시작한다. 단계마다 시나리오를 둔 A3·A4 는 겹치는 단계의 미리 만든 VU 를 합쳐 처음에 만든다(`vus_max` = `meta.vus.peakPreAllocated`).
- **솎아내기**(`gpu_queue.js`, 로컬 G1 `T_SEC=2`·150초·흐름 70개): `create` 간격의 변동계수가 0.91(지수분포는 1), `ARRIVAL_THIN=1` 이면 0.01(같은 간격)이었고 drop 은 없었다. VU 마다 `Math.random()` 의 씨앗이 다르다.
- **가드**: per-vu-iterations 반복 한 번이 부하와 같이 끝난다. `exec.instance.vusActive` 는 드레인 중에는 1보다 크고 마지막 흐름이 끝나면 1로 떨어진다(`ramping-arrival-rate`·`constant-arrival-rate`·순차 단계에서 확인; k6 문서가 보장하는 동작은 아니다).
- **HTTP**: `responseType`·`responseCallback`·`http.expectedStatuses`, `res.json('a.b')`, `http.batch` 객체 형식. 본문이 `Content-Length` 보다 짧게 끊긴 응답은 status 0(`unexpected EOF`)이라 오류로 세어진다.
- **파일**: `SharedArray`(인덱스·`length`), `open(path, 'b')` 와 `IDS_FILE` 의 상대 경로는 스크립트 폴더(`scripts/load/`) 기준이고, Windows 절대 경로는 `\`·`/` 둘 다 된다. Windows 에서 `open()` 의 오류 글은 폴더면 `open() can't be used with directories`, 없는 파일이면 `GetFileAttributesEx …` 다. `handleSummary` 가 쓰는 파일(`SUMMARY_PATH`, A2 `IDS_OUT`)은 현재 폴더 기준이고, 없는 폴더는 만들지 않는다(위 "준비").
- **환경**: 셸에서 `export` 한 변수도 `__ENV` 로 들어간다(위 VM 블록). Git Bash 에서 `-e NAME=$PWD/…` 의 `/c/…`·`/tmp/…` 경로는 Windows 경로로 바뀌어 k6 에 간다(값의 맨 앞 경로만: 쉼표로 이은 둘째 경로는 그대로라 위 VM 절은 `cygpath -m` 을 쓴다). 위 실행에서 사용 중단(deprecation) 경고는 없었다.

### VM 에서 확인할 것 [계획]

- Linux 의 k6(두 번째 VM 에서 걸 때): `open()` 의 오류 글(코드는 폴더 오류의 `can't be used with directories` 만 맞춘다)과 경로.
- Caddy·TLS 경로: 원격 `RTT_BASE_MS` 기준, F3 의 30 MB 본문에 프록시가 413 을 주는지(로컬 Spring 은 21·30 MB 를 `Content-Length` 만 보고 바로 413 으로 끊었다), 느린 업로드가 Caddy 를 지나 202 인지, F4 의 끊김이 backend 에 `client_abort` 로 닿는지.
- 실제 Prometheus: 가드의 디스크 기준과 `env="loadtest"`·`PROM_ENV` 레이블, 서버가 죽은 뒤의 staleness(대역 Prometheus 는 마지막 값을 계속 답했다), `process_files_open_files`(Micrometer 가 Unix 에서만 내므로 Windows 에서는 없었다).
- 컨테이너 경로: F3 남은 파일 확인 명령의 `/app/storage/uploads`·`/tmp/tomcat.8080.*`(Dockerfile·설정에서 추론했고 컨테이너에서 돌리지 않았다).
- 전체 길이와 기본 VU: A1 의 3,300 VU 까지의 클라이언트 메모리·CPU, 1,323초 드레인, 업링크 포화, 전체 길이 G1 의 도착 간격(`k6 run --out json=<파일>` 로 `endpoint=create` 요청 시각을 본다)과 `dropped_iterations`.

## 트래픽 모델 메모

- `python scripts/load/model.py` 의 숫자는 §3·§5 표와 같다(`test_model.py` 가 문서의 숫자를 그대로 확인한다): T3 동시 1,800, λ 1.0/s, 스파이크 3.0/s, RPS(T=60) 37 / 포화 307 / 스파이크 포화 921(요약의 "약 920"), 에셋 11.4 Mbps, 업로드 6.4 Mbps, 디스크 69 GB/일, T3 GPU 43/86/172/429, T2 GPU 2/3/6/15, T2 1대 최대 T 21초, Q_max(c=1, T=60) 9.
- 전체 RPS(§4) = 세션 시작마다 healthz 1 + POST λ + 에셋 λ(1−f) + retry λf + 폴링 L/2, L = λ(Wq + T), Wq 는 M/M/c(Erlang C, c 는 ρ ≤ 0.7 이 되는 최소 대수). retry 뒤 두 번째 대기는 넣지 않는다(f = 0.05 에서 5 % 미만).
- T1·T2 는 공통 가정(DAU/MAU 0.15, DAU당 0.2건)이 아니라 tiers.json 의 티어별 값으로 계산한다(§2): T2 = DAU/MAU 0.2, DAU당 0.5건(→ DAU 2,000, 120건/h), T1 = 팀원 20명, 피크 비율 0.5, 세션 30분, DAU당 1건(→ 동시 5, 10건/h).
- 디스크(§7): T2 는 하루 2.3 GB 라 사용 가능 약 20 GB [가정] 기준 약 9일 (30 GB 전부면 13일). `model.py` 의 "30 GB 가 차는 날수"(13.0)는 tiers.json 의 `diskBudgetGB` 30 을 통째로 나눈 값이다. 실제로 쓸 수 있는 공간은 VM 의 `df` 로 확인한다.
