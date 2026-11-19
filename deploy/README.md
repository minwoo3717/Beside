# deploy — 클라우드 VM 배포 (Docker Compose + Caddy)

백엔드(Spring, profile `real,prod`)를 클라우드 VM 한 대에 Docker Compose 로 올린다. GPU 추론 서비스(FastAPI + AnimalLift)는 **Runpod Pod**(대안: 팀원 GPU PC)에 두고 **Tailscale** 사설망으로 부른다. 팀 내부 테스트용이라 계정·로그인은 없고, 웹·관리 경로만 Caddy **Basic 인증**(팀 공용 계정 하나)으로 막는다. 결정 배경은 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) '배포 가정'.

```text
폰(Unity 앱) ─┐                       ┌──────────── 클라우드 VM (Docker Compose) ────────────┐
브라우저 ─────┴─ HTTPS 443 ─────────▶ │ caddy ──▶ backend:8080 (real,prod) ──▶ 볼륨 beside-storage │
                                      └──────────────────────────────┬─────────────────────────┘
                                                Tailscale 100.x:8002 │  /infer/upload
                                                                     ▼
                      Runpod Pod: tailscaled(userspace) → 127.0.0.1:8002 FastAPI + AnimalLift
                      (대안: 팀원 GPU PC 의 WSL, 서비스를 그 PC 의 100.x:8001 에 바인드)
```

## 상태

| 항목 | 상태 |
|---|---|
| `backend/Dockerfile`, 이 폴더의 `docker-compose.yml`·`Caddyfile`·`.env.example`·`test_caddy_routes.sh`, [scripts/deploy_vm.sh](../scripts/deploy_vm.sh), CI [backend-ci.yml](../.github/workflows/backend-ci.yml) | **[구현됨]** CI(backend-ci)가 main push(`af4a747`, 2026-11-01)와 PR #4(`ad0c3f6`)에서 이미지 빌드·compose/Caddy 검증·경로 시험까지 통과했다. GCP VM 에서 빌드·기동·`deploy_vm.sh`·재부팅 복구를 확인했다(2026-11-13) |
| 환경변수 `INFERENCE_BASEURL` → `inference.base-url` | **[구현됨]** bootJar 를 환경변수로 띄워 확인 |
| 실제 VM·도메인 | **[구현됨]** GCP `beside-vm` + `https://beside-team.duckdns.org`(2026-11-13, 아래 'GCP 메인 서버', 기록 [docs/deploy/2026-11-13_gcp-first-deploy.md](../docs/deploy/2026-11-13_gcp-first-deploy.md)) |
| Tailscale, 공개 주소 → VM → Runpod Pod 1건 | **[구현됨]** 2026-11-17: VM·Pod 를 Tailscale 로 잇고 Pod 의 패스스루 서비스로 사진 1건 전체 흐름(개발 PC 에서, 합계 7.3 s, 기록 [docs/deploy/2026-11-17_runpod-connect.md](../docs/deploy/2026-11-17_runpod-connect.md)). 같은 날 Pod 에 AnimalLift 를 설치해(`runpod/install_animallift.sh`) 실제 모델로 3건 성공(모델 4~6 s, 전체 12~16 s, [exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)). 그날 VM ↔ Pod 는 DERP 중계(왕복 약 300 ms)였고, 2026-11-18·19 2·3차 부하 시험 때는 직접 연결(`tailscale ping` 263~269 ms)이었다 **[실험 결과]**. 날마다 바뀔 수 있다. 폰 앱 시험은 **[계획]** |
| prod 프로파일(H2 콘솔·Swagger off, forward-headers, 업로드 사진 시그니처 검사) | **[구현됨]** (S1, `ProdProfileTest`). Caddy 의 404·401 규칙은 공개 주소에서 확인했고, 첫 배포 감사에서 나온 우회(끝의 `/`, `/webjars`, 소문자 method, 손님의 `X-Forwarded-Prefix`)를 막았다(2026-11-13). 파일 로그는 두지 않았다 — `docker compose logs` 를 쓴다 |
| `/infer` 업로드 모드(`INFERENCE_TRANSFER=upload`, `INFERENCE_TOKEN`) | **[구현됨]** (S2). VM 의 backend ↔ VM 안 스텁(별도 컨테이너, 토큰), 그다음 VM ↔ Runpod Pod(Tailscale, 2026-11-17)로 공개 주소에서 전체 흐름 확인 |
| GPU 오프라인 대기(최대 30분 PENDING), H2 온라인 백업(매일 04:30, 7개), Discord 알림(`NOTIFY_WEBHOOK_URL`, 서버 이름 `NOTIFY_INFERENCE_LABEL`, prod 는 45초 넘은 끊김만) | **[구현됨]** (S3, 테스트로 확인). VM 에서: 백업은 2026-11-14~17 매일 zip 이 생겼다. Discord 는 2026-11-18 웹훅을 넣고 Pod 를 멈춘 상태로 backend 를 다시 띄워 끊김 메시지 1건이 오류 없이 나갔다(서버 이름·45초 유예는 2026-11-18 VM 리포를 main `b6c0e6c` 로 바꿔 배포한 뒤부터다. 서버 이름이 든 메시지를 확인한 기록은 아직 없다. 같은 날 부하 시험 뒤 운영 복구 때 Pod 가 꺼져 있어 `UNKNOWN -> DOWN` 이 backend 로그에 남았다). 오프라인 대기 중 작업이 이어지는 것은 2026-11-19 3차 부하 시험(같은 VM, 부하 덮어쓰기 backend `real,prod`, 웹훅 끔)의 계획 밖 경로 끊김 두 번과 F2-real 에서 확인했다 **[실험 결과]**([docs/perf/load/2026-11-19_vm-3rd.md](../docs/perf/load/2026-11-19_vm-3rd.md) 5·8절). 운영 backend 에서 본 기록은 아직 없다 |
| 모니터링(compose `monitoring` 프로필: Prometheus + Grafana 대시보드 'Beside 운영'), Caddy `/actuator*` 404·`/grafana*` 인증, 컨테이너 로그 회전 | 파일 **[구현됨]**(대시보드 JSON 은 `json.tool` 로 형식만 확인) · compose 설정 검증(`monitoring` 프로필·부하 덮어쓰기)과 경로 시험(`/actuator*` 404, `/grafana*` 401)은 CI(PR #3, main `18c345f`, 2026-11-13)에서 통과 **[구현됨]** · Prometheus 는 2026-11-18 부하 시험 때 VM 에서 띄워 Spring 지표를 수집했다 **[실험 결과]**(시험 뒤 다시 멈춤). Grafana 를 실제로 띄워 보는 것은 **[계획]**. 아래 '모니터링' |
| 부하 시험 덮어쓰기(`docker-compose.loadtest.yml`: backend 만 부하용 볼륨·mock 설정으로 바꿈)와 절차 | 파일 **[구현됨]** · VM 실행 1차(A0~A4, mock) 2026-11-18, 2차(실제 A40, G0r·G1r·G3r) 2026-11-18, 3차(G2r·F2-real·F1b-real·F1a) 2026-11-19 **[실험 결과]** — [loadtest/README.md](loadtest/README.md), [1차](../docs/perf/load/2026-11-18_vm-1st.md)·[2차](../docs/perf/load/2026-11-18_vm-2nd.md)·[3차](../docs/perf/load/2026-11-19_vm-3rd.md) |

## GCP 메인 서버 (2026-11-13)

2026-11-12 결정으로 팀 메인 서버는 GCP 무료 체험 크레딧($300, 90일)으로 돌린다(팀 내부 재료비 구매 계획 §3 — 리포에는 올리지 않은 문서). **업그레이드(전체 계정 활성화)를 누르지 않으면 청구되지 않는다.** 체험 중에는 GPU 를 붙일 수 없으므로 추론은 GCP 밖의 Runpod Pod 에 두고 Pod 안의 Tailscale 로 잇는다(2026-11-13 결정, 아래 'Runpod Pod 쪽').

| 항목 | 값 |
|---|---|
| 프로젝트 | `beside-514511`(무료 체험 결제 계정에 연결) |
| VM | `beside-vm` — `asia-northeast3-a`(서울), e2-standard-2(2 vCPU · 8GB), Ubuntu 24.04 LTS, 균형 디스크 50GB, 삭제 방지 켬 |
| 주소 | 고정 IP `beside-ip` 34.64.91.254 → `https://beside-team.duckdns.org` |
| 방화벽 | `beside-allow-web`: tcp 80·443, udp 443 → 네트워크 태그 `beside-web`. SSH 22 는 기본 네트워크의 `default-allow-ssh`(키 인증만) |
| SSH | 사용자 `beside`, 개발 PC 의 키 `~/.ssh/beside-vm.key`, `~/.ssh/config` 별칭 `beside-gcp` |
| 코드 | VM 의 `~/Beside` 는 `main`(2026-11-19 PR #6~#11 병합 뒤 `1c87da5`, 11:04 UTC 에 `scripts/deploy_vm.sh` 로 backend 다시 배포). 추론 서비스 변경(PR #11)은 VM 이 아니라 Pod 에서 돈다. 감사 뒤 강화한 Caddyfile 은 PR #5 로 main 에 들어갔고 VM 의 로컬 수정은 되돌렸다(아래 '지금 상태'의 절차) |

**만들기**(다시 만들 때도 같다): 콘솔 오른쪽 위 `>_`(Cloud Shell)에서 [gcp/create_vm.sh](gcp/create_vm.sh) 내용을 `~/create_vm.sh` 로 저장하고 `SSH_PUBKEY='<beside-vm.key.pub 한 줄>' bash ~/create_vm.sh` 를 실행한다(영역 자원이 모자라면 끝에 `asia-northeast3-b`). 이미 있는 리소스는 건너뛰므로 중간에 멈추면 같은 명령을 다시 실행한다. 마지막 줄의 `IP=` 를 DuckDNS 에 넣는다.

**VM 첫 설정**(2026-11-13 에 한 순서):

1. 아래 '1. VM 만들기' 의 Docker 설치. 컨테이너를 만들기 전에 `/etc/docker/daemon.json` 에 로그 회전 `{"log-driver": "json-file", "log-opts": {"max-size": "20m", "max-file": "5"}}` 를 넣는다(기본은 무제한이라 접근 로그가 디스크를 채운다).
2. 스왑 2GB(fstab 포함, '띄우기' 절).
3. `git clone https://github.com/minwoo3717/Beside.git ~/Beside` → `main`(2026-11-13 에는 `feat/preprocess` 였다. Caddy 강화가 든 `deploy/gcp` PR 이 병합되기 전에 다시 만든다면 `deploy/gcp`) → `scripts/fetch_models.sh backend/models`.
4. `deploy/.env`: `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env`, 도메인, `INFERENCE_TOKEN` 은 VM 에서 `openssl rand -hex 32` 로 만들어 바로 파일에 넣는다(화면에 찍지 않는다).
5. 팀 비밀번호: 개발 PC 에서 `ssh -t beside-gcp 'bash ~/Beside/deploy/set_basic_auth.sh'`([set_basic_auth.sh](set_basic_auth.sh) — 두 번 묻고 해시만 `.env` 에 넣는다. 비밀번호를 바꿀 때도 같다). 이 파일이 VM 의 체크아웃에 들어오기 전에는 같은 일을 하는 VM 의 `~/bin/beside-set-password` 를 쓴다(`ssh -t beside-gcp '~/bin/beside-set-password'`).
6. `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build` → 밖에서 '확인' 절.

**지금 상태(2026-11-13)**: 공개 주소에서 HTTPS·Basic 인증·404 규칙·전체 흐름(스텁)·재부팅 복구·`deploy_vm.sh beside-gcp` 를 확인했다. 수치와 보안 감사 결과는 [docs/deploy/2026-11-13_gcp-first-deploy.md](../docs/deploy/2026-11-13_gcp-first-deploy.md). 감사 뒤 강화한 `deploy/Caddyfile` 은 VM 의 `~/Beside` 에 **로컬 수정**으로 들어가 있다. 이 강화는 `deploy/gcp` → `main` PR 로만 들어가고 `feat/preprocess` 에서 pull 해도 오지 않으므로, PR 이 병합되기 전에는 VM 의 Caddyfile 을 되돌리지 않는다. 병합 뒤 VM 에서 `git checkout deploy/Caddyfile && git checkout main && git pull --ff-only`, 그다음 개발 PC 에서 `scripts/deploy_vm.sh beside-gcp`(main 의 compose 변경으로 컨테이너가 다시 만들어진다). 2026-11-17 에 VM 에 Tailscale 을 설치하고(apt 저장소, `beside-vm` 100.98.76.5) `INFERENCE_BASE_URL` 을 Runpod Pod(100.83.86.62:8002)로 바꿨다. 이전 값은 `deploy/.env.bak-20261117-stub` 에 있고 스텁 컨테이너 `beside-stub` 는 되돌릴 때를 위해 남겨 두었다.

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
| **404** (없는 것처럼) | `/api/jobs`, `/api/jobs/*`(v0 동결 API — 서버 경로를 노출한다), `/h2-console*`, `/swagger*`, `/v3/api-docs*`, `/webjars*`(prod 에서도 Swagger UI 묶음이 나간다), `/actuator*`(Actuator 는 관리 포트 8081 에만 있다 — 두 겹), 경로에 `;` 가 든 모든 요청 |
| **Basic 인증** | `/`, `/index.html*`, `/app.js*`(Mock 업로드 UI — 끝에 `/` 를 붙인 경로도), `/admin*`, `/library*`, `/job*`(앞으로 생길 웹 화면), `/grafana*`(→ Grafana, 그다음 Grafana 로그인), `/api/v1/jobs`·`/api/v1/jobs/` 의 **POST 가 아닌** 모든 method(목록 등), `/api/v1/jobs/*` 의 **GET·HEAD·POST 가 아닌** 모든 method(삭제 등), `/api/v1/jobs/*/pet*`, `/api/v1/admin*`, `/api/v1/pets*` |
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
sudo tailscale up --hostname=beside-vm --accept-dns=false   # 출력된 링크로 팀 tailnet 에 로그인. DNS 는 GCP 것을 그대로
tailscale ip -4                          # VM 의 100.x 주소
```

- Runpod Pod: 아래 'Runpod Pod 쪽'. Pod 안에서 스크립트가 Tailscale 을 받아 띄우고 로그인 링크를 한 번 보여 준다.
- GPU PC(대안): 추론 서비스가 **WSL2 안**에서 돌면 Tailscale 도 **WSL 안**에 설치하고 로그인한다(위와 같은 명령. WSL 에 systemd 가 꺼져 있으면 `/etc/wsl.conf` 에 `[boot]` `systemd=true` 를 넣고 PowerShell 에서 `wsl --shutdown` 뒤 다시 연다). 그 WSL 의 `tailscale ip -4` 가 `.env` 의 `INFERENCE_BASE_URL` 과 GPU PC 쪽 `BESIDE_HOST` 가 된다.
- 연결 확인: VM 에서 `tailscale ping <Pod 또는 GPU PC 의 100.x>`.
- (선택) Tailscale 관리 콘솔의 Access controls 에서 VM → GPU 쪽 추론 포트(Pod 8002, GPU PC 8001)만 허용하면 tailnet 안의 다른 기기는 추론 서비스에 못 붙는다. userspace 모드의 Pod 는 tailnet 에서 오는 연결을 **모든 포트**에 대해 127.0.0.1 로 넘기므로(Jupyter 8888, sshd 22, nginx 포트 포함), 팀원 노트북·폰을 tailnet 에 넣기 전에는 꼭 건다.

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
| `INFERENCE_BASE_URL` | `http://<Runpod Pod 의 Tailscale IP>:8002`(`start_pod.sh` 가 마지막에 출력), GPU PC 면 `http://<그 PC 의 Tailscale IP>:8001` |
| `INFERENCE_TOKEN` | 긴 무작위 문자열(`openssl rand -hex 32`). GPU 쪽 `BESIDE_INFERENCE_TOKEN` 과 같게 |
| `NOTIFY_WEBHOOK_URL` | (선택) Discord 웹훅 URL. 작업 완성·실패와 GPU 서버 연결/끊김을 팀 채널에 알린다. 비우면 끔 |
| `NOTIFY_INFERENCE_LABEL` | (선택) GPU 알림에 붙는 서버 이름(예: `Runpod A40` → "🟢 GPU 추론 서버(Runpod A40) 연결됨"). 비우면 이름 없이. prod 는 끊김(🔴)을 45초(`notify.down-grace-ms`) 넘게 이어질 때만 알린다. 그 안에 바로 다시 연결되면 🔴·🟢 둘 다 보내지 않고, 서비스가 다시 시작돼 모델을 불러오는 중(🟡)을 거쳐 돌아오면 🟡·🟢 만 보낸다. `#` 는 쓰지 않는다(따옴표 없는 값에서 compose 가 ` #` 뒤를 주석으로 자른다) |
| `GRAFANA_ADMIN_USER` · `GRAFANA_ADMIN_PASSWORD` | (monitoring 프로필을 쓸 때만) Grafana 관리자 계정. 비밀번호는 `openssl rand -base64 24`. 견본에는 일부러 비어 있고, 비어 있으면 grafana 컨테이너가 뜨지 않는다(아래 '모니터링') |

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
backend:8081/actuator/prometheus ◀── 15초마다 ── prometheus:9090 (VM 루프백에만 게시, 14일 또는 1GB 보존)
                                                        ▲
브라우저 ── https://<도메인>/grafana/ ── caddy(Basic 인증) ── grafana:3000 (Grafana 로그인) ┘
```

- **Actuator 는 관리 포트 8081** 에만 있다. compose 내부망에서만 닿고(`ports:` 없음), 공개 8080 에는 `/actuator` 가 없으며, Caddy 도 `/actuator*` 를 404 로 막는다. Prometheus(9090)는 VM 의 `127.0.0.1` 에만 게시한다(인터넷에는 안 열림). 개발 PC 에서 원시 값이 필요하면 `ssh -L 9090:127.0.0.1:9090 <user@VM>` 로 터널을 열고 `http://localhost:9090` 을 쓴다(부하 시험 수집기·중단 감시가 이 방법을 쓴다).
- 대시보드: Grafana 의 **Beside** 폴더 → **Beside 운영**. 변수 `env`(`prod` 운영 / `loadtest` 부하 시험 / `local`), `worker`(mock·real), `창`(드문 작업 지표를 묶는 시간 창). 원본은 [monitoring/grafana/dashboards/beside-ops.json](monitoring/grafana/dashboards/beside-ops.json) 이고 화면에서 고친 것은 저장되지 않는다(JSON 을 고쳐 커밋하면 1분 안에 다시 읽는다).
- `beside_*` 지표는 backend 의 Actuator·Micrometer 코드(같은 W1 의 B1~B3)가 있어야 나온다. 그 전 이미지로는 Prometheus 대상이 DOWN 이고 패널이 비어 있다.
- **생성 성공률의 '패스스루 제외' 선(B)은 실제 모델일 때만 맞다.** FAILED 실행은 늘 `passthrough="false"` 로 태그된다. backend 가 스텁(패스스루)의 실패와 모델의 실패를 구분하지 못하기 때문이다. 그래서 스텁이 답하는 동안 B 는 스텁의 성공은 빼고 실패만 센다. 스텁을 쓰는 부하 시험(G·F 시리즈, [loadtest/README.md](loadtest/README.md))에서는 필터 없는 성공률(선 A)이나 `scripts/load/collect.py` 결과를 본다.

### 메모리 (4GB VM)

| 컨테이너 | 한도 |
|---|---|
| backend | 3g (`mem_limit`, 힙은 그 40 %, 나머지는 전처리 모델의 ONNX Runtime 네이티브 메모리) |
| prometheus | 384m |
| grafana | 256m |
| caddy · Docker · OS | 한도 없음, 약 0.5GB |
| **합계** | **약 4.2 / 4GB** (한도 합) |

monitoring 프로필까지 띄우면 한도 합이 4GB 를 넘으므로 **swap 2GB 가 필요**하다(한 번). 지금 GCP VM 은 8GB(+ swap 2GB)라 한도 합이 RAM 안에 든다. 다만 caddy 는 한도가 없고 2026-11-18 부하 시험 A1 에서 최대 2,246 MiB 까지 썼다(위 표의 추정보다 크다). 그때 컨테이너 합계는 최대 약 2.9 GiB 였다(docker stats, [docs/perf/load/2026-11-18_vm-1st.md](../docs/perf/load/2026-11-18_vm-1st.md)). swap 을 만드는 명령:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                                   # Swap: 2.0Gi
```

### 띄우기 · 확인

`deploy/.env` 에 `GRAFANA_ADMIN_PASSWORD`(`openssl rand -base64 24`)를 먼저 넣는다. 견본(`.env.example`)에서는 일부러 비워 두었다. 비어 있으면 grafana 컨테이너는 이유를 로그에 남기고 뜨지 않는다.

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

VM 에서 k6 부하를 걸 때는 운영 backend 를 내리고 [docker-compose.loadtest.yml](docker-compose.loadtest.yml) 로 부하용 backend(볼륨 `beside-loadtest-storage`, 웹훅·H2 백업 끔, `env=loadtest`)를 대신 띄운 뒤, 끝나면 운영으로 되돌린다. 팀 공지 → 운영 백업 → 띄우기 → 합성 GLB → k6 → 수집 → 복구 → 확인 순서와 중단 조건은 [loadtest/README.md](loadtest/README.md) 에 있다. 2026-11-18~19 에 세 번 실행했다 **[실험 결과]**. 다른 측정 중에는 하지 않는다.

## Runpod Pod 쪽

2026-11-13 결정: GPU 추론은 Runpod Pod 에서 하고, VM 과는 **Pod 안에 띄운 Tailscale(userspace 모드)** 로 잇는다.

| 방법 | 판단 |
|---|---|
| Runpod HTTP 프록시(`https://<pod>-<포트>.proxy.runpod.net`) | 쓰지 않는다. 요청을 100초에서 끊는다(Runpod 문서). Spring 은 `/infer/upload` 를 최대 600초(`inference.timeout-ms`) 기다리고, 실제 생성 시간은 아직 재지 않았다(exp-01) |
| Pod 의 TCP 포트 공개 + 토큰 | 쓰지 않는다. 사진과 토큰이 암호화 없이 인터넷을 지나고, Pod 를 다시 켜면 외부 포트가 바뀌어 그때마다 `.env` 를 고치고 backend 를 다시 띄워야 한다 |
| **Pod 안 Tailscale** | **고름.** 암호화되고 공개 포트가 없다. 노드 상태를 `/workspace` 에 두면 Pod 를 다시 만들어도 같은 100.x 로 돌아오고, 600초 요청도 그대로 지나간다. 처음 한 번 로그인 링크 승인이 필요하다 |

**동작 방식**: Pod 는 컨테이너라 TUN 장치가 없어서 `tailscaled --tun=userspace-networking` 으로 띄운다. 이 모드는 자기 100.x 로 들어온 TCP 를 `127.0.0.1` 의 같은 포트로 넘긴다(Tailscale 소스 `wgengine/netstack` 의 `acceptTCP` 에서 확인). 그래서 추론 서비스는 `127.0.0.1:8002` 에만 열고, Pod 의 공개 포트와 HTTP 프록시에는 아무것도 내놓지 않는다. **8001 이 아니라 8002** 인 이유: Runpod 공식 이미지의 nginx 가 8001(그리고 3001·7270·7861·8081·9091)을 이미 쓴다(`runpod/containers` 의 `container-template/proxy/nginx.conf`, 2026-11-17 조사).

**Pod 구성** (2026-11-17 조사. 가격과 재고는 Pod 를 만들기 직전에 다시 읽는다 — 같은 날에도 바뀌었다):

- **Secure Cloud + 네트워크 볼륨**(30GB, $0.07/GB/월)을 `/workspace` 에 붙인다. 정지한 Pod 는 원래 기계에서만 다시 켜지고, 그 GPU 를 다른 사람이 빌리면 GPU 없이 켜진다(Runpod 문서 'Zero GPU Pods'). 네트워크 볼륨은 Terminate 뒤에도 남으므로, 같은 데이터센터에 새 Pod 를 만들면 conda·가중치·Tailscale 노드가 그대로다. 그래서 쓰지 않을 때는 정지 대신 Terminate 하고, 다시 쓸 때 새 Pod 를 만든다. 네트워크 볼륨은 Secure Cloud 에서만, 만든 데이터센터 안에서만 쓸 수 있다.
- **GPU**: Ampere·Ada 계열(A40 48GB, RTX A5000·A6000, RTX 4090 등). RTX 5090 같은 Blackwell 은 AnimalLift 가 고정한 PyTorch 2.4 가 지원하지 않는다. 아시아 데이터센터에는 24~48GB 재고가 없어 유럽에 둔다(서울 VM 과의 지연은 첫 연결 때 잰다).
- **CUDA**: 조사 시점의 Secure 재고는 모두 CUDA 13.2 호스트에만 있었다. 공식 템플릿 `runpod-torch-v240` 은 12.4~12.9 만 허용하므로 콘솔에서 그대로 만들면 GPU 가 잡히지 않는다. API(MCP `create-pod`)로 `gpu.minCudaVersion: "12.4"` 를 준다. CUDA 12.4 이미지가 13.2 드라이버에서 도는 것은 NVIDIA 하위 호환에 따른 예상이고 아직 Pod 에서 확인하지 않았다.
- **이미지**: `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`(root, curl·git·nginx·python3.11-venv 있음, tmux 없음). Jupyter(8888)와 SSH(22)는 필요 없으면 열지 않는다.
- CPU Pod 에는 볼륨 디스크를 붙일 수 없어서 연결 시험도 GPU Pod 에서 한다. AnimalLift 를 설치하기 전에는 스크립트가 알아서 패스스루로 뜬다.

**처음 한 번**:

1. Tailscale 계정(무료)을 만든다. VM 과 Pod 를 같은 tailnet 에 넣을 계정이다.
2. VM: 위 '3. Tailscale' 의 명령을 실행하고 출력된 링크를 승인한다.
3. 위 구성으로 Pod 를 만든다.
4. Pod 안에서 실행한다. **토큰 줄은 사람이 웹 터미널이나 SSH 에서** 한다: 개발 PC 에서 `ssh beside-gcp "grep ^INFERENCE_TOKEN= ~/Beside/deploy/.env"` 로 직접 꺼내 `=` 뒤의 값만 붙인다(이름·따옴표가 들어가면 스크립트가 거부한다). 채팅·문서·셸 기록에 남지 않게 파일로 둔다. exec 도구(Runpod MCP `exec-pod`)는 입력(stdin)이 없어 이 줄을 할 수 없고, 토큰을 명령이나 env 로 넘기면 도구 호출 기록에 남는다. 나머지 줄(clone, mkdir, nohup)은 exec 도구로 해도 된다. exec 도구는 MCP 에 연결된 Runpod 계정의 Pod 에만 닿으므로, 크레딧이 있는 계정으로 연결돼 있어야 한다.

   ```bash
   git clone https://github.com/minwoo3717/Beside.git /workspace/Beside   # main(deploy/gcp 는 2026-11-18 PR #5 로 병합됨)
   (umask 077; cat > /workspace/beside-token)    # 토큰을 붙이고 Enter, Ctrl-D
   mkdir -p /workspace/beside-logs
   nohup bash /workspace/Beside/deploy/runpod/start_pod.sh > /workspace/beside-logs/start.log 2>&1 &
   ```

5. 첫 실행은 Tailscale 1.104.6 을 `/workspace/tailscale` 에 받고(sha256 고정) 로그인 링크를 `/workspace/beside-logs/tailscale-up.log` 에 쓴다. 그 링크를 승인하면 `start.log` 끝에 `INFERENCE_BASE_URL=http://100.x.y.z:8002` 가 나온다. 터미널을 보고 있다면 `nohup ... &` 없이 실행해도 같은 내용이 화면에 나온다.
6. VM: `deploy/.env` 의 `INFERENCE_BASE_URL` 을 그 값으로 바꾸고 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d backend` 를 실행한다. 확인은 `/healthz` 만이 아니라 실제 사진 1건(`scripts/e2e_mock.sh https://<도메인> <사진>`)으로 한다 — tailnet 의 MTU 가 작아 큰 업로드만 막히는 경우를 healthz 는 잡지 못한다. 끝나면 `docker rm -f beside-stub`.

**다시 쓸 때마다**(새로 만든 Pod 든 정지했다 켠 Pod 든) 먼저 `git -C /workspace/Beside pull --ff-only origin main` 으로 볼륨의 리포를 main 에 맞추고, 4번의 마지막 두 줄만 실행한다. `start_pod.sh` 는 리포를 당기지 않는다. 3차 끝(2026-11-18 18:58 UTC) 때 리포는 `b6c0e6c` 였고, 2026-11-19 병합한 PR #11(DELETE 취소)은 이렇게 당겨야 Pod 에 들어간다. 노드 상태가 `/workspace/tailscale` 에 있어서 로그인 없이 같은 100.x 로 돌아오고 VM `.env` 는 그대로 둔다. 이미 떠 있는 것은 다시 띄우지 않는다. 단, 떠 있는 서비스의 모드가 지금 모드와 다르면(패스스루 시험 중에 AnimalLift 를 설치한 경우) 그 pid 를 `kill` 하라고 알리고 멈춘다. 스크립트는 한 번에 하나만 돈다(앞의 실행이 끝나지 않았으면 거부). 한 볼륨을 두 Pod 가 동시에 쓰지 않는다(같은 노드 키로 서로 밀어낸다). 스크립트가 마지막으로 쓴 Pod id 를 기록해 두고, 다르면 알려 준다.

**네트워크 볼륨이 아니라 Pod 볼륨 디스크로 만든 Pod 를 Terminate 했을 때**는 `/workspace` 가 지워져 새 노드가 된다. 다시 로그인하고, Tailscale 관리 콘솔에서 옛 `beside-gpu` 노드를 지운 뒤, VM 의 `INFERENCE_BASE_URL` 을 새 주소로 바꾼다.

| `MODE` | 하는 일 |
|---|---|
| `passthrough`: `/workspace/AnimalLift` 가 없을 때의 기본값 | `/workspace/beside-venv` 에 `requirements.txt` 를 설치하고 고정 GLB 를 돌려준다. 연결 시험용이다. `/workspace/beside-sample.glb` 가 없으면 삼각형 하나짜리 GLB 를 만든다. 진짜 강아지를 보이려면 그 자리에 GLB 를 올려 둔다 |
| `model`: `/workspace/AnimalLift` 가 있을 때의 기본값 | `scripts/start_inference_wsl.sh` 로 AnimalLift 를 띄운다. `nvidia-smi` 에 GPU 가 없으면 시작하지 않는다(GPU 없이 다시 켜진 Pod 에서 모델이 CPU 로 조용히 올라가 모든 작업이 600초 뒤 실패하는 것을 막는다). conda(`/workspace/miniconda3`, env `animallift`)·AnimalLift·체크포인트·리깅 템플릿(`/workspace/beside-rig/template-rigged.glb`)은 모두 `/workspace` 아래에 둔다. 설치는 [runpod/install_animallift.sh](runpod/install_animallift.sh)(`mkdir -p /workspace/beside-logs && nohup bash /workspace/Beside/deploy/runpod/install_animallift.sh > /workspace/beside-logs/install.log 2>&1 &`, 진행은 `install.status`). `/workspace/AnimalLift.installing` 에 받고 모두 끝난 뒤에만 `/workspace/AnimalLift` 로 옮긴다. 자세한 것은 [ANIMALLIFT_SETUP.md](../generation/inference_service/ANIMALLIFT_SETUP.md) |

파일 위치:

| 경로 | 내용 |
|---|---|
| `/workspace/tailscale/` | 바이너리와 노드 상태(노드 키, 권한 700), 마지막 Pod id |
| `/workspace/beside-token` | 추론 토큰(권한 600). `BESIDE_INFERENCE_TOKEN` 환경 변수가 있으면 그쪽이 먼저다 |
| `/workspace/.cache/` | SD VAE·DINOv2 가중치 캐시(`HF_HOME`, `TORCH_HOME`). 없으면 컨테이너 디스크에 받아 정지할 때마다 다시 받는다 |
| `/workspace/beside-logs/` | `start.log`, `tailscaled.log`, `tailscale-up.log`, `inference.log`. 실행마다 시각 표시를 붙여 이어 쓴다 |
| `/workspace/beside-work/` | 작업 폴더(Spring 이 GLB 를 받은 뒤 `DELETE` 로 지운다). 남은 폴더는 `BESIDE_WORK_SWEEP_MINUTES` 로 지울 수 있다(`start_pod.sh` 는 켜지 않는다) |
| `/var/run/beside/` | 소켓과 pid. 정지하면 사라진다 |

**확인한 것**

- **[구현됨]** WSL(Ubuntu 26.04)에서 가짜 `tailscale`·`tailscaled`·`nvidia-smi`(네트워크에 나가지 않음)와 실제 추론 서비스(패스스루 uvicorn)로 65개 검사가 모두 통과했다(2026-11-17, 검토 워크플로가 찾은 문제를 고친 뒤).
  - 거부: 토큰 없음·빈 토큰·이름(`INFERENCE_TOKEN=`)이나 따옴표가 붙은 토큰·잘못된 `MODE`, `model` 모드인데 GPU 없음, 앞의 실행이 아직 도는 중, 떠 있는 서비스와 모드가 다름(패스스루가 떠 있는데 AnimalLift 를 설치한 경우 — 서비스는 그대로 두고 kill 할 pid 를 알림).
  - 포트: `PORT=1800` 이 8002·18001 에 떠 있는 서비스로 오인되지 않음.
  - 토큰: 환경 변수 또는 `/workspace/beside-token`, 권한이 600 이 아니면 경고.
  - Tailscale 실행: userspace·statedir·소켓 플래그, 로그인 링크는 첫 실행에만이고 `tailscale-up.log` 에도 남음, 마지막 Pod id 가 바뀌면 알림.
  - 서비스: 기본 포트 8002, `127.0.0.1` 에만 바인드, 토큰이 없거나 틀리면 401, 업로드한 결과가 샘플 GLB 와 같음.
  - 다시 실행: 이미 떠 있으면 두 번 띄우지 않음, 재시작을 흉내 낸 경우(프로세스 없음, 낡은 pid 파일) 로그인 없이 다시 뜸, 로그는 이어 씀.
  - `model` 모드: 환경 변수, 가중치 캐시가 `/workspace/.cache`, `set -u` 아래의 conda 활성화(가짜 conda 가 정의되지 않은 변수를 읽어도 통과).
  - 실패 처리: 포트를 HTTP 가 아닌 것(nginx 처럼 502 를 주는 것 포함)이 쓰고 있으면 바로 실패로 끝남.
  - 상태 폴더 권한 700.
- **[구현됨]** 실제 Tailscale 1.104.6 tarball: sha256 이 틀리면 설치하지 않는다. 받기와 설치가 되고, 스크립트가 쓰는 플래그가 실제 CLI 에 모두 있다.
- **[구현됨]** 2026-11-17 실제 Pod(A40, EU-SE-1, 네트워크 볼륨)에서 실행: 첫 로그인 링크 → 승인 → 패스스루 서비스, VM ↔ Pod 전체 흐름 1건. Pod 의 nginx 8001 점유와 tailnet 연결이 127.0.0.1 로 넘어오는 것을 실제로 확인했다([기록](../docs/deploy/2026-11-17_runpod-connect.md)).
- **[구현됨]** 같은 날 `install_animallift.sh` 로 설치(497 s) 뒤 `start_pod.sh` 가 패스스루가 떠 있다며 멈추는 것을 확인하고, 그것을 끈 뒤 `model` 모드로 띄워 실제 사진 3건 성공([exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)).
- **[계획]** Pod 를 Terminate 한 뒤 같은 100.x 유지. 새로 만든 Pod 가 같은 볼륨으로 같은 100.x 에 붙는 것과 VM ↔ Pod 직접 연결(`tailscale ping` 263~269 ms)은 2026-11-18·19 부하 시험에서 확인했다 **[실험 결과]**. 3차 G0 의 전송 등 중앙값은 4.41 s(Pod keep-alive 75 s 면 3.56 s)였다.

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
| 작업이 `INFERENCE_UNAVAILABLE` | Pod(또는 GPU PC)가 켜져 있는지, Pod 를 다시 켠 뒤 `start_pod.sh` 를 실행했는지, `tailscale status`, VM 호스트와 backend 컨테이너에서 각각 `curl http://<GPU 쪽 100.x>:<8002 또는 8001>/healthz`, Pod 의 `/workspace/beside-logs/` | prod 는 GPU 가 응답하지 않으면 30분 동안 PENDING 으로 기다린 뒤 이 코드로 끝난다(`inference.offline-wait-ms`). 호스트에서는 되는데 컨테이너에서만 안 되면 `docker-compose.yml` 의 backend 주석대로 호스트 네트워크로 옮긴다 |
| `permission denied ... docker.sock` | `groups` 에 docker 가 있는지 | `sudo usermod -aG docker $USER` 뒤 다시 로그인 |
| `required variable ... is missing` | `deploy/.env` 에 그 변수 | 견본(`.env.example`)의 모든 변수를 채운다. `--env-file deploy/.env` 를 빠뜨리지 않는다 |
| grafana 가 계속 재시작, 로그에 `GRAFANA_ADMIN_PASSWORD 를 넣는다` | `deploy/.env` 의 `GRAFANA_ADMIN_PASSWORD` | 값을 넣고 `$M up -d grafana` |
| `/grafana/` 가 Basic 인증 뒤 502 | `$M ps` 에 grafana 가 있는지 | monitoring 프로필을 안 띄웠다: `$M up -d`. 쓰지 않을 거면 그대로 둬도 다른 경로에는 영향 없다 |
| Basic 인증은 통과했는데 Grafana 가 401 · 'invalid username or password' | Caddyfile 의 `/grafana*` reverse_proxy 에 `header_up -Authorization` 이 있는지 | Caddy 가 팀 계정 헤더를 넘기면 Grafana 가 그것을 자기 계정으로 검사한다. 헤더를 지우고 `... restart caddy` |
| 대시보드가 비어 있음 · Prometheus `up` 이 0 | `$M exec backend curl -fsS http://localhost:8081/actuator/prometheus \| head` | 응답이 없으면 그 이미지에 Actuator(관리 포트 8081)가 없다 — 최신 main 으로 다시 빌드. 응답은 있는데 패널만 비면 대시보드 상단 `env` 가 `prod` 인지 |
