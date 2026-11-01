# deploy/loadtest — 배포 VM 부하 시험 절차

배포 VM(2 vCPU · 4GB)에서 k6 로 부하를 거는 동안 운영 backend 를 부하용 backend 로 바꿔 끼우고, 끝나면 운영으로 되돌리는 절차다. 목표 트래픽(T1/T2/T3)·합격 기준·시험 목록은 [docs/perf/load/TRAFFIC_MODEL.md](../../docs/perf/load/TRAFFIC_MODEL.md), k6 스크립트와 수집 스크립트는 [scripts/load/](../../scripts/load/) 에 있다.

## 상태

| 항목 | 상태 |
|---|---|
| [docker-compose.loadtest.yml](../docker-compose.loadtest.yml)(backend 덮어쓰기), 이 문서 | 파일 **[구현됨]**. 환경변수 → Spring 속성 대응은 `application*.properties`·`@Value` 이름을 읽어 확인했다. 덮어쓰기 결과(`docker compose ... config`)는 CI 가 확인하도록 넣었지만 아직 돌지 않았다(이 PC 에 Docker 없음) |
| 아래 절차 전체(VM 에서 띄우기, 합성 GLB, k6, 수집, 복구, 운영 DB 확인 SQL) | **[계획]** — VM 에서 한 번도 실행하지 않았다. 실행한 뒤 이 표와 결과 문서에 날짜·커밋을 적는다 |

## 지키는 것

- **다른 측정 중에는 하지 않는다.** 개발 PC 의 `scripts/perf`·다른 벤치마크, GPU PC 의 실제 생성 실험(exp-01·02), 시연·발표 리허설과 겹치지 않게 잡는다. 부하를 거는 개발 PC 에서 게임·빌드도 하지 않는다.
- **실제 GPU PC 를 부르지 않는다.** 덮어쓰기의 추론 주소 기본값은 아무 데도 닿지 않는 `http://127.0.0.1:9` 이고, 운영 `INFERENCE_TOKEN` 도 넘기지 않는다. G 시리즈는 스텁 주소만 넣는다.
- **공개 경로만** 부른다(Basic 인증 경로 제외). 사진 파일명은 `lt-<runId>-` 로 시작하고 `Idempotency-Key` 를 붙인다(k6 스크립트가 한다). 파일명에 `fail` 이 들어가면 mock 워커가 일부러 `INFERENCE_FAILED` 로 끝낸다(F1 용).
- prod 프로파일이 켜져 있어 사진 시그니처를 검사한다: k6 사진은 진짜 PNG/JPEG 여야 한다.
- **`docker compose ... down -v` 를 쓰지 않는다.** 운영 볼륨 `beside-storage`(H2 DB·사진·GLB)까지 지워진다. 지우는 볼륨은 이름을 정확히 쓴다: `docker volume rm beside-loadtest-storage`.
- **중단 조건**: 오류(5xx·연결 오류) > 5 % 가 1분 지속되거나 디스크 사용률 > 80 % 이면 k6 를 멈추고(Ctrl+C) 곧장 '6. 복구' 로 간다.

## 덮어쓰기가 바꾸는 것

backend 서비스만 바꾼다. caddy·`caddy_data`(인증서)·prometheus·grafana·이미지(`beside-backend:local`)·`mem_limit 2g`·관리 포트 8081 은 그대로다. 같은 프로젝트(`beside`)의 같은 서비스라 `up -d backend` 는 운영 backend 컨테이너를 **대신**한다(4GB 에 backend 두 개는 못 띄운다). 값은 compose 명령 앞의 셸 환경변수로 바꾼다(`deploy/.env` 보다 우선).

| 셸에서 바꾸는 이름 | 컨테이너 환경변수 → Spring 속성 | 기본값 | 이유 |
|---|---|---|---|
| `LOADTEST_PROFILES` | `SPRING_PROFILES_ACTIVE` | `mock,prod` | A 시리즈 = mock(생성 뺀 API 계층), G 시리즈 = `real,prod` |
| (고정) | `METRICS_ENV` → 지표 태그 `env` | `loadtest` | Grafana 에서 운영(`prod`)과 나눠 본다 |
| (고정) | `NOTIFY_WEBHOOKURL` → `notify.webhook-url` | 빈 값 | 팀 채널에 알림 수천 건이 가지 않게 |
| (고정) | `STORAGE_BACKUP_ENABLED` → `storage.backup.enabled` | `false` | 매일 H2 백업(DatabaseBackup) 끔 |
| `MOCK_WORKER_PENDINGMS` | → `mock.worker.pending-ms` | `0` | 접수 즉시 PROCESSING |
| `MOCK_WORKER_PROCESSINGMS` | → `mock.worker.processing-ms` | `60000` | 생성 시간 T = 60초 가정 |
| `SPRING_TASK_EXECUTION_POOL_CORESIZE` | → `spring.task.execution.pool.core-size` | `400` | 기본 8 스레드면 mock 이 'GPU 8대'처럼 동작한다(큐가 무한이라 스레드 수 = core-size) |
| `LOADTEST_INFERENCE_BASE_URL` | `INFERENCE_BASEURL` → `inference.base-url` | `http://127.0.0.1:9` | G 시리즈만. 스텁 주소(`http://<스텁 Tailscale IP>:8001`) |
| `LOADTEST_INFERENCE_TOKEN` | `INFERENCE_TOKEN` → `inference.token` | 빈 값 | 스텁의 `BESIDE_INFERENCE_TOKEN` 과 같게(운영 토큰과 다른 값) |
| (고정) | `INFERENCE_TRANSFER` → `inference.transfer` | `upload` | VM ↔ 스텁은 디스크를 공유하지 않는다 |
| `LOADTEST_INFERENCE_TIMEOUT_MS` | `INFERENCE_TIMEOUTMS` → `inference.timeout-ms` | `120000` | 운영 600000 보다 짧게. F1b 는 `20000` |
| `LOADTEST_INFERENCE_OFFLINE_WAIT_MS` | `INFERENCE_OFFLINEWAITMS` → `inference.offline-wait-ms` | `300000` | 운영 1800000 보다 짧게. F2 는 운영과 같은 `1800000` |
| `LOADTEST_INFERENCE_MAX_CONCURRENCY` | `INFERENCE_MAXCONCURRENCY` → `inference.max-concurrency` | `1` | G2 의 c(1·2·4). 스텁 `BESIDE_PASSTHROUGH_SLOTS` 와 같게 |
| (볼륨) | `beside-loadtest-storage:/app/storage` | — | 부하 작업·사진·`events.jsonl` 은 여기에만 쌓인다. 운영 `beside-storage` 는 붙지 않는다 |

### 시리즈별 값

| 시리즈 | VM 쪽(셸 환경변수) | 스텁 쪽(G·F 만, `generation/inference_service` 패스스루) |
|---|---|---|
| A0~A6 (API 계층) | 기본값 그대로 | — |
| G1 (큐 이론, c=1, T=10초) | `LOADTEST_PROFILES=real,prod LOADTEST_INFERENCE_BASE_URL=http://<스텁>:8001 LOADTEST_INFERENCE_TOKEN=<스텁 토큰>` | `BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1` |
| G2 (c = 1, 2, 4) | G1 + `LOADTEST_INFERENCE_MAX_CONCURRENCY=<c>` | `BESIDE_PASSTHROUGH_SLOTS=<c>` |
| G3 (과부하 ρ=1.5) | G1 과 같음(도착률은 k6 가 정한다) | G1 과 같음 |
| F1a (생성 실패 20 %) | G1 과 같음 | `BESIDE_PASSTHROUGH_FAIL_RATE=0.2` |
| F1b (타임아웃) | G1 + `LOADTEST_INFERENCE_TIMEOUT_MS=20000` | `BESIDE_PASSTHROUGH_SLOW_TOKEN=slow BESIDE_PASSTHROUGH_SLOW_MS=30000` |
| F2 (GPU 꺼짐·backend 재시작) | G1 + `LOADTEST_INFERENCE_OFFLINE_WAIT_MS=1800000` | 시험 중 스텁을 끄고 다시 켠다 |
| F3·F4 (잘못된 업로드·다운로드 중단) | 기본값(mock) | — |

스텁의 `BESIDE_PASSTHROUGH_*` 설정은 기본값이 모두 꺼져 있다(같은 W1 의 B5). 스텁은 실제 GPU PC 가 아닌 PC(예: 개발 PC)에서 Tailscale 주소에 띄운다:

```bash
# 스텁 PC (Linux·WSL bash). Python 환경은 generation/inference_service/README.md.
cd generation/inference_service
BESIDE_SAMPLE_GLB=/tmp/lt-sample-1500000.glb \
BESIDE_INFERENCE_TOKEN='<LOADTEST_INFERENCE_TOKEN 과 같은 값>' \
BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1 \
python -m uvicorn app:app --host "$(tailscale ip -4)" --port 8001
```

VM 의 backend 컨테이너에서 스텁이 닿는지 먼저 본다: `$L exec backend curl -sS http://<스텁>:8001/healthz`(`"passthrough": true`). 컨테이너에서만 안 닿으면 [deploy/README.md](../README.md) '문제 해결'의 Tailscale 항목.

## 0. 준비 (하루 전까지)

1. **팀 공지**(Discord 등): 시작 시각·예상 길이, "그동안 앱·웹으로 만든 작업은 처리되지 않는다(운영 backend 를 내린다), 시작 시점에 진행 중인 작업은 FAILED 가 되니 재시도", 담당자.
2. 개발 PC: k6 설치(`k6 version` 기록), 유선 연결, 업링크 측정(iperf3 또는 speedtest 결과 기록). 그 시간에 다른 측정·빌드가 없게 한다.
3. VM: `df -h /` 사용률이 50 % 아래인지(업로드가 쌓인다 — T3 피크면 시간당 수 GB), swap 2GB, monitoring 프로필이 떠 있는지([deploy/README.md](../README.md) '모니터링').
4. 기록할 조건: VM 사양, 커밋 SHA(`git -C ~/Beside rev-parse --short HEAD`), k6 버전, 업링크, 사진 크기, 시각.

## 1. 운영 백업 (운영 backend 를 멈춘 채)

```bash
cd ~/Beside && mkdir -p deploy/backups
# monitoring 을 안 띄웠으면 --profile monitoring 을 뺀다.
C="docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile monitoring"
L="$C -f deploy/docker-compose.loadtest.yml"
TS=$(date +%F_%H%M); echo "$TS"          # 이 값이 docs/perf/load/data/<TS>/ 이름이 된다
D=https://<도메인>

# 진행 중인 작업이 없는지(최근 20건에 PENDING·PROCESSING 이 없어야 한다)
curl -fsS -u 'team:팀 비밀번호' "$D/api/v1/jobs?limit=20" | grep -o '"status":"[A-Z]*"' | sort | uniq -c

$C stop backend
docker run --rm -v beside-storage:/data:ro -v "$PWD/deploy/backups:/backup" alpine:3.22 \
  tar czf "/backup/beside-storage-pre-lt-$TS.tgz" -C /data .
# 운영 DB 파일의 해시: 복구 직전에 다시 재서 같으면 부하 시험이 운영 DB 를 건드리지 않은 것이다.
docker run --rm -v beside-storage:/data:ro alpine:3.22 sha256sum /data/db/beside-real.mv.db | tee "deploy/backups/pre-lt-$TS.sha256"
ls -l deploy/backups/
```

백업 tgz 는 개발 PC 로도 옮겨 둔다(`scp <user@VM>:~/Beside/deploy/backups/beside-storage-pre-lt-*.tgz .`).

## 2. 부하 backend 띄우기

```bash
$L up -d backend                          # A 시리즈. G 시리즈는 앞에 '시리즈별 값'의 셸 변수를 붙인다
$L ps
# 볼륨이 바뀌었는지: beside-loadtest-storage -> /app/storage 여야 한다. beside-storage 가 보이면 멈추고 '6. 복구'
docker inspect -f '{{range .Mounts}}{{.Name}} -> {{.Destination}}{{println}}{{end}}' beside-backend-1
# 설정이 들어갔는지(토큰 값은 가린다)
$L exec -T backend sh -c 'env | grep -E "^(SPRING_PROFILES_ACTIVE|METRICS_ENV|NOTIFY_WEBHOOKURL|STORAGE_BACKUP_ENABLED|MOCK_WORKER_|SPRING_TASK_EXECUTION_POOL_CORESIZE|INFERENCE_)" | sed "s/TOKEN=.*/TOKEN=(hidden)/"'
curl -fsS "$D/api/v1/healthz"; echo        # {"status":"ok","profile":"mock,prod","workerType":"mock"} (G 는 real,prod / real)
```

backend 가 healthy 가 될 때까지 30~60초 걸린다. 볼륨이 처음 만들어질 때 이미지의 `/app/storage` 소유자(beside, uid 10001)를 그대로 받으므로, **3번(합성 GLB)은 반드시 이 단계 뒤**에 한다(다른 컨테이너로 먼저 볼륨을 만들면 root 소유가 되어 backend 가 DB 를 못 쓴다).

## 3. 합성 GLB 1.5 MB 넣기 (A 시리즈)

mock 워커는 COMPLETED 작업에 `storage.result.sample` = `storage/results/sample-dog.glb`(작업 폴더 `/app` 기준)를 내준다. 즉 컨테이너의 `/app/storage/results/sample-dog.glb` = **볼륨 `beside-loadtest-storage` 의 `results/sample-dog.glb`** 다. 새 볼륨과 이미지에는 이 파일이 없고(저장소의 `backend/storage/results/sample-dog.glb` 는 0 B), 없으면 asset 요청이 실패한다.

```bash
# VM 의 리포 루트에서: scripts/perf/fixtures.py 의 glb_synthetic(seed 14 = 루프백 벤치마크의 glb_sample 과 같은 바이트)
python3 -c "import sys; sys.path.insert(0, 'scripts/perf'); import fixtures; open('/tmp/lt-sample-1500000.glb', 'wb').write(fixtures.glb_synthetic(1_500_000, seed=14))"
sha256sum /tmp/lt-sample-1500000.glb      # run.json 에 적는다
# backend 사용자(beside)로 써서 소유자가 맞게
$L exec -T backend sh -c 'mkdir -p /app/storage/results && cat > /app/storage/results/sample-dog.glb' < /tmp/lt-sample-1500000.glb
$L exec -T backend sh -c 'ls -l /app/storage/results/sample-dog.glb && sha256sum /app/storage/results/sample-dog.glb'   # 1500000 바이트, 같은 해시
```

한 건으로 끝까지 확인한다(1×1 PNG, `MOCK_WORKER_PROCESSINGMS` 만큼 기다린 뒤 asset 이 1500000 바이트):

```bash
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=' | base64 -d > /tmp/lt-probe.png
JOB=$(curl -fsS -H "Idempotency-Key: lt-probe-$TS" -F 'photos=@/tmp/lt-probe.png;filename=lt-probe-0.png;type=image/png' "$D/api/v1/jobs" | grep -o '"jobId":"[^"]*"' | cut -d'"' -f4); echo "$JOB"
# 60초 뒤
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' "$D/api/v1/jobs/$JOB/asset"    # 200 1500000
```

볼륨이 남아 있는 동안에는 backend 를 다시 만들어도(설정을 바꾼 `$L up -d backend`) 파일이 그대로다. G 시리즈는 스텁이 자기 `BESIDE_SAMPLE_GLB` 를 돌려주므로 이 파일을 쓰지 않는다.

## 4. k6 실행 (개발 PC)

명령과 옵션은 [scripts/load/README.md](../../scripts/load/README.md)(같은 W1 의 B7). 순서는 A0(연기 시험, RTT 기준) → A1~A4 → G1·G2 → F1~F4, 시리즈마다 VM 쪽 값을 바꿔야 하면 그 값으로 `$L up -d backend` 를 다시 한다(그때 진행 중이던 부하 작업은 부하 DB 에서 FAILED 가 된다 — 운영과 무관).

시험 중에 볼 것:

- Grafana **Beside 운영**, `env = loadtest`: 5xx 비율, API p95, 대기열, @Async 실행기, 힙·CPU, 디스크 여유.
- VM 의 두 번째 ssh 창: `watch -n 10 'df -h / | tail -1; docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"'`
- 개발 PC: k6 클라이언트 CPU(70 % 넘으면 결과를 'T3 통과'로 쓰지 않는다), `dropped_iterations`.
- 중단 조건(오류 > 5 % 1분, 디스크 > 80 %)이면 Ctrl+C → '6. 복구'.

## 5. 수집 → `docs/perf/load/data/<YYYY-MM-DD_HHMM>/`

`<YYYY-MM-DD_HHMM>` 은 1번의 `$TS`. 수집 스크립트(scripts/load, B7)가 k6 summary · Prometheus 구간 값 · `events.jsonl` · `docker stats` 를 모아 `run.json` 을 만든다. 파일 이름은 그 스크립트를 따른다. VM 에서 꺼낼 원본:

```bash
OUT=~/lt-$TS && mkdir -p "$OUT"
$L cp backend:/app/storage/events.jsonl "$OUT/events.jsonl"     # 부하 볼륨의 서버 측정 로그(docs/METRICS.md §1)
$L logs --no-color --since 6h backend > "$OUT/backend.log"
docker stats --no-stream --format '{{json .}}' > "$OUT/docker-stats-end.jsonl"
df -h / > "$OUT/df.txt"
git -C ~/Beside rev-parse HEAD > "$OUT/commit.txt"
sha256sum /tmp/lt-sample-1500000.glb > "$OUT/glb.sha256"
# Prometheus 는 게시하지 않으므로 VM 안에서 읽는다(예: 대상 상태)
$L exec -T prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up' > "$OUT/prom-up.json"
```

개발 PC 에서 `scp -r <user@VM>:~/lt-<TS> docs/perf/load/data/<TS>/vm` 로 가져와 k6 쪽 파일과 같은 폴더에 둔다. 결과 문서에는 [실험 결과] 와 조건(VM 사양, 커밋, k6 버전, 업링크, 사진 크기)을 붙인다.

## 6. 복구 (운영 스택으로)

```bash
$L stop backend                           # 부하 backend 멈춤. 볼륨 beside-loadtest-storage 는 남는다
# (1) 운영 DB 파일이 1번 때와 같은지: 해시가 같아야 한다
docker run --rm -v beside-storage:/data:ro alpine:3.22 sha256sum /data/db/beside-real.mv.db
cat "deploy/backups/pre-lt-$TS.sha256"
# (2) 운영 DB 에 lt- 작업이 0건인지(H2 Shell, 읽기 전용으로 연다. 운영 backend 가 멈춘 지금만 열 수 있다)
docker run --rm -v beside-storage:/app/storage --entrypoint java beside-backend:local \
  -Dloader.main=org.h2.tools.Shell -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  -url 'jdbc:h2:file:/app/storage/db/beside-real;ACCESS_MODE_DATA=r' -user sa -password '' \
  -sql "SELECT COUNT(*) AS LT_JOBS FROM JOBS WHERE UPLOADS LIKE '%\"name\":\"lt-%'"          # LT_JOBS = 0
# (3) 운영 backend 다시
$C up -d backend
$C ps                                      # backend healthy
```

- (2) 의 명령은 이미지 안의 Spring Boot 실행 jar 에서 H2 의 `org.h2.tools.Shell` 을 꺼내 쓰는 방법이다(Spring Boot 3.2 의 `PropertiesLauncher` 는 jar 안 `BOOT-INF/lib` 를 클래스패스에 넣는다). **아직 실행해 본 적 없다 [계획]**. 안 되면 대신 운영 backend 를 띄운 뒤 목록 API 로 최근 작업을 본다: `curl -fsS -u 'team:팀 비밀번호' "$D/api/v1/jobs?limit=100" | grep -c '"name":"lt-'` 가 0(응답의 `nextCursor` 로 다음 쪽).
- `uploads` 열은 사진 목록 JSON(`[{"name":"lt-...","bytes":...,"path":...}]`)이라 LIKE 로 파일명을 찾는다.

## 7. 확인 (복구 뒤)

```bash
curl -fsS "$D/api/v1/healthz"; echo                    # "profile":"real,prod","workerType":"real"
for p in /h2-console /swagger-ui.html /v3/api-docs /api/jobs '/api/v1/jobs;x=1' /actuator /actuator/prometheus; do
  curl -s -o /dev/null -w "%{http_code} $p\n" "$D$p"   # 모두 404
done
curl -s -o /dev/null -w "%{http_code}\n" "$D/api/v1/jobs"   # 401
curl -s -o /dev/null -w "%{http_code}\n" "$D/grafana/"      # 401
df -h /                                                     # 사용률 확인
```

- Grafana `env = prod` 패널이 다시 채워지는지, `$C exec prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up'` 의 beside-backend 가 1 인지.
- 팀 공지: 끝났다고 알린다.
- 수집(5번)을 마쳤으면 부하 볼륨을 지운다(운영 backend 가 다시 뜬 뒤라 쓰는 컨테이너가 없다): `docker volume rm beside-loadtest-storage`. 다음 시리즈에 같은 합성 GLB 를 다시 쓰려면 남겨 둔다.

## 비상시

| 상황 | 할 일 |
|---|---|
| 오류 > 5 % 가 1분 지속 · 디스크 > 80 % | k6 Ctrl+C → 6번 복구. 디스크가 문제면 수집 뒤 `docker volume rm beside-loadtest-storage` |
| 부하 backend 가 응답 없음 · 메모리 부족으로 재시작 반복 | `$L logs --tail 200 backend` 를 `$OUT` 에 남기고 6번 복구 |
| 2번에서 `beside-storage` 가 붙어 있음 | 바로 `$L stop backend` → 1번 백업에서 복원 여부 판단([deploy/README.md](../README.md) '백업'의 복원) → 덮어쓰기 명령(`-f deploy/docker-compose.loadtest.yml`)을 다시 확인 |
| 운영으로 돌아갔는데 healthz 가 안 됨 | `$C logs --tail 200 backend`. DB 잠김이면 남은 컨테이너가 있는지 `docker ps -a` |
