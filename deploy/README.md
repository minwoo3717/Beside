# deploy — 클라우드 VM 배포 (Docker Compose + Caddy)

백엔드(Spring, profile `real,prod`)를 클라우드 VM 한 대에 Docker Compose 로 올린다. GPU 추론 서비스(FastAPI + AnimalLift)는 팀원 GPU PC 에 그대로 두고 **Tailscale** 사설망으로 부른다. 팀 내부 테스트용이라 계정·로그인은 없고, 웹·관리 경로만 Caddy **Basic 인증**(팀 공용 계정 하나)으로 막는다. 결정 배경은 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) '배포 가정'.

```text
폰(Unity 앱) ─┐                       ┌──────────── 클라우드 VM (Docker Compose) ────────────┐
브라우저 ─────┴─ HTTPS 443 ─────────▶ │ caddy ──▶ backend:8080 (real,prod) ──▶ 볼륨 beside-storage │
                                      └──────────────────────────────┬─────────────────────────┘
                                                Tailscale 100.x:8001 │  /infer
                                                                     ▼
                                                  팀원 GPU PC: FastAPI + AnimalLift (WSL)
```

## 상태

| 항목 | 상태 |
|---|---|
| `backend/Dockerfile`, 이 폴더의 `docker-compose.yml`·`Caddyfile`·`.env.example`·`test_caddy_routes.sh`, [scripts/deploy_vm.sh](../scripts/deploy_vm.sh), CI [backend-ci.yml](../.github/workflows/backend-ci.yml) | 파일 **[구현됨]** · 실행 **[계획]**: 이미지 빌드·Caddyfile 검증·경로 시험은 CI(main 대상 push·PR)에서 돌도록 작성했지만 아직 한 번도 돌지 않았다(첫 실행은 deploy/vm-4b → main PR. 작성한 PC 에 Docker 가 없어 로컬에서도 돌려 보지 않았다) |
| 환경변수 `INFERENCE_BASEURL` → `inference.base-url` | **[구현됨]** bootJar 를 환경변수로 띄워 확인 |
| 실제 VM·도메인·Tailscale 준비, 폰 → VM → GPU PC 1건 | **[계획]** 아래 '직접 할 일' |
| prod 프로파일(H2 콘솔·Swagger off, forward-headers, 업로드 사진 시그니처 검사) | **[구현됨]** (S1, `ProdProfileTest`). Caddy 설정도 H2 콘솔·Swagger·v0 를 404 로 막는다(설정 **[구현됨]**, 실제 동작 확인은 CI 첫 실행·VM 배포 **[계획]**). 파일 로그는 두지 않았다 — `docker compose logs` 를 쓴다 |
| `/infer` 업로드 모드(`INFERENCE_TRANSFER=upload`, `INFERENCE_TOKEN`) | **[구현됨]** (S2). 같은 PC 에서 패스스루 서비스와 Spring real 을 띄워 e2e 로 확인. VM ↔ GPU PC 실제 연결은 **[계획]** |
| GPU 오프라인 대기(최대 30분 PENDING), H2 온라인 백업(매일 04:30, 7개), Discord 알림(`NOTIFY_WEBHOOK_URL`) | **[구현됨]** (S3, 테스트로 확인). VM 에서 실제로 돌린 적은 없다 — **[계획]** |
| 모니터링(compose `monitoring` 프로필: Prometheus + Grafana 대시보드 'Beside 운영'), Caddy `/actuator*` 404·`/grafana*` 인증, 컨테이너 로그 회전 | 파일 **[구현됨]**(대시보드 JSON 은 `json.tool` 로 형식만 확인) · 실행 **[계획]**: compose 검증·경로 시험은 CI 에 넣었지만 아직 돌지 않았고, Prometheus·Grafana 를 실제로 띄워 본 적은 없다(이 PC 에 Docker 없음). 아래 '모니터링' |
| 부하 시험 덮어쓰기(`docker-compose.loadtest.yml`: backend 만 부하용 볼륨·mock 설정으로 바꿈)와 절차 | 파일 **[구현됨]** · 실행 **[계획]** — [loadtest/README.md](loadtest/README.md) |

## 경로 규칙 (Caddyfile)

| 처리 | 경로 |
|---|---|
| **404** (없는 것처럼) | `/api/jobs`, `/api/jobs/*`(v0 동결 API — 서버 경로를 노출한다), `/h2-console*`, `/swagger*`, `/v3/api-docs*`, `/actuator*`(Actuator 는 관리 포트 8081 에만 있다 — 두 겹), 경로에 `;` 가 든 모든 요청 |
| **Basic 인증** | `/`, `/index.html`, `/app.js`(Mock 업로드 UI), `/admin*`, `/library*`, `/job*`(앞으로 생길 웹 화면), `/grafana*`(→ Grafana, 그다음 Grafana 로그인), `GET`·`HEAD /api/v1/jobs`(목록), `DELETE /api/v1/jobs/*`, `/api/v1/jobs/*/pet`, `/api/v1/admin*`, `/api/v1/pets*` |
| **공개** | 그 밖 전부: 앱 API(`POST /api/v1/jobs`, `GET /api/v1/jobs/{id}`·`/asset`, `POST /retry`), `/api/v1/healthz`, 공유 링크(`/s/*`, `/share.*`, `/api/v1/shares/*`), `styles.css` 같은 정적 파일 |
| **413** | 요청 본문 25MB 초과(Caddy). 20MB·장당 5MB 초과는 Spring 이 v1 오류 봉투(`PAYLOAD_TOO_LARGE`)로 413 |

- `;` 를 막는 이유: Tomcat 은 `/api/v1/jobs;x=1`, `/api;x/v1/jobs`, `/api/jobs/{id};x` 를 `;` 뒤를 뗀 경로로 처리한다(로컬 real 서버에서 셋 다 200 을 확인). 열어 두면 인증·404 규칙을 비켜 간다.
- Caddy 의 path 비교는 대소문자를 가리지 않고, 퍼센트 인코딩을 풀고 `//`·`..` 를 정리한 뒤에 한다. `caddy:2.11.1` 미만은 이 정리에 우회 취약점(CVE-2026-27587)이 있으므로 이미지를 내리지 않는다.
- 규칙을 바꾸면 [test_caddy_routes.sh](test_caddy_routes.sh) 의 기대값도 고친다. Linux + Docker 에서 `bash deploy/test_caddy_routes.sh` 로 가짜 백엔드 앞에 같은 Caddyfile 을 띄워 경로마다 상태 코드를 확인한다(CI 도 같은 명령).
- 공개 경로의 작업은 UUID 를 아는 사람만 볼 수 있다는 가정이다(계정 없음). 목록 API 를 막는 이유가 이것이다.

## 직접 할 일 (한 번)

### 1. VM 만들기

- Ubuntu 24.04 LTS, **2 vCPU · 4GB RAM · 30GB 디스크** 권장(backend 컨테이너 한도 2GB + Caddy + 첫 Gradle 빌드). 공인 IP 는 고정(예약) IP 로 받는다 — 도메인이 이 IP 를 가리킨다.
- 클라우드 방화벽(보안 그룹)은 인바운드 **TCP 80, TCP 443** 만 연다(UDP 443 은 HTTP/3 용, 선택). SSH 22 는 가능하면 내 IP 로만. **8080 은 열지 않는다.**
- Docker 가 게시한 포트(80/443)는 ufw 규칙을 거치지 않는다. 그래서 막는 일은 클라우드 방화벽과 "8080 을 게시하지 않는 compose"(`expose` 만)가 맡는다. ufw 를 쓴다면 `sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable`.

Docker Engine + compose 플러그인 설치(공식 apt 저장소, docs.docker.com/engine/install/ubuntu):

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"     # 로그아웃 후 다시 로그인하면 sudo 없이 docker
docker compose version              # v2 인지 확인
```

### 2. 도메인 (예: DuckDNS)

1. duckdns.org 에 로그인 → 하위 도메인(예 `beside-team`) 추가 → **current ip** 에 VM 공인 IP 를 넣고 update.
2. `dig +short beside-team.duckdns.org` 가 VM IP 를 돌려주는지 본다.
3. 인증서는 Caddy 가 처음 뜰 때 Let's Encrypt 에서 자동으로 받는다(80/443 이 밖에서 닿아야 한다). 받은 인증서는 `caddy_data` 볼륨에 남는다.

### 3. Tailscale (VM 과 GPU PC 를 한 사설망에)

```bash
# VM
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up                  # 출력된 링크로 팀 tailnet 에 로그인
tailscale ip -4                    # VM 의 100.x 주소
```

- GPU PC: 추론 서비스가 **WSL2 안**에서 돌면 Tailscale 도 **WSL 안**에 설치하고 로그인한다(위와 같은 명령. WSL 에 systemd 가 꺼져 있으면 `/etc/wsl.conf` 에 `[boot]` `systemd=true` 를 넣고 PowerShell 에서 `wsl --shutdown` 뒤 다시 연다). 그 WSL 의 `tailscale ip -4` 가 `.env` 의 `INFERENCE_BASE_URL` 과 GPU PC 쪽 `BESIDE_HOST` 가 된다.
- 연결 확인: VM 에서 `tailscale ping <GPU PC 100.x>`.
- (선택) Tailscale 관리 콘솔의 Access controls 에서 VM → GPU PC:8001 만 허용하면 tailnet 안의 다른 기기는 추론 서비스에 못 붙는다.

### 4. 리포와 `.env`

```bash
git clone <리포 URL> ~/Beside          # 비공개 리포면 VM 용 deploy key(읽기 전용)나 gh auth login
cd ~/Beside
cp deploy/.env.example deploy/.env && chmod 600 deploy/.env
docker run --rm caddy:2.11.7 caddy hash-password --plaintext '팀 비밀번호'   # 출력 한 줄을 BASIC_AUTH_HASH 에
openssl rand -hex 32                                                       # INFERENCE_TOKEN (GPU PC 와 같은 값)
nano deploy/.env
```

| 변수 | 값 |
|---|---|
| `BESIDE_DOMAIN` | `beside-team.duckdns.org` (스킴·경로 없이) |
| `BASIC_AUTH_USER` | 팀 공용 사용자 이름 |
| `BASIC_AUTH_HASH` | `caddy hash-password` 출력. **작은따옴표로 감싼다**: `BASIC_AUTH_HASH='$2a$14$...'` |
| `INFERENCE_BASE_URL` | `http://<GPU PC 의 Tailscale IP>:8001` |
| `INFERENCE_TOKEN` | 긴 무작위 문자열(`openssl rand -hex 32`). GPU PC 의 `BESIDE_INFERENCE_TOKEN` 과 같게 |
| `NOTIFY_WEBHOOK_URL` | (선택) Discord 웹훅 URL. 작업 완성·실패와 GPU 서버 연결/끊김을 팀 채널에 알린다. 비우면 끔 |

**해시의 `$` 처리**: docker compose v2 는 `.env` 의 따옴표 없는 값과 큰따옴표 값에서 `$이름`·`${이름}` 을 변수로 바꾸고, **작은따옴표 값은 글자 그대로** 쓴다(Docker 문서 'Interpolation — .env file syntax'). bcrypt 해시(`$2a$14$...`)를 따옴표 없이 넣으면 `$14...` 같은 부분이 빈 값으로 바뀌어 로그인이 영영 안 된다. `$$` 는 compose **YAML 파일 안**에서 `$` 를 쓰는 방법이고 `.env` 에서는 쓰지 않는다. 들어간 값은 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec caddy printenv BASIC_AUTH_HASH` 로 확인한다(CI 도 견본 파일로 같은 확인을 한다).

## 띄우기

리포 루트에서(compose 파일 기준 상대 경로와 `--env-file` 경로가 맞도록):

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps     # backend 가 healthy 가 되면 준비 끝
```

첫 빌드는 Gradle 의존성을 받느라 몇 분 걸린다. 이후에는 코드만 바뀌면 빠르다.

## 확인

밖(개발 PC)에서:

```bash
D=https://beside-team.duckdns.org
curl $D/api/v1/healthz                                          # 200 {"status":"ok","profile":"real,prod","workerType":"real"}
for p in /h2-console /swagger-ui.html /v3/api-docs /api/jobs '/api/v1/jobs;x=1' /actuator /actuator/prometheus; do
  curl -s -o /dev/null -w "%{http_code} $p\n" "$D$p"            # 모두 404
done
curl -s -o /dev/null -w "%{http_code}\n" $D/api/v1/jobs           # 401
curl -s -o /dev/null -w "%{http_code}\n" -u 'team:팀 비밀번호' $D/api/v1/jobs   # 200
TIMEOUT_SECONDS=600 scripts/e2e_mock.sh $D ~/photos/dog1.jpg     # 전체 흐름. GPU PC 가 꺼져 있으면 작업은 PENDING 으로 기다린다(최대 30분)
```

브라우저로 `https://<도메인>/` 을 열면 Basic 인증 창이 뜨고, 로그인하면 Mock 업로드 UI 가 보인다.

## 모니터링 (Prometheus + Grafana, 선택)

설정 파일 **[구현됨]** · VM 에서 띄우기 **[계획]**. 멘토 지표 5종(생성 성공률, 생성 시간 평균·p95, 대기열 길이, API 응답 시간, 에셋 다운로드 실패율)과 서버 자원을 한 화면에서 본다. 지표 이름은 [docs/METRICS.md](../docs/METRICS.md) §5(`beside.*` → Prometheus `beside_*`).

```text
backend:8081/actuator/prometheus ◀── 15초마다 ── prometheus:9090 (내부망 전용, 14일 또는 1GB 보존)
                                                        ▲
브라우저 ── https://<도메인>/grafana/ ── caddy(Basic 인증) ── grafana:3000 (Grafana 로그인) ┘
```

- **Actuator 는 관리 포트 8081** 에만 있다. compose 내부망에서만 닿고(`ports:` 없음), 공개 8080 에는 `/actuator` 가 없으며, Caddy 도 `/actuator*` 를 404 로 막는다. Prometheus(9090)도 게시하지 않는다.
- 대시보드: Grafana 의 **Beside** 폴더 → **Beside 운영**. 변수 `env`(`prod` 운영 / `loadtest` 부하 시험 / `local`), `worker`(mock·real), `창`(드문 작업 지표를 묶는 시간 창). 원본은 [monitoring/grafana/dashboards/beside-ops.json](monitoring/grafana/dashboards/beside-ops.json) 이고 화면에서 고친 것은 저장되지 않는다(JSON 을 고쳐 커밋하면 1분 안에 다시 읽는다).
- `beside_*` 지표는 backend 의 Actuator·Micrometer 코드(같은 W1 의 B1~B3)가 있어야 나온다. 그 전 이미지로는 Prometheus 대상이 DOWN 이고 패널이 비어 있다.

### 메모리 (4GB VM)

| 컨테이너 | 한도 |
|---|---|
| backend | 2g (`mem_limit`, 힙은 그 75 %) |
| prometheus | 384m |
| grafana | 256m |
| caddy · Docker · OS | 한도 없음, 약 0.5GB |
| **합계** | **약 3.2 / 4GB** |

여유가 적으므로 **swap 2GB 를 권장**한다(한 번):

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                                   # Swap: 2.0Gi
```

### 띄우기 · 확인

`deploy/.env` 에 `GRAFANA_ADMIN_PASSWORD`(`openssl rand -base64 24`)를 먼저 넣는다. 비어 있으면 grafana 컨테이너는 이유를 로그에 남기고 뜨지 않는다.

```bash
cd ~/Beside
M="docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile monitoring"
$M up -d                                  # prometheus·grafana 가 새로 뜬다(backend·caddy 는 설정이 같으면 그대로)
$M ps
# Prometheus 가 backend 를 읽는지: beside-backend 의 value 가 "1" 이면 UP
$M exec prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up'
# 관리 포트(내부망)에서 지표가 나오는지
$M exec backend curl -fsS http://localhost:8081/actuator/prometheus | grep -m5 '^beside_'
```

밖(개발 PC)에서:

```bash
D=https://beside-team.duckdns.org
curl -s -o /dev/null -w "%{http_code}\n" $D/grafana/                         # 401 (Caddy Basic 인증)
curl -s -o /dev/null -w "%{http_code}\n" -u 'team:팀 비밀번호' $D/grafana/   # 302 (Grafana 로그인 화면으로)
curl -s -o /dev/null -w "%{http_code}\n" $D/actuator/prometheus              # 404
```

브라우저: `https://<도메인>/grafana/` → Basic 인증(팀 계정) → Grafana 로그인(`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`).

- **monitoring 을 쓰기 시작하면 compose 명령에 늘 `--profile monitoring` 을 붙인다**(`$M`). 붙이지 않은 `up -d` 는 prometheus·grafana 를 멈추지도 고치지도 않는다. [scripts/deploy_vm.sh](../scripts/deploy_vm.sh) 도 프로필을 붙이지 않으므로, `monitoring/prometheus.yml` 이 바뀐 배포 뒤에는 `$M restart prometheus` 를 따로 한다(파일 하나 마운트라 재시작해야 읽는다). 대시보드·데이터소스 폴더는 폴더 마운트라 git pull 만으로 반영된다.
- Grafana 관리자 비밀번호는 **처음 뜰 때(빈 `grafana_data`)만** `.env` 에서 읽는다. 나중에 바꾸려면 `$M exec grafana grafana cli admin reset-admin-password '<새 비밀번호>'`.
- 끄기: `$M stop prometheus grafana`(데이터는 볼륨 `beside_prometheus_data`·`beside_grafana_data` 에 남는다). 지표는 백업 대상이 아니다.
- Prometheus 화면이 필요하면 Grafana 의 **Explore** 에서 같은 PromQL 을 쓴다. 원시 응답은 위처럼 `$M exec prometheus wget -qO- 'http://localhost:9090/api/v1/...'`.

## 운영

### 로그

```bash
cd ~/Beside
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f --tail 100 backend   # Spring
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f --tail 100 caddy     # 접근 로그·인증서
```

컨테이너 로그는 서비스마다 10MB × 5개에서 돈다(compose 의 `x-logging`, json-file `max-size`·`max-file`). 그보다 오래된 로그가 필요하면 그때그때 파일로 꺼내 둔다(`... logs --no-color backend > backend-$(date +%F).log`).

측정 로그는 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env cp backend:/app/storage/events.jsonl ./events.jsonl` 로 꺼낸다(prod 는 H2 콘솔이 꺼져 있다. `job_runs` CSV 내보내기 API 는 S5 — 계약 추가라 검토 브랜치에 있다. 그 전에는 H2 백업 zip 을 개발 PC 로 가져와 h2-console 로 연다).

### 업데이트

개발 PC 에서 `scripts/deploy_vm.sh <user@VM IP> [VM 의 리포 경로, 기본 ~/Beside]` — ssh 로 `git pull --ff-only` → `up -d --build` → (실행 중인 caddy 가 읽는 Caddyfile 이 체크아웃과 다르면 caddy 재시작) → VM 안에서 `https://<도메인>/api/v1/healthz` 확인. VM 에서 직접 하려면 같은 명령을 차례로 친다.

- backend 가 다시 시작되면 그때 PENDING/PROCESSING 이던 작업은 FAILED(`INTERNAL_ERROR`)가 된다(앱은 retry). 아무도 생성 중이 아닐 때 배포한다.
- `deploy/Caddyfile` 은 파일 하나를 마운트하므로 `git pull` 로 바뀌어도 **caddy 를 재시작해야** 반영된다(`... restart caddy`). `deploy_vm.sh` 는 이것을 알아서 한다: 이번 pull 의 변경이 아니라 실행 중인 컨테이너의 `/etc/caddy/Caddyfile` 과 비교하므로, 앞선 실행이 pull 뒤 빌드에서 실패했거나 VM 에서 손으로 pull 한 경우에도 재시작한다.
- 오래된 이미지 정리: `docker image prune -f`. 디스크 확인: `docker system df`, `df -h`.

### 백업

볼륨 `beside-storage` 에 H2 DB·업로드 사진·결과 GLB·`events.jsonl` 이 모두 있다. H2 파일은 서버가 쓰는 중에 복사하면 깨질 수 있으므로 **backend 를 잠깐 멈추고** 묶는다(멈춘 동안 진행 중 작업은 위와 같이 FAILED).

```bash
cd ~/Beside && mkdir -p deploy/backups
C="docker compose -f deploy/docker-compose.yml --env-file deploy/.env"
$C stop backend
docker run --rm -v beside-storage:/data:ro -v "$PWD/deploy/backups:/backup" alpine:3.22 \
  tar czf "/backup/beside-storage-$(date +%F-%H%M).tgz" -C /data .
$C start backend
# 개발 PC 로 가져오기: scp <user@VM>:~/Beside/deploy/backups/*.tgz .
```

복원: `$C stop backend` → `docker run --rm -v beside-storage:/data -v "$PWD/deploy/backups:/backup:ro" alpine:3.22 sh -c 'rm -rf /data/* && tar xzf /backup/<파일>.tgz -C /data'` → `$C start backend`.

- `deploy/backups/` 는 gitignore 다. VM 디스크가 날아가면 같이 사라지므로 중요한 시점(실측 전후)에는 개발 PC 로 옮긴다.
- **[구현됨]** prod 는 서버를 멈추지 않고 매일 04:30(한국 시각, `storage.backup.zone` — 컨테이너는 UTC 로 돈다) H2 온라인 백업(`BACKUP TO`)을 볼륨의 `storage/backup/beside-<시각>.zip` 으로 만들고 최신 7개를 남긴다(`storage.backup.*`). 꺼내기: `$C cp backend:/app/storage/backup ./h2-backup`. 업로드 사진·GLB 까지 묶으려면 위의 tar 절차.

### 부하 시험

VM 에서 k6 부하를 걸 때는 운영 backend 를 내리고 [docker-compose.loadtest.yml](docker-compose.loadtest.yml) 로 부하용 backend(볼륨 `beside-loadtest-storage`, 웹훅·H2 백업 끔, `env=loadtest`)를 대신 띄운 뒤, 끝나면 운영으로 되돌린다. 팀 공지 → 운영 백업 → 띄우기 → 합성 GLB → k6 → 수집 → 복구 → 확인 순서와 중단 조건은 [loadtest/README.md](loadtest/README.md) **[계획]**. 다른 측정 중에는 하지 않는다.

## GPU PC 쪽

추론 서비스를 Tailscale 주소에 바인드하고 토큰을 검사하게 띄운다(업로드 모드 **[구현됨]**, VM 과의 실제 연결은 **[계획]**). WSL 안에서:

```bash
export BESIDE_HOST="$(tailscale ip -4)"                 # uvicorn 바인드 주소 = 이 PC 의 Tailscale IP (0.0.0.0 이 아니다)
export BESIDE_INFERENCE_TOKEN='<deploy/.env 의 INFERENCE_TOKEN 과 같은 값>'
bash /mnt/c/beside/Beside/scripts/start_inference_wsl.sh   # 리포가 C:\beside\Beside 일 때(ANIMALLIFT_SETUP 의 배치). 'model loaded ... on cuda' 를 기다린다
```

VM 에서 `curl http://<GPU PC 100.x>:8001/healthz` 와 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec backend curl -sS http://<GPU PC 100.x>:8001/healthz` 가 둘 다 응답하면 연결된 것이다. GPU PC 를 끄면 VM 은 15초 안에 알아채고(웹훅이 있으면 알림), 새 작업은 다시 켜질 때까지 PENDING 으로 기다린다.

## 폰 앱

앱 코드 변경 없이 주소만 바꾼다: 홈 오른쪽 위 **서버 설정** → `https://<도메인>` 입력 → **연결 확인**(healthz 가 `real,prod`) → **저장**. HTTPS 이므로 개발용 "Allow downloads over HTTP" 가 필요 없다. 주소를 바꾸면 앱의 이전 작업 기록은 지워진다([unity/README.md](../unity/README.md)).

## 문제 해결

| 증상 | 확인 | 조치 |
|---|---|---|
| 브라우저 인증서 경고, caddy 로그에 `could not get certificate` | `dig +short <도메인>` 이 VM IP 인지, 클라우드 방화벽 80·443 | DNS·방화벽을 고치고 `... restart caddy`. `caddy_data` 볼륨을 지우며 반복하지 않는다(Let's Encrypt 실패 횟수 제한) |
| 맞는 비밀번호인데 계속 401 | `... exec caddy printenv BASIC_AUTH_HASH` 가 `$2a$14$` 로 시작하는 완전한 해시인지 | `.env` 에서 작은따옴표로 감싸고 `up -d`(환경변수가 바뀌면 caddy 가 새로 만들어진다) |
| 업로드 413 | 응답 본문이 v1 오류 봉투(`PAYLOAD_TOO_LARGE`)면 Spring(장당 5MB·요청 20MB), 본문이 비었으면 Caddy(25MB) | 사진을 줄인다. 상한을 바꾸려면 Spring 설정과 Caddyfile 을 함께(계약 회의) |
| 502 Bad Gateway | `... ps` 에서 backend 상태, `... logs backend` | 기동 중(첫 30~60초)이면 기다린다. 종료됐으면 로그의 원인(DB 잠김·메모리) |
| 작업이 `INFERENCE_UNAVAILABLE` | GPU PC 가 켜져 있는지, `tailscale status`, VM 호스트와 backend 컨테이너에서 각각 `curl http://<GPU PC 100.x>:8001/healthz` | prod 는 GPU 가 응답하지 않으면 30분 동안 PENDING 으로 기다린 뒤 이 코드로 끝난다(`inference.offline-wait-ms`). 호스트에서는 되는데 컨테이너에서만 안 되면 `docker-compose.yml` 의 backend 주석대로 호스트 네트워크로 옮긴다 |
| `permission denied ... docker.sock` | `groups` 에 docker 가 있는지 | `sudo usermod -aG docker $USER` 뒤 다시 로그인 |
| `required variable ... is missing` | `deploy/.env` 에 그 변수 | 견본(`.env.example`)의 모든 변수를 채운다. `--env-file deploy/.env` 를 빠뜨리지 않는다 |
| grafana 가 계속 재시작, 로그에 `GRAFANA_ADMIN_PASSWORD 를 넣는다` | `deploy/.env` 의 `GRAFANA_ADMIN_PASSWORD` | 값을 넣고 `$M up -d grafana` |
| `/grafana/` 가 Basic 인증 뒤 502 | `$M ps` 에 grafana 가 있는지 | monitoring 프로필을 안 띄웠다: `$M up -d`. 쓰지 않을 거면 그대로 둬도 다른 경로에는 영향 없다 |
| Basic 인증은 통과했는데 Grafana 가 401 · 'invalid username or password' | Caddyfile 의 `/grafana*` reverse_proxy 에 `header_up -Authorization` 이 있는지 | Caddy 가 팀 계정 헤더를 넘기면 Grafana 가 그것을 자기 계정으로 검사한다. 헤더를 지우고 `... restart caddy` |
| 대시보드가 비어 있음 · Prometheus `up` 이 0 | `$M exec backend curl -fsS http://localhost:8081/actuator/prometheus \| head` | 응답이 없으면 그 이미지에 Actuator(관리 포트 8081)가 없다 — 최신 main 으로 다시 빌드. 응답은 있는데 패널만 비면 대시보드 상단 `env` 가 `prod` 인지 |
