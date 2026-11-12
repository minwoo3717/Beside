# deploy — 클라우드 VM 배포 (Docker Compose + Caddy)

백엔드(Spring, profile `real,prod`)를 클라우드 VM 한 대에 Docker Compose 로 올린다. GPU 추론 서비스(FastAPI + AnimalLift)는 **Runpod Pod**(대안: 팀원 GPU PC)에 두고 **Tailscale** 사설망으로 부른다. 팀 내부 테스트용이라 계정·로그인은 없고, 웹·관리 경로만 Caddy **Basic 인증**(팀 공용 계정 하나)으로 막는다. 결정 배경은 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) '배포 가정'.

```text
폰(Unity 앱) ─┐                       ┌──────────── 클라우드 VM (Docker Compose) ────────────┐
브라우저 ─────┴─ HTTPS 443 ─────────▶ │ caddy ──▶ backend:8080 (real,prod) ──▶ 볼륨 beside-storage │
                                      └──────────────────────────────┬─────────────────────────┘
                                                Tailscale 100.x:8001 │  /infer/upload
                                                                     ▼
                      Runpod Pod: tailscaled(userspace) → 127.0.0.1:8001 FastAPI + AnimalLift
                      (대안: 팀원 GPU PC 의 WSL, 서비스를 그 PC 의 100.x 에 바인드)
```

## 상태

| 항목 | 상태 |
|---|---|
| `backend/Dockerfile`, 이 폴더의 `docker-compose.yml`·`Caddyfile`·`.env.example`·`test_caddy_routes.sh`, [scripts/deploy_vm.sh](../scripts/deploy_vm.sh), CI [backend-ci.yml](../.github/workflows/backend-ci.yml) | **[구현됨]** CI(backend-ci)가 main push(`af4a747`, 2026-11-01)와 PR #4(`ad0c3f6`)에서 이미지 빌드·compose/Caddy 검증·경로 시험까지 통과했다. GCP VM 에서 빌드·기동·`deploy_vm.sh`·재부팅 복구를 확인했다(2026-11-13) |
| 환경변수 `INFERENCE_BASEURL` → `inference.base-url` | **[구현됨]** bootJar 를 환경변수로 띄워 확인 |
| 실제 VM·도메인 | **[구현됨]** GCP `beside-vm` + `https://beside-team.duckdns.org`(2026-11-13, 아래 'GCP 메인 서버', 기록 [docs/deploy/2026-11-13_gcp-first-deploy.md](../docs/deploy/2026-11-13_gcp-first-deploy.md)) |
| Tailscale, 폰 → VM → Runpod Pod 1건 | **[계획]** 지금 VM 의 추론은 VM 안 패스스루 스텁이다. Pod 쪽 스크립트 [runpod/start_pod.sh](runpod/start_pod.sh) 는 **[구현됨]** 이지만 WSL 에서 가짜 Tailscale 과 실제 추론 서비스로만 시험했다(아래 'Runpod Pod 쪽'). Runpod 크레딧은 아직 사지 않았다 |
| prod 프로파일(H2 콘솔·Swagger off, forward-headers, 업로드 사진 시그니처 검사) | **[구현됨]** (S1, `ProdProfileTest`). Caddy 의 404·401 규칙은 공개 주소에서 확인했고, 첫 배포 감사에서 나온 우회(끝의 `/`, `/webjars`, 소문자 method, 손님의 `X-Forwarded-Prefix`)를 막았다(2026-11-13). 파일 로그는 두지 않았다 — `docker compose logs` 를 쓴다 |
| `/infer` 업로드 모드(`INFERENCE_TRANSFER=upload`, `INFERENCE_TOKEN`) | **[구현됨]** (S2). VM 의 backend ↔ VM 안 스텁(별도 컨테이너, 토큰)으로 공개 주소에서 전체 흐름 확인. VM ↔ GPU PC 실제 연결은 **[계획]** |
| GPU 오프라인 대기(최대 30분 PENDING), H2 온라인 백업(매일 04:30, 7개), Discord 알림(`NOTIFY_WEBHOOK_URL`) | **[구현됨]** (S3, 테스트로 확인). VM 에서 실제로 돌았는지는 아직 보지 않았다 — **[계획]** |

## GCP 메인 서버 (2026-11-13)

2026-11-12 결정으로 팀 메인 서버는 GCP 무료 체험 크레딧($300, 90일)으로 돌린다([docs/budget/2026-11_purchase-plan.md](../docs/budget/2026-11_purchase-plan.md) §3). **업그레이드(전체 계정 활성화)를 누르지 않으면 청구되지 않는다.** 체험 중에는 GPU 를 붙일 수 없으므로 추론은 GCP 밖의 Runpod Pod 에 두고 Pod 안의 Tailscale 로 잇는다(2026-11-13 결정, 아래 'Runpod Pod 쪽').

| 항목 | 값 |
|---|---|
| 프로젝트 | `beside-514511`(무료 체험 결제 계정에 연결) |
| VM | `beside-vm` — `asia-northeast3-a`(서울), e2-standard-2(2 vCPU · 8GB), Ubuntu 24.04 LTS, 균형 디스크 50GB, 삭제 방지 켬 |
| 주소 | 고정 IP `beside-ip` 34.64.91.254 → `https://beside-team.duckdns.org` |
| 방화벽 | `beside-allow-web`: tcp 80·443, udp 443 → 네트워크 태그 `beside-web`. SSH 22 는 기본 네트워크의 `default-allow-ssh`(키 인증만) |
| SSH | 사용자 `beside`, 개발 PC 의 키 `~/.ssh/beside-vm.key`, `~/.ssh/config` 별칭 `beside-gcp` |
| 코드 | VM 의 `~/Beside` 는 `feat/preprocess`(PR #4). 병합 뒤 `main` 으로 바꾼다 |

**만들기**(다시 만들 때도 같다): 콘솔 오른쪽 위 `>_`(Cloud Shell)에서 [gcp/create_vm.sh](gcp/create_vm.sh) 내용을 `~/create_vm.sh` 로 저장하고 `SSH_PUBKEY='<beside-vm.key.pub 한 줄>' bash ~/create_vm.sh` 를 실행한다(영역 자원이 모자라면 끝에 `asia-northeast3-b`). 이미 있는 리소스는 건너뛰므로 중간에 멈추면 같은 명령을 다시 실행한다. 마지막 줄의 `IP=` 를 DuckDNS 에 넣는다.

**VM 첫 설정**(2026-11-13 에 한 순서):

1. 아래 '1. VM 만들기' 의 Docker 설치. 컨테이너를 만들기 전에 `/etc/docker/daemon.json` 에 로그 회전 `{"log-driver": "json-file", "log-opts": {"max-size": "20m", "max-file": "5"}}` 를 넣는다(기본은 무제한이라 접근 로그가 디스크를 채운다).
2. 스왑 2GB(fstab 포함, '띄우기' 절).
3. `git clone https://github.com/minwoo3717/Beside.git ~/Beside` → `git checkout feat/preprocess` → `scripts/fetch_models.sh backend/models`.
4. `deploy/.env`: `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env`, 도메인, `INFERENCE_TOKEN` 은 VM 에서 `openssl rand -hex 32` 로 만들어 바로 파일에 넣는다(화면에 찍지 않는다).
5. 팀 비밀번호: 개발 PC 에서 `ssh -t beside-gcp 'bash ~/Beside/deploy/set_basic_auth.sh'`([set_basic_auth.sh](set_basic_auth.sh) — 두 번 묻고 해시만 `.env` 에 넣는다. 비밀번호를 바꿀 때도 같다). 이 파일이 VM 의 체크아웃에 들어오기 전에는 같은 일을 하는 VM 의 `~/bin/beside-set-password` 를 쓴다(`ssh -t beside-gcp '~/bin/beside-set-password'`).
6. `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build` → 밖에서 '확인' 절.

**지금 상태(2026-11-13)**: 공개 주소에서 HTTPS·Basic 인증·404 규칙·전체 흐름(스텁)·재부팅 복구·`deploy_vm.sh beside-gcp` 를 확인했다. 수치와 보안 감사 결과는 [docs/deploy/2026-11-13_gcp-first-deploy.md](../docs/deploy/2026-11-13_gcp-first-deploy.md). 감사 뒤 강화한 `deploy/Caddyfile` 은 VM 의 `~/Beside` 에 **로컬 수정**으로 들어가 있다(그 커밋이 원격에 올라가면 `git checkout deploy/Caddyfile` 뒤 pull). Tailscale·Runpod Pod 연결은 **[계획]**(Runpod 크레딧 구매 뒤).

**남은 GCP 쪽 정리(Cloud Shell, 선택)**: 지금 VM 은 기본 서비스 계정을 달고 있어 컨테이너가 메타데이터 서버에서 GCP 토큰을 받을 수 있다(`create_vm.sh` 는 이제 서비스 계정 없이 만든다). 바꾸려면 VM 을 잠깐 멈춰야 한다(약 1분 중단, 컨테이너는 스스로 다시 뜬다). 기본 네트워크가 만든 RDP 규칙도 필요 없다.

```bash
gcloud compute instances stop beside-vm --zone=asia-northeast3-a
gcloud compute instances set-service-account beside-vm --zone=asia-northeast3-a --no-service-account --no-scopes
gcloud compute instances start beside-vm --zone=asia-northeast3-a
gcloud compute firewall-rules delete default-allow-rdp
```

**GPU 없이 먼저 전체 흐름 확인(스텁)**: 리포의 추론 서비스를 패스스루 모드로 VM 안에 띄우고 `INFERENCE_BASE_URL=http://beside-stub:8001` 로 둔다. 이미지는 `python:3.12-slim` + `generation/inference_service/requirements.txt`, 앱 코드는 읽기 전용 마운트, 샘플 GLB 는 **유효한 GLB** 여야 한다(리포의 `backend/storage/results/sample-dog.glb` 는 0바이트라 쓸 수 없다). `--network beside_default`, 포트는 게시하지 않고, `BESIDE_INFERENCE_TOKEN` 은 `.env` 의 `INFERENCE_TOKEN` 과 같게(권한 600 인 env 파일로 넘긴다). Runpod Pod 를 연결하면 `INFERENCE_BASE_URL` 을 바꾸고 `docker rm -f beside-stub`.

**주의 — compose 프로젝트 이름이 `beside` 로 고정돼 있다.** 그래서 compose 파일을 다른 폴더(예: `/tmp`)에 복사해 `docker compose -f <복사본> ...` 을 실행해도 지금 떠 있는 서버 컨테이너를 그 설정으로 다시 만든다. 2026-11-13 에 시험용 복사본으로 이렇게 해서 서버가 약 1분 내려갔다(복구: `~/Beside` 에서 `up -d`). 서버를 건드리는 명령은 `~/Beside` 에서만 하고, VM 에서 무언가를 시험할 때는 `name:` 을 바꾼 별도 프로젝트로 한다.

**비용과 끝내기**: 결제 → 예산 및 알림에서 30·60·90% 알림을 받는다. 추정은 하루 약 $2.4(VM + 디스크 + 고정 IP, 12/21 까지 약 $100 / $300)이고 실제 청구는 결제 → 보고서에서 확인한다 **[계획]**. 최종 보고 뒤(12/21 무렵) '백업' 절대로 묶어 개발 PC 로 옮기고 Cloud Shell 에서:

```bash
gcloud compute instances update beside-vm --zone=asia-northeast3-a --no-deletion-protection
gcloud compute instances delete beside-vm --zone=asia-northeast3-a
gcloud compute addresses delete beside-ip --region=asia-northeast3   # VM 만 지우고 IP 를 남기면 IP 요금이 계속 붙는다
```

## 경로 규칙 (Caddyfile)

| 처리 | 경로 |
|---|---|
| **404** (없는 것처럼) | `/api/jobs`, `/api/jobs/*`(v0 동결 API — 서버 경로를 노출한다), `/h2-console*`, `/swagger*`, `/v3/api-docs*`, `/webjars*`(prod 에서도 Swagger UI 묶음이 나간다), 경로에 `;` 가 든 모든 요청 |
| **Basic 인증** | `/`, `/index.html*`, `/app.js*`(Mock 업로드 UI — 끝에 `/` 를 붙인 경로도), `/admin*`, `/library*`, `/job*`(앞으로 생길 웹 화면), `/api/v1/jobs`·`/api/v1/jobs/` 의 **POST 가 아닌** 모든 method(목록 등), `/api/v1/jobs/*` 의 **GET·HEAD·POST 가 아닌** 모든 method(삭제 등), `/api/v1/jobs/*/pet*`, `/api/v1/admin*`, `/api/v1/pets*` |
| **공개** | 그 밖 전부: 앱 API(`POST /api/v1/jobs`, `GET`·`HEAD /api/v1/jobs/{id}`·`/asset`, `POST /retry`), `/api/v1/healthz`, 공유 링크(`/s/*`, `/share.*`, `/api/v1/shares/*`), `styles.css` 같은 정적 파일 |
| **413** | 요청 본문 25MB 초과(Caddy). 20MB·장당 5MB 초과는 Spring 이 v1 오류 봉투(`PAYLOAD_TOO_LARGE`)로 413 |
| **모든 응답** | `Strict-Transport-Security: max-age=15552000`(180일, 하위 도메인 없이), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`. 손님이 보낸 `Forwarded`·`X-Forwarded-Prefix`·`-Port`·`-Ssl` 은 Spring 에 넘기기 전에 지운다(`X-Forwarded-For`·`-Proto`·`-Host` 는 Caddy 가 새로 쓴다) |

- `;` 를 막는 이유: Tomcat 은 `/api/v1/jobs;x=1`, `/api;x/v1/jobs`, `/api/jobs/{id};x` 를 `;` 뒤를 뗀 경로로 처리한다(로컬 real 서버에서 셋 다 200 을 확인). 열어 두면 인증·404 규칙을 비켜 간다.
- Caddy 의 path 비교는 대소문자를 가리지 않고, 퍼센트 인코딩을 풀고 `//`·`..` 를 정리한 뒤에 한다. `caddy:2.11.1` 미만은 이 정리에 우회 취약점(CVE-2026-27587)이 있으므로 이미지를 내리지 않는다.
- 규칙을 바꾸면 [test_caddy_routes.sh](test_caddy_routes.sh) 의 기대값도 고친다. Linux + Docker 에서 `bash deploy/test_caddy_routes.sh` 로 가짜 백엔드 앞에 같은 Caddyfile 을 띄워 경로마다 상태 코드를 확인한다(CI 도 같은 명령).
- 공개 경로의 작업은 UUID 를 아는 사람만 볼 수 있다는 가정이다(계정 없음). 목록 API 를 막는 이유가 이것이다.
- 파일 이름도 접두사(`/index.html*`)로 맞추는 이유: Caddy 는 `//` 와 `.` 조각은 정리하지만 끝의 `/` 는 남기고, Spring 은 `/index.html/` 을 그 파일로 내준다. 2026-11-13 첫 배포 감사에서 예전 규칙(정확히 맞추기)으로 Mock UI 가 인증 없이 200 이었다. method 도 "GET 이면 인증" 이 아니라 "POST 가 아니면 인증" 으로 쓴다(method 비교는 대소문자를 가린다).

## 직접 할 일 (한 번)

### 1. VM 만들기

- Ubuntu 24.04 LTS, **2 vCPU · 4GB RAM · 30GB 디스크** 권장(backend 컨테이너 한도 3GB — 전처리 모델 포함 — + Caddy + 첫 Gradle 빌드, 스왑 2GB 권장). 공인 IP 는 고정(예약) IP 로 받는다 — 도메인이 이 IP 를 가리킨다.
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

### 3. Tailscale (VM 과 GPU 쪽을 한 사설망에)

```bash
# VM
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --hostname=beside-vm   # 출력된 링크로 팀 tailnet 에 로그인
tailscale ip -4                          # VM 의 100.x 주소
```

- Runpod Pod: 아래 'Runpod Pod 쪽'. Pod 안에서 스크립트가 Tailscale 을 받아 띄우고 로그인 링크를 한 번 보여 준다.
- GPU PC(대안): 추론 서비스가 **WSL2 안**에서 돌면 Tailscale 도 **WSL 안**에 설치하고 로그인한다(위와 같은 명령. WSL 에 systemd 가 꺼져 있으면 `/etc/wsl.conf` 에 `[boot]` `systemd=true` 를 넣고 PowerShell 에서 `wsl --shutdown` 뒤 다시 연다). 그 WSL 의 `tailscale ip -4` 가 `.env` 의 `INFERENCE_BASE_URL` 과 GPU PC 쪽 `BESIDE_HOST` 가 된다.
- 연결 확인: VM 에서 `tailscale ping <Pod 또는 GPU PC 의 100.x>`.
- (선택) Tailscale 관리 콘솔의 Access controls 에서 VM → GPU 쪽:8001 만 허용하면 tailnet 안의 다른 기기는 추론 서비스(와 Pod 의 다른 포트)에 못 붙는다.

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
| `INFERENCE_BASE_URL` | `http://<Runpod Pod(또는 GPU PC)의 Tailscale IP>:8001` |
| `INFERENCE_TOKEN` | 긴 무작위 문자열(`openssl rand -hex 32`). GPU 쪽 `BESIDE_INFERENCE_TOKEN` 과 같게 |
| `NOTIFY_WEBHOOK_URL` | (선택) Discord 웹훅 URL. 작업 완성·실패와 GPU 서버 연결/끊김을 팀 채널에 알린다. 비우면 끔 |

**해시의 `$` 처리**: docker compose v2 는 `.env` 의 따옴표 없는 값과 큰따옴표 값에서 `$이름`·`${이름}` 을 변수로 바꾸고, **작은따옴표 값은 글자 그대로** 쓴다(Docker 문서 'Interpolation — .env file syntax'). bcrypt 해시(`$2a$14$...`)를 따옴표 없이 넣으면 `$14...` 같은 부분이 빈 값으로 바뀌어 로그인이 영영 안 된다. `$$` 는 compose **YAML 파일 안**에서 `$` 를 쓰는 방법이고 `.env` 에서는 쓰지 않는다. 들어간 값은 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec caddy printenv BASIC_AUTH_HASH` 로 확인한다(CI 도 견본 파일로 같은 확인을 한다).

## 띄우기

전처리 모델(대표 사진 검사·크롭/배경 제거, 약 210 MB)을 먼저 받는다. `deploy_vm.sh` 는 이 단계를 스스로 한다. `deploy/.env` 의 `BESIDE_MODELS_DIR` 를 비우면 리포의 `backend/models` 를 쓴다.

```bash
scripts/fetch_models.sh backend/models        # 또는 BESIDE_MODELS_DIR 에 적은 절대 경로. 해시가 맞으면 다시 받지 않는다
```

backend 컨테이너는 이 모델 때문에 메모리 한도가 3 GB 다(힙 40% + ONNX Runtime). 4 GB VM 에서 이미지를 다시 빌드하는 동안 메모리가 모자랄 수 있으니 스왑 2 GB 를 만들어 둔다(`sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`, 재부팅 뒤에도 쓰려면 `/etc/fstab` 에 `/swapfile none swap sw 0 0`). 실제 사용량(`docker stats`)은 **[계획]** 이다. 전처리를 끄려면 `deploy/.env` 에 `PREPROCESS_CHECK_ENABLED=false`·`PREPROCESS_CUTOUT_ENABLED=false`.

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
for p in /h2-console /swagger-ui.html /v3/api-docs /api/jobs '/api/v1/jobs;x=1'; do
  curl -s -o /dev/null -w "%{http_code} $p\n" "$D$p"            # 모두 404
done
curl -s -o /dev/null -w "%{http_code}\n" $D/api/v1/jobs           # 401
curl -s -o /dev/null -w "%{http_code}\n" -u 'team:팀 비밀번호' $D/api/v1/jobs   # 200
TIMEOUT_SECONDS=600 scripts/e2e_mock.sh $D ~/photos/dog1.jpg     # 전체 흐름. GPU PC 가 꺼져 있으면 작업은 PENDING 으로 기다린다(최대 30분)
```

브라우저로 `https://<도메인>/` 을 열면 Basic 인증 창이 뜨고, 로그인하면 Mock 업로드 UI 가 보인다.

## 운영

### 로그

```bash
cd ~/Beside
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f --tail 100 backend   # Spring
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f --tail 100 caddy     # 접근 로그·인증서
```

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

## Runpod Pod 쪽

2026-11-13 결정: GPU 추론은 Runpod Pod 에서 하고, VM 과는 **Pod 안에 띄운 Tailscale(userspace 모드)** 로 잇는다.

| 방법 | 판단 |
|---|---|
| Runpod HTTP 프록시(`https://<pod>-8001.proxy.runpod.net`) | 쓰지 않는다. 요청을 100초에서 끊는다(Runpod 문서). Spring 은 `/infer/upload` 를 최대 600초(`inference.timeout-ms`) 기다리고, 실제 생성 시간은 아직 재지 않았다(exp-01) |
| Pod 의 TCP 포트 공개 + 토큰 | 쓰지 않는다. 사진과 토큰이 암호화 없이 인터넷을 지나고, Pod 를 다시 켜면 외부 포트가 바뀌어 그때마다 `.env` 를 고치고 backend 를 다시 띄워야 한다 |
| **Pod 안 Tailscale** | **고름.** 암호화되고 공개 포트가 없다. 노드 상태를 `/workspace` 에 두면 Pod 를 껐다 켜도 같은 100.x 로 돌아오고, 600초 요청도 그대로 지나간다. 처음 한 번 로그인 링크 승인이 필요하다 |

**동작 방식**: Pod 는 컨테이너라 TUN 장치가 없어서 `tailscaled --tun=userspace-networking` 으로 띄운다. 이 모드는 자기 100.x 로 들어온 TCP 를 `127.0.0.1` 의 같은 포트로 넘긴다(Tailscale 소스 `wgengine/netstack` 의 `acceptTCP` 에서 확인). 그래서 추론 서비스는 `127.0.0.1:8001` 에만 열고, Pod 의 공개 포트와 HTTP 프록시에는 아무것도 내놓지 않는다.

**처음 한 번** (Runpod 크레딧을 산 뒤):

1. Tailscale 계정(무료)을 만든다. VM 과 Pod 를 같은 tailnet 에 넣을 계정이다.
2. VM: 위 '3. Tailscale' 의 명령을 실행하고 출력된 링크를 승인한다.
3. Pod 를 만든다. 연결 시험만이면 CPU Pod 로 충분하다(패스스루). GPU 종류와 이미지는 3D 트랙이 정한다. `/workspace`(Volume disk)를 두고, 포트는 더 열지 않는다. 정지하면 컨테이너 디스크는 지워지고 `/workspace` 는 남는다. Terminate 하면 `/workspace` 도 지워진다(Runpod 문서 'Storage types').
4. Pod 웹 터미널이나 SSH 에서 아래를 실행한다. 브랜치는 이 스크립트가 들어 있는 것(지금 `deploy/gcp`, 병합 뒤 `main`)이다. 토큰은 개발 PC 에서 `ssh beside-gcp "grep ^INFERENCE_TOKEN= ~/Beside/deploy/.env"` 로 꺼내 Pod 터미널에만 붙인다.

   ```bash
   git clone -b <브랜치> https://github.com/minwoo3717/Beside.git /workspace/Beside
   BESIDE_INFERENCE_TOKEN='<VM deploy/.env 의 INFERENCE_TOKEN>' bash /workspace/Beside/deploy/runpod/start_pod.sh
   ```

5. 첫 실행은 Tailscale 1.104.6 을 `/workspace/tailscale` 에 받고(sha256 고정), 로그인 링크를 한 번 보여 준다. 승인하면 마지막에 `INFERENCE_BASE_URL=http://100.x.y.z:8001` 이 나온다.
6. VM: `deploy/.env` 의 `INFERENCE_BASE_URL` 을 그 값으로 바꾸고 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d backend` 를 실행한다. 확인은 아래 'GPU PC 쪽' 마지막 문단의 `curl` 두 줄(주소만 Pod 의 100.x)로 한다. 실제 사진 1건이 끝나면 `docker rm -f beside-stub` 를 실행한다.

**Pod 를 다시 켤 때마다** 4번의 둘째 명령만 다시 실행한다. 노드 상태가 `/workspace/tailscale` 에 남아 있어서 로그인 없이 같은 100.x 로 돌아오므로 VM `.env` 는 그대로 둔다. 이미 떠 있는 것은 다시 띄우지 않는다.

**Pod 를 Terminate 했을 때**는 새 노드가 된다. 다시 로그인하고, Tailscale 관리 콘솔에서 옛 `beside-gpu` 노드를 지운 뒤(남겨 두면 새 노드 이름에 번호가 붙는다), VM 의 `INFERENCE_BASE_URL` 을 새 주소로 바꾼다.

| `MODE` | 하는 일 |
|---|---|
| `passthrough`: `/workspace/AnimalLift` 가 없을 때의 기본값 | `/workspace/beside-venv` 에 `requirements.txt` 를 설치하고 고정 GLB 를 돌려준다. 연결 시험용이다. `/workspace/beside-sample.glb` 가 없으면 삼각형 하나짜리 GLB 를 만든다. 진짜 강아지를 보이려면 그 자리에 GLB 를 올려 둔다 |
| `model`: `/workspace/AnimalLift` 가 있을 때의 기본값 | `scripts/start_inference_wsl.sh` 로 AnimalLift 를 띄운다. conda(`/workspace/miniconda3`, env `animallift`)·AnimalLift·체크포인트·리깅 템플릿(`/workspace/beside-rig/template-rigged.glb`)은 모두 `/workspace` 아래에 있어야 정지 뒤에도 남는다. 설치는 [ANIMALLIFT_SETUP.md](../generation/inference_service/ANIMALLIFT_SETUP.md)(3D 트랙) |

파일 위치:

| 경로 | 내용 |
|---|---|
| `/workspace/tailscale/` | 바이너리와 노드 상태(노드 키, 권한 700) |
| `/workspace/beside-logs/` | `tailscaled.log`, `inference.log` |
| `/workspace/beside-work/` | 작업 폴더 |
| `/var/run/beside/` | 소켓과 pid. 정지하면 사라진다 |

**확인한 것**

- **[구현됨]** WSL(Ubuntu 26.04)에서 가짜 `tailscale`·`tailscaled`(네트워크에 나가지 않음)와 실제 추론 서비스(패스스루 uvicorn)로 45개 검사가 모두 통과했다.
  - 거부: 토큰 없음·빈 토큰·잘못된 `MODE`.
  - Tailscale 실행: userspace·statedir·소켓 플래그, 로그인 링크는 첫 실행에만.
  - 서비스: `127.0.0.1` 에만 바인드, 토큰이 없거나 틀리면 401, 업로드한 결과가 샘플 GLB 와 같음.
  - 다시 실행: 이미 떠 있으면 두 번 띄우지 않음, 재시작을 흉내 낸 경우(프로세스 없음, 낡은 pid 파일) 로그인 없이 다시 뜸.
  - `model` 모드의 환경 변수(가짜 conda).
  - 실패 처리: 포트가 막혔거나 다른 서버가 이미 응답하면 실패로 끝남.
  - 상태 폴더 권한 700.
- **[구현됨]** 실제 Tailscale 1.104.6 tarball: sha256 이 틀리면 설치하지 않는다. 받기와 설치가 되고, 스크립트가 쓰는 플래그가 실제 CLI 에 모두 있다.
- **[계획]** 실제 Pod 에서 실행, VM ↔ Pod 전체 흐름 1건, Pod 재시작 뒤 같은 100.x 유지, `model` 모드 실측.

## GPU PC 쪽 (대안)

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
| 작업이 `INFERENCE_UNAVAILABLE` | Pod(또는 GPU PC)가 켜져 있는지, Pod 를 다시 켠 뒤 `start_pod.sh` 를 실행했는지, `tailscale status`, VM 호스트와 backend 컨테이너에서 각각 `curl http://<GPU 쪽 100.x>:8001/healthz` | prod 는 GPU 가 응답하지 않으면 30분 동안 PENDING 으로 기다린 뒤 이 코드로 끝난다(`inference.offline-wait-ms`). 호스트에서는 되는데 컨테이너에서만 안 되면 `docker-compose.yml` 의 backend 주석대로 호스트 네트워크로 옮긴다 |
| `permission denied ... docker.sock` | `groups` 에 docker 가 있는지 | `sudo usermod -aG docker $USER` 뒤 다시 로그인 |
| `required variable ... is missing` | `deploy/.env` 에 그 변수 | 견본(`.env.example`)의 모든 변수를 채운다. `--env-file deploy/.env` 를 빠뜨리지 않는다 |
