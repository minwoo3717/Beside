# scripts/load — 목표 트래픽 모델과 k6 부하·장애 시험

쏘카 수준 목표 트래픽의 계산·시험 도구다. 가정·목표 등급·공식·합격 기준·시험 구성의 근거는 [docs/perf/load/TRAFFIC_MODEL.md](../../docs/perf/load/TRAFFIC_MODEL.md) 한 곳이고, 이 README 의 "§N" 은 모두 그 문서의 절이다(§2 가정, §3 목표 등급, §4 공식, §7 디스크, §8 합격 기준, §9 시험 구성, §10 정직성 규칙, §11 이름). 트래픽 모델(`model.py`)이 T1/T2/T3 의 요청률과 GPU 대수를 계산하고, k6 스크립트가 그 요청률을 배포 VM 에 건다. 결과는 k6 실행 하나마다 폴더 하나(`docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/`)에 모으고, `collect.py` 가 그 폴더에 조건과 함께 `run.json` 을 쓴다.

**상태**
- 스크립트: **[구현됨, k6 미실행 — 이 PC 에 k6 없음]**. 단위 테스트(Python 56개, Node 30개)와 `node --check` 문법 검사만 통과했다. k6 의 실제 동작(아래 "k6 에 기대는 부분")은 첫 로컬 smoke 에서 확인해야 한다.
- 트래픽 모델: **[파생]** — `tiers.json` 의 **[가정]** 으로 계산한 값이다. 측정값이 아니다.
- 측정(A0~A6, G1~G3, F1~F5): **[계획]**. VM·k6·Docker 실행은 아직 없다.

## 파일

| 파일 | 내용 |
|---|---|
| `tiers.json` | [가정] 한 곳(§2): 티어별 MAU, DAU/MAU, 피크 비율, 세션 분, DAU당 생성, 스파이크, 사진·GLB 크기, 실패율, 폴링 2초, 앱 최대 대기 600초, 예시 T(30/60/120/300초), 목표 ρ 0.7, 그리고 k6 단계 목록(`load`) |
| `model.py` | tiers.json → 파생 표(DAU, 피크 동시 사용자, λ, RPS(GPU 충분/포화/스파이크), 에셋·업로드 Mbps, 디스크 GB/일, T별 필요 GPU, 1대 용량, Q_max, G1 예측). Markdown 출력, `--json` 은 JSON. `--queue-rate-per-h λ --queue-service-s S --queue-slots c` 는 G 시리즈 예측(ρ, M/M/c Wq, c = 1 이면 M/D/1 Wq)을 측정한 평균 슬롯 시간으로 다시 계산한다 |
| `smoke.js` | **A0** 연기 시험: 10초에 흐름 1개, 3분. healthz p50 = **RTT_base** 를 요약에 남긴다 |
| `api_staged.js` | **A1** 단계형 혼합 흐름(동시 25→200→1,000→1,800→3,600→5,400→7,200→9,000, 단계마다 증가 1분 + 유지 8분). `SCENARIO=spike` 는 **A5**, `SCENARIO=soak` 는 **A6** |
| `poll_saturation.js` | **A2** 폴링 포화: GET /jobs/{id} 100→300→600→900→1,200→1,800 RPS, 각 5분 |
| `upload.js` | **A3** 업로드: `SIZE=small`(0.8 MB, 1→20/s), `SIZE=large`(약 20 MB, 0.5→2/s) |
| `asset.js` | **A4** 에셋 송신 상한: 1→40 다운로드/s |
| `gpu_queue.js` | **G1~G3**, **F1b** GPU 큐: λ = RHO·SLOTS/T_SEC 의 푸아송 도착(솎아내기 `ARRIVAL_THIN`)으로 흐름을 건다(스텁 패스스루). 요약에 측정한 슬롯 시간으로 예측을 다시 계산하는 명령(`meta.recompute`)을 남긴다 |
| `failure/f3_uploads.js` | **F3** 잘못된 업로드 9종의 상태·`error.code` 일치, 그동안 폴링 p95 |
| `failure/f4_abort.py` | **F4** 에셋 다운로드 N회를 본문 중간에 끊고, 작업 COMPLETED 유지와 다시 받은 파일의 SHA-256 일치를 확인(표준 라이브러리) |
| `collect.py` | 결과 수집: 실행 폴더 하나에 `run.json`(조건·판정) + Prometheus query_range CSV 17개 + 첨부 복사 |
| `make_fixtures.py` | 입력 파일: `photo-400k.png`(0.40 MB), `photo-5mb.png`(4.99 MB), `glb-1.5mb.glb`(1,500,000 B) → `scripts/load/out/fixtures/`(커밋 안 함). GLB 는 부하 시험의 유일한 합성 GLB 다: `scripts/perf/fixtures.py` 의 `glb_synthetic(1_500_000, seed=14)` 와 같은 바이트, sha256 `03a0c8f2b05aee204237cee07ddb41c42173014b33ef0007a43c7b83e2fc0ee9`(deploy/loadtest/README.md 3번의 `/tmp/lt-sample-1500000.glb` 와 같다) |
| `lib/` | k6 공용: `config`(환경·사용자→도착률), `stages`(경과 시간으로 단계 태그), `multipart`, `bodies`(사진은 VU 마다 한 번만 열기), `flow`(사용자 흐름), `classify`, `thresholds`(§8 기준), `summary`(handleSummary), `seed`, `guard`(Prometheus 중단 감시), `queue`, `metrics` |
| `testing/`, `*.test.mjs` | Node 테스트: k6 모듈을 대역으로 바꿔 각 스크립트의 init(단계·기준)과 흐름을 가짜 API 서버로 돌린다 |

## 사용자 흐름과 태그

`lib/flow.js` 는 Unity `JobManager` 와 같은 순서로 공개 경로만 쓴다(Basic 인증 경로는 부르지 않는다).

1. `GET /api/v1/healthz`
2. `POST /api/v1/jobs` — 사진 2장(multipart `photos` × 2), 파일명 `lt-<RUN_ID>-1.png`, 헤더 `Idempotency-Key: lt-<RUN_ID>-<uuid>`
3. `GET /api/v1/jobs/{id}` 를 응답 뒤 2초마다, COMPLETED/FAILED 까지. 600초가 지나면 앱처럼 포기(`load_flowTimeoutRatio`)
4. COMPLETED → 에셋 한 번(`Content-Length` = `asset.bytes` 확인). FAILED → 3초 뒤 `POST /retry` 한 번, 다시 3
5. `FAIL_RATIO`(A1 기본 0.05) 만큼의 흐름은 파일명에 `FAIL_TOKEN`(`fail`)을 넣는다. mock 워커와 스텁(`BESIDE_PASSTHROUGH_FAIL_TOKEN`)이 그 작업을 실패시키므로 retry 경로가 실제로 돈다. 그래서 `RUN_ID` 에 `FAIL_TOKEN` 이 들어가면 시작을 거부한다. F1b 는 `FAIL_TOKEN=slow` 로 같은 장치를 스텁의 `BESIDE_PASSTHROUGH_SLOW_TOKEN` 에 쓴다(아래 VM 절)

모든 요청에 `endpoint=healthz|create|poll|asset|retry`, 고정 `name`(작업 ID 없음), 단계 태그 `stage=<단계 번호>`·`phase=ramp|hold|drain` 이 붙는다. 단계 태그는 원격 jslib 없이 `exec.scenario.startTime` 부터의 경과 시간으로 계산한다(`lib/stages.js`).

k6 값 이름은 `load_` 접두사다(§11 의 `load.*`, k6 는 점을 못 쓴다): `load_postMs`, `load_pollMs`, `load_assetMs`, `load_assetTtfbMs`, `load_healthzMs`, `load_retryMs`, `load_flowMs`, `load_waitMs`, `load_serverQueuedMs`·`load_serverProcessingMs`(서버 `timings.queuedMs`·`processingMs`), `load_errorRatio`, `load_flowTimeoutRatio`, `load_flowOkRatio`, `load_unexpectedCount`, `load_rejectedCount`, `load_assetBytes`, `load_bytesSent`(POST 본문 바이트, 단계별 업링크용), F3 의 `load_f3MatchRatio`. `bench.*` 처럼 docs/perf 전용이며 METRICS.md 에는 올리지 않는다(안건 #15).

## 합격 기준 (§8, [제안값 — 계약 회의 전])

`RTT_BASE_MS` 는 같은 대상에 돌린 A0 의 healthz p50(`meta.rttBaseMsMeasured`)이다. 원격 `BASE_URL` 에서 이 값 없이 A1~A4·G·F3 을 돌리면 시작을 거부한다(0 ms 로 판정하지 않게).

| 엔드포인트 태그 | p95 | p99 |
|---|---|---|
| `healthz` | RTT + 50 ms | RTT + 150 ms |
| `poll` | RTT + 100 ms | RTT + 300 ms |
| `create` (0.8 MB) | 1,500 ms | 3,000 ms |
| `asset` | 첫 바이트(`http_req_waiting`) RTT + 150 ms, 전체 3,000 ms | 전체 6,000 ms |

- `load_errorRatio`(5xx + 연결 오류) < 0.5 %. 429 `RATE_LIMITED`·503 `QUEUE_FULL`([계획] R1~R3, 아직 계약에 없음)은 `load_rejectedCount` 로 따로 센다.
- `load_flowTimeoutRatio == 0`, `load_flowOkRatio >= 0.95`, `dropped_iterations == 0`.
- **A2** 는 `load_unexpectedCount == 0` 도 전체와 단계마다 건다. 폴링이 404 같은 기대하지 않은 상태를 받으면 오류(5xx)가 아니라 'unexpected' 로만 세어지고, 빠른 404 는 지연 기준도 통과하기 때문이다. `IDS_FILE` 을 주면 setup 이 그 안의 id 약 20개를 GET 해 하나라도 200 이 아니면 시작하지 않는다(다른 서버·비운 볼륨의 id). 요약 글은 실패한 check 수와 `load_unexpectedCount` 가 0 이 아니면 출력한다.
- 단계가 있는 스크립트는 같은 기준을 `{stage:N,phase:hold}` 로 단계마다 한 번 더 건다. 요약의 `verdict.stages`(단계마다 `pass | fail | not_run | invalid`)와 `verdict.breakingPoint`(마지막 통과 단계, 처음 실패 단계, 운영 상한 = 마지막 통과 사용자 수의 70 %)가 여기서 나온다.
- **drop 이 있는 단계는 통과가 아니다.** A2 는 단계마다 시나리오(`rps0`…)가 따로라 `dropped_iterations{scenario:rpsN}` 이 그 단계를 `fail` 로 만든다. `ramping-arrival-rate` 스크립트(A1·A3·A4·A5·A6)는 k6 가 drop 에 단계 태그를 붙이지 못하므로, drop 이 하나라도 있으면 `pass` 단계를 `invalid` 로 바꾸고 `breakingPoint` 를 null 로 둔다(이유는 `verdict.breakingPointNote`). VU 를 늘려(`PRE_VUS`/`MAX_VUS`) 다시 돌린다.
- **업링크**: `verdict.uplinkRatio` = max(가장 바쁜 단계 유지 구간의 업로드 본문 Mbps, 전체 평균 `data_sent` Mbps) / `UPLINK_MBPS`, 0.7 을 넘으면 `uplinkSaturated`. 단계 값은 `load_bytesSent{stage:N,phase:hold}` × 8 / 유지 초(A1·A3, 단계 항목의 `sentMbps`, `peakStage`·`peakStageSentMbps`)이고, 전체 평균은 `uplinkRatioWholeRun` 으로 따로 남는다. 본문만 세므로 헤더·폴링·TLS 몫이 빠진 하한이다.
- **중단**: `load_errorRatio` 누적 > 5 % 를 첫 1분 뒤부터 `abortOnFail`. k6 기준은 누적값이라 "1분 지속"을 정확히 못 본다. `PROM_URL` 을 주면 `lib/guard.js` 가 15초마다 서버의 최근 1분 5xx 비율(> 5 % 두 번 연속)과 디스크 사용률(> 80 %)을 보고 `exec.test.abort()` 한다.
- VM CPU < 85 %, GC 뒤 힙 < 75 % 는 k6 가 아니라 `collect.py` 의 Prometheus CSV(`process_cpu`, `system_cpu`, `jvm_heap_used/max`)로 판정한다.

## k6 설치 (지금 하지 않는다)

k6 0.52 이상(1.x 포함). 이 PC 에는 아직 없다.

```powershell
winget install k6 --source winget     # 또는: choco install k6
k6 version
```

Linux(두 번째 VM 등)는 k6 문서의 apt 저장소 절차를 따른다. 설치한 버전은 `collect.py --k6-version "$(k6 version)"` 로 run.json 에 남긴다.

## 준비

```powershell
python scripts/load/make_fixtures.py          # 사진·GLB 를 scripts/load/out/fixtures/ 에 만든다 (GLB sha256 03a0c8f2…)
python scripts/load/model.py                  # 목표 표 확인 (--json 으로 JSON)
```

- 명령은 리포 루트에서 실행한다. k6 는 `open()` 의 상대 경로를 스크립트 위치 기준으로 읽으므로 기본 사진 경로(`./out/fixtures/...`)는 어디서 실행해도 `scripts/load/out/fixtures/` 를 가리킨다. 다른 사진과 `IDS_FILE` 은 절대 경로로 준다(`PHOTOS=a.png,b.png` 또는 `PHOTO_1`/`PHOTO_2`).
- `SUMMARY_PATH` 의 폴더는 미리 만든다(k6 가 폴더를 만들어 주는지에 기대지 않는다). 기본값은 `scripts/load/out/k6-summary-<RUN_ID>.json`.
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

로컬 mock 기본값(대기 2초 + 처리 3초)이면 흐름 하나가 약 5초다. 기준식이 도는지 보려면 `-e RTT_BASE_MS=<smoke 의 rttBaseMsMeasured>` 를 붙여 `api_staged.js -e USERS=25,200 -e RAMP_S=10 -e HOLD_S=30` 처럼 짧게 돌린다. `scripts/perf` 나 다른 측정이 도는 동안에는 걸지 않는다.

## VM (보고용)

전제: `deploy/docker-compose.loadtest.yml` 덮어쓰기로 부하용 backend 가 떠 있다(절차는 `deploy/loadtest/README.md`). A 시리즈는 `mock,prod` + `pending-ms=0`, `processing-ms=60000`, `@Async` 풀 400. 1.5 MB 합성 GLB 를 부하용 볼륨에 미리 넣는다: `make_fixtures.py` 의 `glb-1.5mb.glb` 와 deploy/loadtest/README.md 3번의 `/tmp/lt-sample-1500000.glb` 는 같은 파일이다(sha256 `03a0c8f2…`, 어느 쪽으로 만들어도 해시 확인이 같다). 부하는 개발 PC 에서 유선으로 걸고, 시작 전 iperf3/speedtest 업링크와 k6 클라이언트 CPU 를 적어 둔다.

**결과 폴더 규칙**: k6 실행 하나에 폴더 하나, `docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/`. 그 안에 그 실행의 k6 요약(`k6-summary.json`)과 `collect.py` 가 쓰는 `run.json` 이 있고, 그 실행의 VM 쪽 파일(events.jsonl 의 `lt-` 줄, docker stats, glb.sha256 등)은 하위 폴더 `vm/` 에 둔다. VM 파일은 `collect.py --attach` 로만 run.json 에 들어간다. 그래서 `RUN` 은 k6 명령마다 다시 정하고(G1 은 ρ 마다 폴더 하나), `collect.py` 는 폴더마다 한 번 돌린다. 한 폴더에 여러 실행을 쓰면 `k6-summary.json` 과 `run.json` 이 덮어써진다.

```bash
# Prometheus 는 VM 의 127.0.0.1:9090 에만 게시된다 → 다른 터미널에서 터널: ssh -N -L 9090:127.0.0.1:9090 <user@VM>
export BASE_URL=https://<domain> UPLINK_MBPS=<측정값> PROM_URL=http://localhost:9090
# k6 명령마다 먼저 부른다: RUN_ID 와 그 실행의 결과 폴더 RUN(+ vm/)을 새로 정한다
newrun() { RUN_ID=$1; RUN=docs/perf/load/data/$(date +%Y-%m-%d_%H%M)-$1; mkdir -p "$RUN/vm"; echo "$RUN"; }

# A0 — RTT_base
newrun a0r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/smoke.js
export RTT_BASE_MS=<위 요약의 meta.rttBaseMsMeasured>

# A1 (깨질 때까지), A5 스파이크, A6 소크
newrun a1r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js
newrun a5r1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=spike -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js
newrun a6r1; k6 run -e RUN_ID=$RUN_ID -e SCENARIO=soak  -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/api_staged.js

# A2 — 완료 작업 1,000건을 setup 에서 만들고 ids 를 그 실행 폴더에 남긴다. PENDING 1,000건은 pending-ms 를 길게 둔
#      서버에서 SEED_PENDING=1000 SEED_COMPLETED=0 으로 만들어 IDS_OUT 으로 받은 뒤, 두 파일을 합쳐 IDS_FILE(절대 경로)로 준다.
#      IDS_FILE 의 id 는 setup 에서 약 20개를 확인한다(200 이 아니면 시작하지 않는다).
newrun a2r1; k6 run -e RUN_ID=$RUN_ID -e IDS_OUT=$RUN/ids.json -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/poll_saturation.js

# A3, A4
newrun a3s1; k6 run -e RUN_ID=$RUN_ID -e SIZE=small -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/upload.js
newrun a3l1; k6 run -e RUN_ID=$RUN_ID -e SIZE=large -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/upload.js
newrun a4r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/asset.js

# G1 (c=1, T=10 s, ρ 마다 한 번 — 폴더도 ρ 마다 하나), G2 (ρ=0.7, c=1/2/4), G3 (ρ=1.5)
#   real,prod + 스텁: BESIDE_PASSTHROUGH_DELAY_MS=10000, BESIDE_PASSTHROUGH_SLOTS=c, backend inference.max-concurrency=c
#   도착은 푸아송(솎아내기 ARRIVAL_THIN=20, 요약 meta.arrivalProcess). 예측 비교는 아래 "G 시리즈 예측" 참고
for rho in 0.5 0.7 0.9 1.2; do
  newrun g1rho${rho/./}; k6 run -e RUN_ID=$RUN_ID -e RHO=$rho -e SLOTS=1 -e T_SEC=10 -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/gpu_queue.js
done

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

# F3 잘못된 업로드 (prod 프로파일: upload.verify-magic=true). 30 MB 는 Caddy(25MB)가 끊는다.
newrun f3r1; k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/failure/f3_uploads.js
# F3 느린 업로드(10 kB/s) — k6 는 요청 하나의 속도를 못 줄이므로 curl 로 (같은 f3r1 폴더에 결과를 적는다)
curl -sS --limit-rate 10k -H "Idempotency-Key: lt-f3slow-1" -F "photos=@scripts/load/out/fixtures/photo-400k.png;type=image/png;filename=lt-f3slow-1.png" \
     -o /dev/null -w "%{http_code} %{time_total}s\n" $BASE_URL/api/v1/jobs | tee $RUN/f3-slow-curl.txt

# F4 다운로드 1,000회 중단 (COMPLETED 작업 하나, 1.5 MB GLB)
newrun f4r1; python scripts/load/failure/f4_abort.py --base-url $BASE_URL --job-id <uuid> --n 1000 --concurrency 20 --json-out $RUN/f4.json
```

- F1b 의 실효 이용률은 ρ=0.5 보다 크다. 느린 흐름은 backend 슬롯을 타임아웃(20초)까지 잡고, retry 로 한 번 더 잡는다.
- F1b 는 MODEL_LOCK 문제로 **실패가 예상되며, 실패하면 그대로 기록**한다(§9). 뒤따르는 G1 의 `load_flowOkRatio`·`queuedMs` 가 연쇄 실패 여부를 보여 준다.

환경 변수는 각 스크립트 머리 주석에 있다. 자주 쓰는 것: `USERS`(A1 단계 일부), `RAMP_S`/`HOLD_S`, `RPS`/`RATES`, `FAIL_RATIO`/`FAIL_TOKEN`, `MAX_VUS`, `BROWSE=0`(healthz 만 하는 세션 끄기), `CONVERSION_TIER`, `ARRIVAL_THIN`(G, 기본 20, 1 이면 같은 간격 도착).

### G 시리즈 예측

- k6 의 arrival-rate 실행기는 반복을 같은 간격으로 시작한다. 그대로면 큐가 D/D/c 라 ρ < 1 에서 대기가 거의 0 이 되고, 푸아송 도착을 가정한 M/D/1·M/M/1 예측과 비교할 수 없다. 그래서 `gpu_queue.js` 는 λ·K 의 반복을 걸고 반복마다 1/K 확률로만 흐름을 시작한다(솎아내기, K = `ARRIVAL_THIN`, 기본 20). 흐름 사이 간격은 틱의 기하분포 배수라 K 가 크면 지수분포(푸아송 도착)에 가깝다. 요약 `meta.arrivalProcess` 가 `poisson (thinned x20)` 처럼 적는다.
- `meta.prediction`(그리고 §9 표의 5 / 11.7 / 45초)은 슬롯 시간이 정확히 `T_SEC` 일 때의 값이다. backend 는 작업 하나의 슬롯을 실행 전체(추론 서비스로 업로드, 스텁 대기, GLB 다운로드, 정리) 동안 잡으므로, **예측은 측정한 평균 슬롯 시간으로 다시 계산해서** `measuredQueuedMs` 와 비교한다. 요약의 `meta.recompute` 가 그 명령이다:
  `python scripts/load/model.py --queue-rate-per-h <meta.prediction.jobsPerHour> --queue-service-s <meta.measuredProcessingMs.mean / 1000> --queue-slots <SLOTS>` (`--json` 도 된다). 출력: ρ, M/M/c Wq, c = 1 이면 M/D/1 Wq.
- 서버 쪽 성공률 주의: 실패한 실행은 항상 `passthrough="false"` 로 붙는다(backend 는 스텁의 주입 실패와 모델 실패를 구분하지 못한다). 그래서 `passthrough="false"` 로 거른 성공률은 추론 서비스가 실제 모델을 돌릴 때만 맞다. G·F 스텁 실행에서는 거르지 않은 성공률(`beside_jobs_finished_total` 의 result 비율, `collect.py` 의 `jobs_finished_rate` CSV)이나 k6 의 `load_flowOkRatio` 를 쓴다.

## 결과 수집

실행 폴더마다 한 번(위 `newrun` 이 만든 `$RUN`). VM 쪽 파일은 먼저 그 폴더의 `vm/` 에 가져다 둔다(deploy/loadtest/README.md).

```bash
python scripts/load/collect.py $RUN --scenario A1 --tier T3 --summary $RUN/k6-summary.json --k6-version "$(k6 version)" \
  --vm-provider <회사> --vm-type <유형> --vm-vcpu 2 --vm-ram-gb 4 --uplink-mbps $UPLINK_MBPS --client-cpu-max-pct <값> \
  --server-sha <배포 커밋> --photo scripts/load/out/fixtures/photo-400k.png \
  --prom-url $PROM_URL --attach $RUN/vm/events-lt.jsonl --attach $RUN/vm/docker-stats.txt
```

`docs/perf/load/data/<YYYY-MM-DD_HHMM>-<RUN_ID>/` 에 남는 것:

| 파일 | 내용 |
|---|---|
| `k6-summary.json` | handleSummary 출력: `meta`(스크립트, 시나리오, RUN_ID, 시작·끝, 단계별 사용자 수·도착률, RTT; G 는 `arrivalProcess`·`prediction`·`measuredQueuedMs`·`measuredProcessingMs`·`recompute`), `verdict`(실패 기준, 단계별 판정과 `sentMbps`·`dropped`, 깨지는 지점과 `breakingPointNote`, `dataSentMbps`·`peakStageSentMbps`·`uplinkRatio`·`uplinkRatioWholeRun`, `dropped_iterations`, `checksFailed`, `unexpectedCount`), `k6`(원본 지표) |
| `run.json` | 조건: VM 사양, 도구 커밋(`git rev-parse HEAD`, scripts/load 미커밋 변경 여부), 서버 커밋, k6 버전, 업링크, 클라이언트 CPU, 사진 크기·SHA-256, 티어, 시나리오, 구간, k6 판정 사본, Prometheus 질의 목록 |
| `prometheus/*.csv` | `unix,time,series,value`: http p95/p99(uri별), RPS(uri·status별), 5xx 비율, `beside_jobs_queue`(state별), 작업 종료율, 에셋 다운로드(outcome별), process/system CPU, 힙 사용·최대, GC, Tomcat 바쁜 스레드, Hikari 대기, 열린 파일, 디스크 여유 |
| `vm/` | 그 실행의 VM 쪽 원본(events.jsonl 의 `lt-` 줄, `docker stats`, glb.sha256 등). run.json 에는 `--attach` 로 준 것만 들어간다 |
| `attachments/` | `--attach` 로 준 파일의 사본(F4 보고 등) |

`docs/perf/notes/` 가 아니라 여기에 둔다(`scripts/perf/report.py` 가 notes/*.md 를 루프백 보고서에 넣기 때문).

## 정직성 규칙 (§10)

- 루프백·로컬 결과는 버전 비교나 동작 확인에만 쓴다. `collect.py` 는 `localhost` 대상이면 run.json `status` 에 "인용하지 않는다"를 적는다.
- mock 결과는 "생성을 뺀 API 처리량", 스텁 결과는 "큐 동작 검증(생성 시간은 가정 T)"이다.
- k6 VU 수는 동시 사용자 수가 아니다. 사용자 수는 도착률로 환산해 적는다: λ = U / 1,800 흐름/s(T3 비율), 그리고 healthz 만 하는 세션 U / 450 /s. 이 환산은 모든 단계에 T3 비율을 쓰므로 "동시 25(T2)" 단계의 λ 는 0.014/s 로, T2 자체 가정의 0.033/s 보다 작다(`CONVERSION_TIER=T2` 로 바꿀 수 있다).
- "API 계층이 T3 를 통과했다"는 T3(1,800) 단계 유지 구간 통과, `dropped_iterations` 0, 기준 충족, 클라이언트 CPU < 70 %, 업링크 비포화(`uplinkRatio` ≤ 0.7, 가장 바쁜 단계 기준)를 모두 만족할 때만 쓴다. 못 넘으면 "최대 지속 처리량 Y RPS(T3 의 k %)"로 쓴다.
- G 시리즈의 예측은 측정한 평균 슬롯 시간으로 다시 계산한 값(`meta.recompute`)과 비교하고, 도착 방식(`meta.arrivalProcess`)을 함께 적는다.
- 결과는 [실험 결과] 로 적고 조건(VM 사양, 커밋 SHA, k6 버전, 업링크, 사진 크기)을 붙인다 — run.json 의 해당 필드.

## 디스크와 VU

- 업로드는 서버 디스크에 남는다. 기본 단계의 예상 업로드량: A1 약 6.7 GB(작업 약 8,300건 × 0.8 MB), A3 small 약 6.1 GB, A3 large 약 7.2 GB. `upload.js` 는 예상량이 `DISK_BUDGET_GB`(8)를 넘으면 시작을 거부하고, 요약 `meta.expectedUploadGB` 에 적는다. 시험이 끝나면 부하용 볼륨을 비운다(deploy/loadtest 절차).
- 흐름 VU 는 사진(0.4 MB)과 본문(0.8 MB)을 들고 있다. A1 은 최대 단계(λ = 5/s)에서 흐름이 600초까지 길어지면 VU 가 약 3,300개 필요하다(`MAX_VUS` 기본 상한 4,000). VU 가 모자라면 `dropped_iterations` 가 생기고, 요약은 그 결과를 통과로 쓰지 않는다(A2 는 그 단계 `fail`, 나머지는 `invalid`).
- `gpu_queue.js` 의 VU: 진행 중 흐름이 대략 푸아송(m), m = λ × 예상 흐름 시간 × 1.5 이므로 m + 3√m, 그리고 솎아낸 반복(바로 끝남) 몫으로 2개를 더 둔다.

## 테스트

```powershell
python -I -m unittest discover -s scripts/load -p "test_*.py"   # model(--queue-* 포함), collect, f4_abort, make_fixtures
node --test "scripts/load/*.test.mjs"                             # k6 스크립트(대역 모듈 + 가짜 API 서버)
```

`node --test` 는 `testing/k6/` 의 대역으로 `k6`, `k6/http`, `k6/metrics`, `k6/execution`, `k6/data` 를 바꾸고 `testing/harness.mjs` 의 가짜 서버(Spring 의 업로드 검사 순서와 크기 상한을 흉내 냄)에 흐름을 돌린다. 이것은 스크립트의 논리 검사이고 k6 실행이 아니다. 대역은 실행기(executor)의 반복 시작 시각·VU 할당·drop, 하위 지표 집계와 기준 평가를 흉내 내지 않는다. 요약 판정 시험은 손으로 만든 summary 객체를 쓴다.

### k6 에 기대는 부분 (k6 로 아직 확인 안 함)

`ramping-arrival-rate`/`constant-arrival-rate` 의 `timeUnit: '1h'`·`'1m'`(정수 rate), 시나리오 `tags`·`startTime`, `exec.scenario.startTime`, 태그 두 개로 좁힌 하위 지표 기준(`metric{endpoint:poll,stage:3,phase:hold}`), `abortOnFail`/`delayAbortEval`, `responseType`·`responseCallback`·`http.expectedStatuses`, `res.json('a.b')`, `http.batch` 객체 형식, `SharedArray`(인덱스 접근과 `length` 만 씀), `open(path, 'b')` 의 상대 경로 해석, `handleSummary` 의 `setup_data`·파일 출력, `summaryTrendStats` 의 `count`. 첫 로컬 smoke 와 짧은 A1(`USERS=25,200 RAMP_S=10 HOLD_S=30`)에서 요약 JSON 에 단계별 하위 지표가 나오는지 확인한다.

이번에 더한 것도 대역으로는 확인되지 않는다:
- 솎아내기(`gpu_queue.js`): `constant-arrival-rate` 가 반복을 같은 간격 틱으로 시작한다는 것, 바로 끝나는 반복이 VU 를 거의 잡지 않는다는 것, 그래서 흐름 도착이 푸아송에 가깝고 위 VU 수로 drop 이 없다는 것. 요약에는 표본 시각이 없으므로, 첫 G1 을 `k6 run --out json=<파일>` 로 돌려 `endpoint=create` 요청 시각의 간격 분포를 보고 `dropped_iterations` 가 0 인지 본다.
- 시나리오 `tags`(stage·phase)가 사용자 지표(`load_unexpectedCount`, `load_bytesSent`)의 표본에도 붙어 `{stage:N,phase:hold}` 하위 지표로 모인다는 것(A2 의 단계별 `load_unexpectedCount` 기준).
- 표본이 하나도 없는 Counter 의 `count==0`·`count>=0` 기준이 오류 없이 통과로 평가되고 요약에 `values.count` 로 나온다는 것.
- `dropped_iterations{scenario:rpsN}` 하위 지표가 시나리오 이름 태그로 모인다는 것(k6 는 drop 에 시나리오 `tags` 를 붙이지 않는다고 보고 단계 태그 대신 이것을 쓴다).
- 요약의 `checks` 가 `values.passes`·`values.fails` 를 가진다는 것.

## 트래픽 모델 메모

- `python scripts/load/model.py` 의 숫자는 §3·§5 표와 같다(`test_model.py` 가 문서의 숫자를 그대로 확인한다): T3 동시 1,800, λ 1.0/s, 스파이크 3.0/s, RPS(T=60) 37 / 포화 307 / 스파이크 포화 921(요약의 "약 920"), 에셋 11.4 Mbps, 업로드 6.4 Mbps, 디스크 69 GB/일, T3 GPU 43/86/172/429, T2 GPU 2/3/6/15, T2 1대 최대 T 21초, Q_max(c=1, T=60) 9.
- 전체 RPS(§4) = 세션 시작마다 healthz 1 + POST λ + 에셋 λ(1−f) + retry λf + 폴링 L/2, L = λ(Wq + T), Wq 는 M/M/c(Erlang C, c 는 ρ ≤ 0.7 이 되는 최소 대수). retry 뒤 두 번째 대기는 넣지 않는다(f = 0.05 에서 5 % 미만).
- T1·T2 는 공통 가정(DAU/MAU 0.15, DAU당 0.2건)이 아니라 tiers.json 의 티어별 값으로 계산한다(§2): T2 = DAU/MAU 0.2, DAU당 0.5건(→ DAU 2,000, 120건/h), T1 = 팀원 20명, 피크 비율 0.5, 세션 30분, DAU당 1건(→ 동시 5, 10건/h).
- 디스크(§7): T2 는 하루 2.3 GB 라 사용 가능 약 20 GB [가정] 기준 약 9일 (30 GB 전부면 13일). `model.py` 의 "30 GB 가 차는 날수"(13.0)는 tiers.json 의 `diskBudgetGB` 30 을 통째로 나눈 값이다. 실제로 쓸 수 있는 공간은 VM 의 `df` 로 확인한다.
