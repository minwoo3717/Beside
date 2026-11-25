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
| Tailscale, 공개 주소 → VM → Runpod Pod 1건 | **[구현됨]** 2026-11-17: VM·Pod 를 Tailscale 로 잇고 Pod 의 패스스루 서비스로 사진 1건 전체 흐름(개발 PC 에서, 합계 7.3 s, 기록 [docs/deploy/2026-11-17_runpod-connect.md](../docs/deploy/2026-11-17_runpod-connect.md)). 같은 날 Pod 에 AnimalLift 를 설치해(`runpod/install_animallift.sh`) 실제 모델로 3건 성공(모델 4~6 s, 전체 12~16 s, [exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)). 그날 VM ↔ Pod 는 DERP 중계(왕복 약 300 ms)였고, 2026-11-18·19 2·3차 부하 시험 때는 직접 연결(`tailscale ping` 263~269 ms)이었다 **[실험 결과]**. 날마다 바뀔 수 있다. 폰 앱(실기기)으로 공개 주소를 쓴 기록(기기·빌드 커밋·영상·logcat)은 리포에 없다 **[계획]**(2026-11-25 확인: `git ls-files` 에 영상·logcat 0개) |
| prod 프로파일(H2 콘솔·Swagger off, forward-headers, 업로드 사진 시그니처 검사) | **[구현됨]** (S1, `ProdProfileTest`). Caddy 의 404·401 규칙은 공개 주소에서 확인했고, 첫 배포 감사에서 나온 우회(끝의 `/`, `/webjars`, 소문자 method, 손님의 `X-Forwarded-Prefix`)를 막았다(2026-11-13). 파일 로그는 두지 않았다 — `docker compose logs` 를 쓴다 |
| Runpod Pod 추론 서비스 자동 재시작([runpod/supervise_inference.sh](runpod/supervise_inference.sh), 장애 대응 R7) | **[구현됨]** 2026-11-20: `start_pod.sh` 가 서비스를 감독 루프 아래에서 띄운다. 가짜 서비스·가짜 Tailscale 로 [runpod/test_start_pod_supervisor.sh](runpod/test_start_pod_supervisor.sh) 127개 검사(passthrough·model 모드)가 WSL(Ubuntu 26.04)에서 통과했고 CI 단계로 넣었다. 2026-11-20 장애 훈련 2차 확인에서 실제 A40 Pod(main `ce1a685`)로 쟀다 **[실험 결과]**: `kill -9` 5 s 뒤 감독 루프가 다시 띄우고 41 s 뒤 backend 가 UP 을 봤다(사람 손 없음). DOWN 이 45 s 유예보다 짧아 DOWN 알림 없이 🟡·🟢 만 갔다. 아래 'Runpod Pod 쪽'의 '서비스 자동 재시작' |
| `/infer` 업로드 모드(`INFERENCE_TRANSFER=upload`, `INFERENCE_TOKEN`) | **[구현됨]** (S2). VM 의 backend ↔ VM 안 스텁(별도 컨테이너, 토큰), 그다음 VM ↔ Runpod Pod(Tailscale, 2026-11-17)로 공개 주소에서 전체 흐름 확인 |
| GPU 오프라인 대기(최대 30분 PENDING), H2 온라인 백업(매시 5분, 48개. 2026-11-20 까지는 매일 04:30, 7개), Discord 알림(`NOTIFY_WEBHOOK_URL`, 서버 이름 `NOTIFY_INFERENCE_LABEL`, prod 는 45초 넘은 끊김만) | **[구현됨]** (S3, 테스트로 확인. 매시·48개는 `DatabaseBackupScheduleTest`). VM 반영은 2026-11-20 05:33 UTC main `ce1a685` 배포로 했다 **[구현됨: 적용]**: 설정 `0 5 * * * *`·48개를 확인했고, 그 뒤 매시 zip(13:05·14:05·15:05 UTC 등)과 매시 20분 VM 밖 업로드도 확인했다(아래 '운영 대응 설치' 적용 기록 1번). VM 에서: 백업은 2026-11-14~17 매일 zip 이 생겼다(매일 04:30 설정 때). Discord 는 2026-11-18 웹훅을 넣고 Pod 를 멈춘 상태로 backend 를 다시 띄워 끊김 메시지 1건이 오류 없이 나갔다(서버 이름·45초 유예는 2026-11-18 VM 리포를 main `b6c0e6c` 로 바꿔 배포한 뒤부터다. 같은 날 부하 시험 뒤 운영 복구 때 Pod 가 꺼져 있어 `UNKNOWN -> DOWN` 이 backend 로그에 남았다). 서버 이름이 든 메시지는 2026-11-20 장애 훈련 2차 확인에서 훈련 backend(부하·훈련 덮어쓰기. `--env-file deploy/.env` 라 서버 이름 `NOTIFY_INFERENCE_LABEL` 은 운영 값, 웹훅은 수집 서버 `DRILL_WEBHOOK_URL`)가 보낸 본문으로 확인했다("GPU 추론 서버(Runpod A40)…", [docs/ops/data/2026-11-20_failure-drills/window2/window2-hook.log](../docs/ops/data/2026-11-20_failure-drills/window2/window2-hook.log)) **[실험 결과]**. 오프라인 대기 중 작업이 이어지는 것은 2026-11-19 3차 부하 시험(같은 VM, 부하 덮어쓰기 backend `real,prod`, 웹훅 끔)의 계획 밖 경로 끊김 두 번과 F2-real 에서 확인했다 **[실험 결과]**([docs/perf/load/2026-11-19_vm-3rd.md](../docs/perf/load/2026-11-19_vm-3rd.md) 5·8절). 운영 backend 에서 본 기록은 아직 없다 |
| 모니터링(compose `monitoring` 프로필: Prometheus + Grafana 대시보드 'Beside 운영'), Caddy `/actuator*` 404·`/grafana*` 인증, 컨테이너 로그 회전 | 파일 **[구현됨]**(대시보드 JSON 은 `json.tool` 로 형식만 확인) · compose 설정 검증(`monitoring` 프로필·부하 덮어쓰기)과 경로 시험(`/actuator*` 404, `/grafana*` 401)은 CI(PR #3, main `18c345f`, 2026-11-13)에서 통과 **[구현됨]** · Prometheus 는 2026-11-18 부하 시험 때 VM 에서 띄워 Spring 지표를 수집했다 **[실험 결과]**(시험 뒤 다시 멈춤). Grafana 는 2026-11-25 05:52 UTC 운영 VM 에 띄웠다 **[적용]**(Prometheus 다시 시작, backend·caddy 는 그대로·재시작 0, 공개 경로 61/61). 같은 날 GPU 리허설 작업 9건으로 멘토 지표 5개 값이 Prometheus 에 찼다 **[실험 결과]**([2026-11-25 리허설](../docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md)). Grafana 화면 확인·캡처는 [계획]. 아래 '모니터링' |
| 부하 시험 덮어쓰기(`docker-compose.loadtest.yml`: backend 만 부하용 볼륨·mock 설정으로 바꿈)와 절차 | 파일 **[구현됨]** · VM 실행 1차(A0~A4, mock) 2026-11-18, 2차(실제 A40, G0r·G1r·G3r) 2026-11-18, 3차(G2r·F2-real·F1b-real·F1a) 2026-11-19, 4차(새 A40 Pod, G0·G3r c=1·G2r c=2·F1b-real) 2026-11-19 UTC, 5차(큐 상한 0 대 19, FD16b·F1b·FD9·FD5 재현) 2026-11-21 UTC **[실험 결과]** — [loadtest/README.md](loadtest/README.md), [1차](../docs/perf/load/2026-11-18_vm-1st.md)·[2차](../docs/perf/load/2026-11-18_vm-2nd.md)·[3차](../docs/perf/load/2026-11-19_vm-3rd.md)·[4차](../docs/perf/load/2026-11-20_vm-4th.md)·[5차](../docs/perf/load/2026-11-21_vm-5th.md) |

## GCP 메인 서버 (2026-11-13)

2026-11-12 결정으로 팀 메인 서버는 GCP 무료 체험 크레딧($300, 90일)으로 돌린다(팀 내부 재료비 구매 계획 §3 — 리포에는 올리지 않은 문서). **업그레이드(전체 계정 활성화)를 누르지 않으면 청구되지 않는다.** 체험 중에는 GPU 를 붙일 수 없으므로 추론은 GCP 밖의 Runpod Pod 에 두고 Pod 안의 Tailscale 로 잇는다(2026-11-13 결정, 아래 'Runpod Pod 쪽').

| 항목 | 값 |
|---|---|
| 프로젝트 | `beside-514511`(무료 체험 결제 계정에 연결) |
| VM | `beside-vm` — `asia-northeast3-a`(서울), e2-standard-2(2 vCPU · 8GB), Ubuntu 24.04 LTS, 균형 디스크 50GB, 삭제 방지 켬 |
| 주소 | 고정 IP `beside-ip` 34.64.91.254 → `https://beside-team.duckdns.org` |
| 방화벽 | `beside-allow-web`: tcp 80·443, udp 443 → 네트워크 태그 `beside-web`. SSH 22 는 기본 네트워크의 `default-allow-ssh`(키 인증만) |
| SSH | 사용자 `beside`, 개발 PC 의 키 `~/.ssh/beside-vm.key`, `~/.ssh/config` 별칭 `beside-gcp` |
| 서비스 계정·백업 버킷 | `beside-backup@beside-514511.iam.gserviceaccount.com`(키 없음, 범위 `storage-rw,logging-write,monitoring-write`, 2026-11-20 04:26 UTC 까지 붙임) — 버킷 `gs://beside-514511-backups` 에 만들기만(`roles/storage.objectCreator`). 아래 '운영 대응 설치' 3번. 컨테이너는 2번(메타데이터 차단, 2026-11-20)으로 이 토큰을 받지 못한다 |
| 코드 | VM 의 `~/Beside` 는 `main` `625c2a5`(PR #30 병합 뒤, 2026-11-22 07:05:35 UTC `git pull --ff-only`, backend 컨테이너 07:07:56 UTC 다시 만듦. 2026-11-25 확인: VM `git reflog`·`docker inspect`). main 의 그 뒤 커밋(PR #31)은 `unity/` 만 바꿨다. 그 전 배포는 2026-11-21 01:06 UTC `fe10683`(`api-v1.1`, 5차 부하 시험 전), 2026-11-20 15:50:40~15:53:14 UTC `7230dea`(PR #22, backend 만 다시 빌드·다시 만듦), 05:32:32~05:33:49 UTC `ce1a685`(PR #18·#20). caddy 컨테이너는 2026-11-20 05:33 UTC 에 만든 그대로다. 추론 서비스 변경(PR #11, PR #20 의 `loadError`·`busyForMs`·감독 루프)은 VM 이 아니라 Pod 에서 돈다. 감사 뒤 강화한 Caddyfile 은 PR #5 로 main 에 들어갔고 VM 의 로컬 수정은 되돌렸다(아래 '지금 상태'의 절차) |

**만들기**(다시 만들 때도 같다): 콘솔 오른쪽 위 `>_`(Cloud Shell)에서 [gcp/create_vm.sh](gcp/create_vm.sh) 내용을 `~/create_vm.sh` 로 저장하고 `SSH_PUBKEY='<beside-vm.key.pub 한 줄>' bash ~/create_vm.sh` 를 실행한다(영역 자원이 모자라면 끝에 `asia-northeast3-b`). 이미 있는 리소스는 건너뛰므로 중간에 멈추면 같은 명령을 다시 실행한다. 마지막 줄의 `IP=` 를 DuckDNS 에 넣는다.

**VM 첫 설정**(2026-11-13 에 한 순서):

1. 아래 '1. VM 만들기' 의 Docker 설치. 컨테이너를 만들기 전에 `/etc/docker/daemon.json` 에 로그 회전 `{"log-driver": "json-file", "log-opts": {"max-size": "20m", "max-file": "5"}}` 를 넣는다(기본은 무제한이라 접근 로그가 디스크를 채운다).
2. 스왑 2GB(fstab 포함, '띄우기' 절).
3. `git clone https://github.com/minwoo3717/Beside.git ~/Beside` → `main`(2026-11-13 에는 `feat/preprocess` 였다. Caddy 강화가 든 `deploy/gcp` PR 이 병합되기 전에 다시 만든다면 `deploy/gcp`) → `scripts/fetch_models.sh backend/models`.
4. `deploy/.env`: `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env`, 도메인, `INFERENCE_TOKEN` 은 VM 에서 `openssl rand -hex 32` 로 만들어 바로 파일에 넣는다(화면에 찍지 않는다).
5. 팀 비밀번호: 개발 PC 에서 `ssh -t beside-gcp 'bash ~/Beside/deploy/set_basic_auth.sh'`([set_basic_auth.sh](set_basic_auth.sh) — 두 번 묻고 해시만 `.env` 에 넣는다. 비밀번호를 바꿀 때도 같다). 이 파일이 VM 의 체크아웃에 들어오기 전에는 같은 일을 하는 VM 의 `~/bin/beside-set-password` 를 쓴다(`ssh -t beside-gcp '~/bin/beside-set-password'`).
6. `STORAGE_DB_CREATE_IF_MISSING=true docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build` → backend 가 healthy 가 되면(빈 DB 파일이 생긴다) **변수 없이 한 번 더** `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d` → 밖에서 '확인' 절. 앞의 `STORAGE_DB_CREATE_IF_MISSING=true` 는 **새 서버의 첫 시작에만** 붙인다(2026-11-20 부터): prod backend 는 H2 DB 파일이 없으면 시작하지 않기 때문이다(장애 훈련 FD5 — 전에는 빈 DB 를 조용히 만들어 기존 작업이 모두 404 였다). 두 번째 `up -d` 를 빼먹으면 컨테이너가 그 값을 계속 들고 있어서, 다음 배포까지는 재시작·재부팅 때 DB 를 잃어도 빈 DB 로 뜬다. `docker inspect beside-backend-1 -f '{{range .Config.Env}}{{println .}}{{end}}' | grep STORAGE_DB_CREATEIFMISSING` 가 `=false` 인지 본다. 이미 쓰던 서버에서 backend 가 `H2 database file ... does not exist` 로 멈추면 DB 를 잃은 것이니 붙이지 말고 [RUNBOOK](../docs/ops/RUNBOOK.md) 'DB 복구' 로 되돌린다.

**지금 상태(2026-11-13)**: 공개 주소에서 HTTPS·Basic 인증·404 규칙·전체 흐름(스텁)·재부팅 복구·`deploy_vm.sh beside-gcp` 를 확인했다. 수치와 보안 감사 결과는 [docs/deploy/2026-11-13_gcp-first-deploy.md](../docs/deploy/2026-11-13_gcp-first-deploy.md). 감사 뒤 강화한 `deploy/Caddyfile` 은 VM 의 `~/Beside` 에 **로컬 수정**으로 들어가 있다. 이 강화는 `deploy/gcp` → `main` PR 로만 들어가고 `feat/preprocess` 에서 pull 해도 오지 않으므로, PR 이 병합되기 전에는 VM 의 Caddyfile 을 되돌리지 않는다. 병합 뒤 VM 에서 `git checkout deploy/Caddyfile && git checkout main && git pull --ff-only`, 그다음 개발 PC 에서 `scripts/deploy_vm.sh beside-gcp`(main 의 compose 변경으로 컨테이너가 다시 만들어진다). 2026-11-17 에 VM 에 Tailscale 을 설치하고(apt 저장소, `beside-vm` 100.98.76.5) `INFERENCE_BASE_URL` 을 Runpod Pod(100.83.86.62:8002)로 바꿨다. 이전 값은 `deploy/.env.bak-20261117-stub` 에 있고 스텁 컨테이너 `beside-stub` 는 되돌릴 때를 위해 남겨 두었다.

**남은 GCP 쪽 정리(Cloud Shell, 선택)**: 2026-11-20 04:26 UTC 전까지 VM 은 기본 서비스 계정(GCS 읽기 범위)을 달고 있어 컨테이너가 메타데이터 서버에서 GCP 토큰을 받을 수 있었다. 그때 아래 '운영 대응 설치' 3번대로 기본 계정 대신 버킷 `beside-514511-backups` 에 만들기만 되는 `beside-backup@beside-514511.iam.gserviceaccount.com` 을 붙였고, 같은 날 2번(컨테이너의 메타데이터 서버 차단)도 적용해 그 토큰은 VM 호스트(백업 스크립트)만 받는다 **[구현됨: 적용]**(운영자 적용·보고, 아래 '적용 기록'). **R1(VM 밖 백업)을 쓰면 계정을 `--no-service-account` 로 떼지 않는다** — 그러면 백업이 매시 실패한다(7번 VM 감시가 3시간 뒤 알린다). R1 을 쓰지 않기로 한 경우에만 `stop` → `set-service-account beside-vm --zone=asia-northeast3-a --no-service-account --no-scopes` → `start` 로 뗀다(약 1분 중단). 기본 네트워크가 만든 RDP 규칙은 어느 쪽이든 필요 없다.

```bash
gcloud compute firewall-rules delete default-allow-rdp
```

VM 을 새로 만들면(`create_vm.sh` 는 2026-11-13 결정대로 서비스 계정 없이 만든다. 스냅샷에서 만들 때 `--service-account` 를 주지 않으면 기본 계정이 붙는다) R1 을 위해 '운영 대응 설치' 3번의 붙이기(`set-service-account ... --service-account=beside-backup@beside-514511.iam.gserviceaccount.com --scopes=storage-rw,logging-write,monitoring-write`)를 다시 한다. `create_vm.sh` 로 빈 디스크부터 만들었으면 2·4·7번 설치도 다시 한다(스냅샷에서 만든 디스크에는 이미 들어 있다).

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
| **Basic 인증** | `/`, `/index.html*`, `/app.js*`(Mock 업로드 UI — 끝에 `/` 를 붙인 경로도), `/admin*`, `/library*`, `/job*`(관리·보관함·작업 상세 웹 화면), `/grafana*`(→ Grafana, 그다음 Grafana 로그인), `/api/v1/jobs`·`/api/v1/jobs/` 의 **POST 가 아닌** 모든 method(목록 등), `/api/v1/jobs/*` 의 **GET·HEAD·POST 가 아닌** 모든 method(삭제·평가 저장 등), `/api/v1/jobs/*/pet*`, `/api/v1/jobs/*/ratings*`·`/ratings/*`(품질 평가), `/api/v1/jobs/*/shares*`·`/shares/*`(공유 링크 만들기·모두 끊기 — 웹 작업 상세만 쓴다, 앱은 아직 공유하지 않음), `/api/v1/shares/*` 의 **GET·HEAD 가 아닌** 모든 method(공유 취소), `/api/v1/admin*`, `/api/v1/pets*` |
| **공개** | 그 밖 전부: 앱 API(`POST /api/v1/jobs` — 펫을 받지 않아 보관함에 쓰지 않는다, `GET`·`HEAD /api/v1/jobs/{id}`·`/asset`, `POST /retry`·`/cancel` — 작업 UUID 를 아는 쪽만 부를 수 있다), `/api/v1/healthz`, 공유 링크 보기(`/s/*`, `/share.*`, `GET /api/v1/shares/*` — Scene Viewer 가 인증 없이 GLB 를 받는다), `styles.css` 같은 정적 파일 |
| **413** | 요청 본문 25MB 초과(Caddy). 20MB·장당 5MB 초과는 Spring 이 v1 오류 봉투(`PAYLOAD_TOO_LARGE`)로 413 |
| **모든 응답** | `Strict-Transport-Security: max-age=15552000`(180일, 하위 도메인 없이), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`. 손님이 보낸 `Forwarded`·`X-Forwarded-Prefix`·`-Port`·`-Ssl` 은 Spring 에 넘기기 전에 지운다(`X-Forwarded-For`·`-Proto`·`-Host` 는 Caddy 가 새로 쓴다) |

- `;` 를 막는 이유: Tomcat 은 `/api/v1/jobs;x=1`, `/api;x/v1/jobs`, `/api/jobs/{id};x` 를 `;` 뒤를 뗀 경로로 처리한다(로컬 real 서버에서 셋 다 200 을 확인). 열어 두면 인증·404 규칙을 비켜 간다.
- Caddy 의 path 비교는 대소문자를 가리지 않고, 퍼센트 인코딩을 풀고 `//`·`..` 를 정리한 뒤에 한다. `caddy:2.11.1` 미만은 이 정리에 우회 취약점(CVE-2026-27587)이 있으므로 이미지를 내리지 않는다.
- 규칙을 바꾸면 [test_caddy_routes.sh](test_caddy_routes.sh) 의 기대값도 고친다. Linux + Docker 에서 `bash deploy/test_caddy_routes.sh` 로 가짜 백엔드 앞에 같은 Caddyfile 을 띄워 경로마다 상태 코드를 확인한다(CI 도 같은 명령).
- 공개 경로의 작업은 UUID 를 아는 사람만 볼 수 있다는 가정이다(계정 없음). 목록 API 를 막는 이유가 이것이다.
- 공개 `GET /api/v1/jobs/{id}` 의 `petId`(보관함의 펫 UUID)는 그 작업 UUID 를 가진 쪽에게 보이지만, 그 UUID 로 할 수 있는 일(펫 보기·작업 넣기·지우기)은 모두 위 인증 뒤다.
- 요청 수 제한은 아직 없다(#22 `RATE_LIMITED` 는 이름만 예약, **[계획]**). 공개 `POST /retry`·`/cancel` 을 되풀이하면 실행 기록(`job_runs`·`events.jsonl`)이 늘어난다. 취소는 Discord 로 보내지 않아 팀 채널은 덮이지 않는다. `job_runs`·`events.jsonl`·`share_links` 의 보존 기간은 #23 과 함께 정한다 **[계획]**.
- Spring 은 브라우저가 다른 사이트의 페이지에서 보낸 `/api/v1` 쓰기(`Sec-Fetch-Site`·`Origin` 으로 판단)를 400 으로 거절한다(`CrossSiteWriteFilter`). Basic 인증을 한 브라우저는 다른 사이트의 폼 POST 에도 인증 정보를 붙이기 때문이다.
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
| `NOTIFY_WEBHOOK_URL` | (선택) Discord 웹훅 URL. 작업 완성·실패와 GPU 서버 연결/끊김, 추론 토큰 불일치(🔑)·멈춘 듯한 작업(🐢)·모델 불러오기 실패(🧩, 아래 '문제 해결')를 팀 채널에 알린다. 비우면 끔 |
| `NOTIFY_INFERENCE_LABEL` | (선택) GPU 알림에 붙는 서버 이름(예: `Runpod A40` → "🟢 GPU 추론 서버(Runpod A40) 연결됨"). 비우면 이름 없이. prod 는 끊김(🔴)을 45초(`notify.down-grace-ms`) 넘게 이어질 때만 알린다. 그 안에 바로 다시 연결되면 🔴·🟢 둘 다 보내지 않고, 서비스가 다시 시작돼 모델을 불러오는 중(🟡)을 거쳐 돌아오면 🟡·🟢 만 보낸다. `#` 는 쓰지 않는다(따옴표 없는 값에서 compose 가 ` #` 뒤를 주석으로 자른다) |
| `ADMISSION_MAX_PENDING` | (선택, 기본 꺼짐) 대기열 상한 N — 안건 #21, 2026-11-21 결정(`api-v1.1`). 비우거나 0 이면 꺼짐(compose 기본 0). **운영 VM 은 두지 않는다(꺼 둠)**: 5차 부하 시험(2026-11-21)이 N 19 를 확인했고, 남은 조건은 `QUEUE_FULL` 을 아는 Unity 새 빌드를 팀·시연 기기에 까는 것이다. 그 뒤 켠다 [계획]. 켜면 대기 작업 + 접수 중 요청이 N 이상일 때 v1 생성·retry 를 503 `QUEUE_FULL` + `Retry-After` 로 거절한다. 켜기 전에 새 앱 빌드를 기기에 깐다. 0 이상 정수가 아니면 backend 가 시작하지 못하므로 `.env.example` 의 형식 확인 명령을 먼저 돌린다. 되돌리기는 0 으로 바꾸고 `up -d backend`(부하 중에도 된다. 그때 대기·처리 중이던 많아야 N + c 건이 `INTERNAL_ERROR` 로 실패하고 retry 할 수 있다) |
| `ADMISSION_RETRY_AFTER_SECONDS` | (선택) 상한이 거절할 때의 `Retry-After` 초 R. 비우면 165(c=1·N 19, 2026-11-21 #21 결정 값. 5차 부하 시험이 같은 규칙으로 다시 계산한 값은 167 이었고, 같은 날 165 유지로 정했다). 1 이상 정수. N 과 함께 정한다(R = N × 3600 / 잰 처리량). `inference.max-concurrency`(슬롯 수)나 GPU 수가 바뀌면 N 과 R 을 함께 다시 계산한다(값 규칙은 `.env.example`) |
| `GRAFANA_ADMIN_USER` · `GRAFANA_ADMIN_PASSWORD` | (monitoring 프로필을 쓸 때만) Grafana 관리자 계정. 비밀번호는 `openssl rand -base64 24`. 견본에는 일부러 비어 있고, 비어 있으면 grafana 컨테이너가 뜨지 않는다(아래 '모니터링') |

**해시의 `$` 처리**: docker compose v2 는 `.env` 의 따옴표 없는 값과 큰따옴표 값에서 `$이름`·`${이름}` 을 변수로 바꾸고, **작은따옴표 값은 글자 그대로** 쓴다(Docker 문서 'Interpolation — .env file syntax'). bcrypt 해시(`$2a$14$...`)를 따옴표 없이 넣으면 `$14...` 같은 부분이 빈 값으로 바뀌어 로그인이 영영 안 된다. `$$` 는 compose **YAML 파일 안**에서 `$` 를 쓰는 방법이고 `.env` 에서는 쓰지 않는다. 들어간 값은 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec caddy printenv BASIC_AUTH_HASH` 로 확인한다(CI 도 견본 파일로 같은 확인을 한다).

## 띄우기

전처리 모델(대표 사진 검사·크롭/배경 제거, 약 210 MB)을 먼저 받는다. `deploy_vm.sh` 는 이 단계를 스스로 한다. `deploy/.env` 의 `BESIDE_MODELS_DIR` 를 비우면 리포의 `backend/models` 를 쓴다.

```bash
scripts/fetch_models.sh backend/models        # 또는 BESIDE_MODELS_DIR 에 적은 절대 경로. 해시가 맞으면 다시 받지 않는다
```

backend 컨테이너는 이 모델 때문에 메모리 한도가 3 GB 다(힙 40% + ONNX Runtime). 4 GB VM 에서 이미지를 다시 빌드하는 동안 메모리가 모자랄 수 있으니 스왑 2 GB 를 만들어 둔다(`sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`, 재부팅 뒤에도 쓰려면 `/etc/fstab` 에 `/swapfile none swap sw 0 0`). 실제 사용량(`docker stats`)은 **[계획]** 이다. 전처리를 끄려면 `deploy/.env` 에 `PREPROCESS_CHECK_ENABLED=false`·`PREPROCESS_CUTOUT_ENABLED=false`.

리포 루트에서(compose 파일 기준 상대 경로와 `--env-file` 경로가 맞도록). **새 서버의 첫 시작**은 DB 파일이 아직 없으므로 첫 줄에 `STORAGE_DB_CREATE_IF_MISSING=true` 를 붙이고, backend 가 healthy 가 되면 변수 없이 `up -d` 를 한 번 더 한다(prod 는 H2 DB 파일이 없으면 시작하지 않는다 — 장애 훈련 FD5. 위 'VM 첫 설정' 6번). 이미 쓰던 서버에서는 붙이지 않는다:

```bash
STORAGE_DB_CREATE_IF_MISSING=true docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build   # 새 서버 첫 시작만
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps     # backend 가 healthy 가 되면
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d  # 변수 없이 한 번 더: 준비 끝
```

첫 빌드는 Gradle 의존성을 받느라 몇 분 걸린다. 이후에는 코드만 바뀌면 빠르다. 그 뒤 업데이트는 변수 없이 `up -d --build`(또는 `scripts/deploy_vm.sh`)다.

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

설정 파일 **[구현됨]** · VM 에서 띄우기 **[적용]**: 2026-11-25 05:52 UTC, 아래 '띄우기 · 확인' 의 `$M up -d` 대신 `$M up -d --no-deps prometheus grafana` 로 backend·caddy 를 건드리지 않고 띄웠다(먼저 `--dry-run` 으로 확인, 두 컨테이너 ID·재시작 0 그대로, `.env` 백업 `deploy/.env.bak-20261125-grafana`, 관리자 비밀번호는 VM 에서 만들어 출력하지 않음). 그때 메모리는 grafana 55 MiB·prometheus 100 MiB 였다. 이제 VM 의 compose 명령에는 늘 `--profile monitoring` 을 붙인다(아래). 첫 값은 [2026-11-25 리허설](../docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md) 의 Prometheus 질의. 멘토 지표 5종(생성 성공률, 생성 시간 평균·p95, 대기열 길이, API 응답 시간, 에셋 다운로드 실패율)과 서버 자원을 한 화면에서 본다. 지표 이름은 [docs/METRICS.md](../docs/METRICS.md) §5(`beside.*` → Prometheus `beside_*`).

```text
backend:8081/actuator/prometheus ◀── 15초마다 ── prometheus:9090 (VM 루프백에만 게시, 14일 또는 1GB 보존)
                                                        ▲
브라우저 ── https://<도메인>/grafana/ ── caddy(Basic 인증) ── grafana:3000 (Grafana 로그인) ┘
```

- **Actuator 는 관리 포트 8081** 에만 있다. compose 내부망에서만 닿고(`ports:` 없음), 공개 8080 에는 `/actuator` 가 없으며, Caddy 도 `/actuator*` 를 404 로 막는다. Prometheus(9090)는 VM 의 `127.0.0.1` 에만 게시한다(인터넷에는 안 열림). 개발 PC 에서 원시 값이 필요하면 `ssh -L 9090:127.0.0.1:9090 <user@VM>` 로 터널을 열고 `http://localhost:9090` 을 쓴다(부하 시험 수집기·중단 감시가 이 방법을 쓴다).
- 대시보드: Grafana 의 **Beside** 폴더 → **Beside 운영**. 변수 `env`(`prod` 운영 / `loadtest` 부하 시험 / `local`), `worker`(mock·real), `창`(드문 작업 지표를 묶는 시간 창). 원본은 [monitoring/grafana/dashboards/beside-ops.json](monitoring/grafana/dashboards/beside-ops.json) 이고 화면에서 고친 것은 저장되지 않는다(JSON 을 고쳐 커밋하면 1분 안에 다시 읽는다).
- `beside_*` 지표는 backend 의 Actuator·Micrometer 코드(같은 W1 의 B1~B3)가 있어야 나온다. 그 전 이미지로는 Prometheus 대상이 DOWN 이고 패널이 비어 있다.
- **생성 성공률의 '패스스루 제외' 선(B)은 실제 모델일 때만 맞다.** FAILED 실행은 늘 `passthrough="false"` 로 태그된다. backend 가 스텁(패스스루)의 실패와 모델의 실패를 구분하지 못하기 때문이다. 그래서 스텁이 답하는 동안 B 는 스텁의 성공은 빼고 실패만 센다. 스텁을 쓰는 부하 시험(G·F 시리즈, [loadtest/README.md](loadtest/README.md))에서는 필터 없는 성공률(선 A)이나 `scripts/load/collect.py` 결과를 본다.

### 메모리 (지금 VM 8 GB)

| 컨테이너 | 한도 |
|---|---|
| backend | 3g (`mem_limit`, 힙은 그 40 %, 나머지는 전처리 모델의 ONNX Runtime 네이티브 메모리) |
| caddy | 2g (`mem_limit`) + `GOMEMLIMIT=1638MiB`(약 80 %, Go GC 목표) |
| prometheus | 384m |
| grafana | 256m |
| Docker · OS | 한도 없음, 약 0.5GB |
| **합계** | **약 5.6GB + OS** (한도 합) |

caddy 한도는 2026-11-18 부하 시험 A1 에서 한도 없이 최대 2,246 MiB 까지 쓴 것(9,000명 유지 구간 2,170 MiB, 5,400명 1,292 MiB, 평상시 규모 1,800명 451 MiB)을 보고 정했다. compose 값 **[구현됨]** · `docker compose config` 로 두 값을 확인하는 CI 단언은 넣었지만 아직 돌지 않았다(개발 PC 에 Docker 가 없다. PR CI 의 docker 작업이 통과하면 그 커밋을 여기에 적는다). 목적은 caddy 가 늘어나 backend·OS 몫을 먹지 못하게 하는 것이다. 1g 로 낮추면 한도 없이 1 GiB 를 넘은 5,400명(평상시의 3배)부터 OOM 으로 caddy 가 죽어 모든 연결이 끊길 수 있다 [가설]. 한도 안에서 A1 9,000명이 그대로 버티는지는 다시 재야 한다 **[계획]**. 한도에 닿으면 caddy 컨테이너만 OOM 으로 죽고 `unless-stopped` 로 다시 뜬다(그동안 연결이 끊긴다). VM 반영 **[구현됨: 적용]**: 2026-11-20 05:33 UTC 배포로 caddy 를 다시 만들었고 `Memory` 2147483648, `GOMEMLIMIT` 1638MiB 를 확인했다.

지금 GCP VM 은 8GB(+ swap 2GB)라 한도 합이 RAM 안에 든다. 4GB VM 이면 한도 합이 RAM 을 넘으므로 **swap 2GB 가 필요**하다(한 번). A1 때 컨테이너 합계는 최대 약 2.9 GiB 였다(docker stats, [docs/perf/load/2026-11-18_vm-1st.md](../docs/perf/load/2026-11-18_vm-1st.md)). swap 을 만드는 명령:

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
- **[구현됨]** prod 는 서버를 멈추지 않고 매시 5분(한국 시각 기준 `storage.backup.zone` — 컨테이너는 UTC 로 돈다) H2 온라인 백업(`BACKUP TO`)을 볼륨의 `storage/backup/beside-<시각>.zip` 으로 만들고 최신 48개(이틀)를 남긴다(`storage.backup.*`, 2026-11-20 까지는 매일 04:30·7개). 꺼내기: `$C cp backend:/app/storage/backup ./h2-backup`. 업로드 사진·GLB 까지 묶으려면 위의 tar 절차.
- 이 백업들은 모두 VM 디스크 안에 있다. VM 밖(GCS 버킷 `beside-514511-backups`)으로 매시 20분에 올리는 것이 아래 '운영 대응 설치' R1 이고, 버킷·계정·메타데이터 차단·업로드 타이머를 2026-11-20 에 적용했다 **[구현됨: 적용]**(아래 '적용 기록'). 백엔드 배포(1번)는 2026-11-20 05:33 UTC 에 했다. 그 전까지 VM 의 prod 는 매일 04:30 KST·7개로 백업해서 버킷에 새로 올라가는 DB zip 이 하루 1개였다. 이제 매시 5분 zip 이 그 시각 20분에 올라간다 **[구현됨: 적용]**: 2026-11-20 13:05·14:05·15:05 UTC zip 이 생겼고, 13:20·14:20·15:20 UTC 업로드가 한 번에 1개씩 보냈다(`db: 1 new backup(s) sent`).

### 부하 시험

VM 에서 k6 부하를 걸 때는 운영 backend 를 내리고 [docker-compose.loadtest.yml](docker-compose.loadtest.yml) 로 부하용 backend(볼륨 `beside-loadtest-storage`, 웹훅·H2 백업 끔, `env=loadtest`)를 대신 띄운 뒤, 끝나면 운영으로 되돌린다. 팀 공지 → 운영 백업 → 띄우기 → 합성 GLB → k6 → 수집 → 복구 → 확인 순서와 중단 조건은 [loadtest/README.md](loadtest/README.md) 에 있다. 2026-11-18~21(UTC)에 다섯 번(1~5차) 실행했다 **[실험 결과]**(위 '상태' 표의 부하 시험 줄). 다른 측정 중에는 하지 않는다.

## 운영 대응 설치 (R1~R5)

2026-11-20 장애 시나리오 대응 가운데 VM·GCP 쪽 다섯 가지다. 스크립트·systemd 단위와 그 시험(가짜 docker·curl·systemctl·gcloud·iptables 의 `scripts/test_{watchdog,offsite_backup,block_metadata,gcp_ops}.sh` 와 `systemd-analyze verify`)은 **[구현됨]**(2026-11-20 WSL Ubuntu 에서 통과. 같은 명령을 CI 에 넣었고 PR CI 에서 처음 돈다)이다. **VM·GCP 적용은 2026-11-20 에 1~5번과 7번을 했고 [구현됨: 적용], 6번(외부 가동 확인 R3)만 [계획]** 이다. 2~5번(메타데이터 차단, 버킷·계정·VM 에 붙이기, 백업 타이머, 스냅샷 일정)은 장애 훈련 전후에 했다(3번은 운영자 보고, 2·4·5번은 [장애 훈련 기록](../docs/ops/2026-11-20_failure-drills.md)의 '결과에서 나온 대응'). 1번(백엔드 배포)은 05:32:32~05:33:49 UTC, 7번(VM 감시)은 05:34 UTC 에 했고, 이어진 장애 훈련 2차 확인(05:37~05:53 UTC)에서 R4·R6·R7 을 다시 재현했다. 남은 장애 고침(PR #22: FD16b·FD5·FD4·FD9)은 같은 날 15:50:40~15:53:14 UTC 에 배포했고, 7번은 🧱 가 든 판으로 15:53 UTC 에 다시 설치했다. 다시 적용하면 아래 '적용 기록' 표에 날짜와 확인 결과를 적는다.

| 대응 | 막는 장애 | 파일 | 상태 |
|---|---|---|---|
| R1 VM 밖 백업 | DB·사진·GLB 가 VM 디스크에만 있다(FD5 DB 유실, FD6 VM 유실) | prod H2 백업 매시 5분·48개, [backup/offsite_backup.sh](backup/offsite_backup.sh) + `beside-backup.service`·`.timer`(매시 20분), [backup/block-metadata.sh](backup/block-metadata.sh) + `beside-block-metadata.service`, [gcp/backup_bucket.sh](gcp/backup_bucket.sh) | 파일 [구현됨] · 버킷 `beside-514511-backups`·계정·VM 에 붙이기·VM 설치(차단·타이머) [구현됨: 적용] 2026-11-20 · 매시 H2 백업의 VM 반영(1번) [구현됨: 적용] 2026-11-20 05:33 |
| R2 스냅샷 일정 | VM·zone 통째 유실(FD6) | [gcp/snapshot_schedule.sh](gcp/snapshot_schedule.sh): 하루 1번 19:00 UTC(04:00 KST), 7일 보관, 서울 | 파일 [구현됨] · 일정 `beside-daily-snapshot` [구현됨: 적용] 2026-11-20 · 일정이 만든 스냅샷 READY 5개(2026-11-20~24 매일 19:14 UTC. 2026-11-25 `gcloud compute snapshots list` 확인) |
| R3 외부 가동 확인 | backend·Caddy·VM 이 죽어도 알림이 없다(FD1·FD2·FD3·FD6·FD7) | [gcp/uptime_check.sh](gcp/uptime_check.sh): `/api/v1/healthz` 1분 간격, `"status":"ok"`, 2개 지역 이상 2분 실패 → 메일 | 파일 [구현됨] · 적용 [계획] |
| R4 VM 감시 | 멈춘 backend 가 다시 시작되지 않는다(FD2), 디스크가 차도 모른다(FD4), VM 밖 백업(R1)이 멈춰도 모른다, DB 파일이 없어 backend 가 시작하지 않는다(FD5, 재시작 반복 알림 🧱 — PR #22) | [watchdog/beside-watchdog.sh](watchdog/beside-watchdog.sh) + `.service`·`.timer`(30초마다) | 파일 [구현됨] · 적용 [구현됨: 적용] 2026-11-20 05:34, 🧱 가 든 판으로 15:53 다시 설치 · FD2 재현에서 멈춘 backend 를 112 s 뒤 재시작 **[실험 결과]** · FD5 재현(2026-11-21 5차 부하 시험): 시작 거부, 🧱 44.6 s 뒤 **[실험 결과]** — 훈련 backend 와 따로 띄운 감시 인스턴스로 했고 알림은 VM 안 훈련 수집기로만 갔다 |
| R5 Caddy 메모리 상한 | A1 에서 한도 없는 Caddy 가 2.2 GiB | `docker-compose.yml` caddy `mem_limit: 2g`, `GOMEMLIMIT: 1638MiB`(위 '메모리') | compose 값 [구현됨] · CI 확인은 PR CI 통과 뒤 · VM 반영 [구현됨: 적용] 2026-11-20 05:33 |

- **얼마나 잃는가 [계산]**: DB 는 매시 5분 백업이 20분에 올라가므로 VM 을 통째로 잃으면 최근 약 1시간(최악 약 75분)의 작업을 잃는다. 사진·GLB 는 하루 1번(그날 첫 실행, 00:20 KST 무렵)이라 최악 약 24시간이다. 스냅샷은 최악 약 24시간 전 디스크다. 1번(매시 백업)을 배포한 2026-11-20 05:33 UTC 전에는 VM 밖으로 가는 DB zip 이 하루 1번(04:30 KST 백업, 05:20 KST 업로드)이라 DB 도 최악 약 25시간이었다(2026-11-20 에 4번을 적용하기 전에는 DB 가 하루 1번 같은 디스크에만 있었고 사진·GLB 는 VM 밖 사본이 없었다).
- **순서**: 메타데이터 차단(2) → 버킷·계정(3) → VM 에 계정 붙이기(정지 필요) → 백업 타이머(4). 차단을 먼저 하는 이유: 계정을 붙인 순간부터 컨테이너가 그 토큰을 받을 수 있다. 2026-11-20 에 2~4번을 모두 적용했으므로(단계별 시각은 남기지 않았다) 이 순서는 VM 을 빈 디스크(`create_vm.sh`)로 다시 만들 때 지킨다.
- **스크립트는 복사해서 쓴다**: root 가 돌리는 파일이 `git pull` 만으로 바뀌지 않게 `/usr/local/sbin` 으로 `install` 한다. 리포의 스크립트가 바뀌면 아래 `install` 줄을 다시 실행한다.
- 비밀(웹훅·토큰)은 출력하지 않는다. 백업 스크립트는 메타데이터 서버 토큰을, 감시 스크립트는 `deploy/.env` 의 `NOTIFY_WEBHOOK_URL` 을 curl 설정(stdin)으로만 넘긴다.

**적용 기록**(적용한 사람이 채운다. 시각은 UTC. 확인 결과에는 아래 각 단계의 확인 명령이 보여 준 것을 적는다):

| 단계 | 상태 | 적용 시각 (UTC) | 확인 결과 |
|---|---|---|---|
| 1. 백엔드 배포(R1 매시 백업, R5) | **[구현됨: 적용]** (main `ce1a685`, `scripts/deploy_vm.sh`) | 2026-11-20 05:32:32~05:33:49 | backend 다시 빌드, caddy 다시 만듦: `Memory` 2147483648, `GOMEMLIMIT` 1638MiB. prod H2 백업 `0 5 * * * *`·48개, `management.health.diskspace.enabled=false`, actuator `/health` UP, 공개 경로 61/61. 그 뒤 매시 5분 zip 이 생김(예: 13:05·14:05·15:05 UTC), 4번 타이머가 13:20·14:20·15:20 UTC 에 그 zip 을 하나씩 올림(`db: 1 new backup(s) sent`, 15:20 은 사진·GLB 새 파일 0·버킷 29). **PR #22 다시 배포**: main `7230dea`, 2026-11-20 15:50:40~15:53:14, backend 만 다시 빌드·다시 만듦(caddy 그대로). 배포 전 확인: 볼륨에 `db/beside-real.mv.db` 있음, `deploy/.env` 에 `STORAGE_DB_CREATE_IF_MISSING` 없음, 직전 2시간 `/api/v1/jobs` 요청 0. 배포 뒤: healthz `real,prod`, 컨테이너 `STORAGE_DB_CREATEIFMISSING=false`, `RestartCount` 0, 추론 `UNKNOWN -> DOWN`(Pod 꺼짐, 예상), 공개 경로 61/61 |
| 2. 메타데이터 차단(R1) | **[구현됨: 적용]** (장애 훈련 기록 `723fda8` 의 운영자 적용, 이 리포의 시험으로 본 것은 아님) | 2026-11-20 (시각 기록 없음). 1번 배포 뒤 리포 판으로 다시 `install` | 컨테이너에서 메타데이터 HTTP 000(무응답), 호스트 200, 컨테이너의 이름 풀이 정상. 1번으로 컨테이너를 다시 만든 뒤에도 `check` ok |
| 3. 버킷·계정·VM 에 붙이기(R1) | **[구현됨: 적용]** (운영자 적용·보고, 이 리포의 시험으로 본 것은 아님) | 2026-11-20 04:26 까지 | 버킷 `gs://beside-514511-backups`: asia-northeast3, 균일 접근, 공개 액세스 방지 enforced, 소프트 삭제 7일, 수명 주기 `db/` 만 30일 뒤 삭제. 키 없는 `beside-backup@beside-514511.iam.gserviceaccount.com` 에 그 버킷의 `roles/storage.objectCreator`. `beside-vm` 에 `--scopes=storage-rw,logging-write,monitoring-write` 로 붙임 |
| 4. 백업 타이머(R1) | **[구현됨: 적용]** (2번과 같음) | 2026-11-20 (시각 기록 없음). 1번 배포 뒤 리포 판으로 다시 `install` | 매시 20분 타이머. 첫 실행이 DB 백업 zip 7개(1번 배포 전이라 매일 04:30 KST 것)와 사진·GLB 29개를 올림. 버킷에서 받은 zip 을 H2 로 열어 작업 15건 확인. 1번 배포 전 마지막 실행 05:20 성공 |
| 5. 스냅샷 일정(R2) | **[구현됨: 적용]** (2번과 같음) | 2026-11-20 (시각 기록 없음) | 일정 `beside-daily-snapshot`: 매일 19:00 UTC, 7일 보관. 2026-11-25 확인(`gcloud compute snapshots list`): 일정이 만든 스냅샷 READY 5개, `beside-vm-asia-northeast3-a-20261120191411-…` ~ `…-20261124191411-…`(매일 19:14 UTC) |
| 6. 외부 가동 확인(R3) | [계획] | | (알림 채널 종류, 가동 확인 이름) |
| 7. VM 감시(R4) | **[구현됨: 적용]** | 2026-11-20 05:34, 15:53 다시 설치(🧱 가 든 PR #22 판) | 15:53 설치: `/usr/local/sbin/beside-watchdog` 가 리포 파일과 같음(`cmp`), 타이머 30초마다. 시작 90 s 유예가 끝난 15:54:30 부터 16:07 까지 25번 실행이 모두 조용했다(health 정상, 재시작 반복 없음). FD5 재현은 2026-11-21 5차 부하 시험에서 했다 **[실험 결과]**: 훈련 backend 가 시작을 거부했고, 따로 띄운 감시 인스턴스가 44.6 s 뒤 🧱 를 VM 안 훈련 수집기로 보냈다([5차](../docs/perf/load/2026-11-21_vm-5th.md) 7절). 이 설치본이 운영 웹훅으로 🧱 를 보낸 기록은 없다. 05:34 설치: `beside-watchdog.timer` 30초마다, 정상이면 스크립트는 아무것도 남기지 않음. 장애 훈련 2차 확인 FD2(05:44:36 SIGSTOP, 점검 표시 없음): 실패 1/3·2/3·3/3 뒤 `docker restart`(컨테이너 다시 시작 05:46:28.9), 🔁 05:46:29(운영 웹훅) — 멈춤에서 재시작 112 s **[실험 결과]**. 바로 뒤 훈련 스크립트가 backend 를 다시 만들어, 다시 시작한 컨테이너가 정상이 되기까지는 재지 못했다. 감시는 ✅ "재시작 186초 뒤 확인"(05:49:10)도 운영 웹훅으로 보냈다(시작 뒤 90 s 검사 유예와 훈련의 다시 만들기 때문에 늦은 확인이고 회복 시간이 아니다). 디스크·백업 알림은 재현하지 않음 |

**1. 백엔드 배포(R1 의 매시 백업, R5)** — 2026-11-20 05:32:32~05:33:49 UTC 적용함 **[구현됨: 적용]**(main `ce1a685`, 위 '적용 기록'. 같은 날 15:50:40~15:53:14 UTC 에 PR #22 의 `7230dea` 로 다시 배포). 개발 PC 에서 `scripts/deploy_vm.sh beside-gcp`. compose 의 caddy 설정이 바뀌었으므로 caddy 도 다시 만들어진다(몇 초 끊김, 인증서는 `caddy_data` 에 남는다). 확인(VM):

```bash
docker inspect -f '{{.HostConfig.Memory}}' beside-caddy-1          # 2147483648
docker exec beside-caddy-1 printenv GOMEMLIMIT                     # 1638MiB
sudo ls -t /var/lib/docker/volumes/beside-storage/_data/backup | head -n 3   # 다음 정시 5분 뒤 새 zip
```

**2. 메타데이터 차단(R1, VM)** — 2026-11-20 적용함 **[구현됨: 적용]**(위 '적용 기록'). 다시 할 일은 VM 을 빈 디스크로 새로 만들 때와 리포의 스크립트가 바뀌었을 때(`install` 줄)뿐이다:

```bash
cd ~/Beside
sudo install -m 755 deploy/backup/block-metadata.sh /usr/local/sbin/beside-block-metadata
sudo install -m 644 deploy/backup/beside-block-metadata.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now beside-block-metadata.service
sudo beside-block-metadata check     # ok 3줄: 규칙, 컨테이너의 메타데이터 HTTP 무응답(000), 컨테이너의 이름 풀이
curl -s -H 'Metadata-Flavor: Google' http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/email; echo   # 호스트는 그대로 닿는다
```

- 규칙은 `DOCKER-USER` 맨 위의 `-d 169.254.169.254/32 -p tcp ! --dport 53 -j DROP` 이다. 53(DNS)은 열어 둔다: 메타데이터 서버가 VM 의 DNS 다. Docker 를 다시 시작하면 단위가 규칙을 다시 넣는다(`PartOf=docker.service`).
- backend 를 호스트 네트워크로 옮기면(위 compose 주석의 대안) 이 차단이 적용되지 않는다.

**3. 버킷·계정(R1, gcloud 가 로그인된 곳)** — 2026-11-20 적용함 **[구현됨: 적용]**(위 '적용 기록'). 다시 할 일은 VM 을 새로 만들 때의 붙이기뿐이다(위 'GCP 메인 서버'). 스크립트는 다시 실행해도 있는 버킷·계정은 그대로 두고 설정만 다시 넣는다: `bash deploy/gcp/backup_bucket.sh`(먼저 `DRY_RUN=1` 로 명령 확인). 버킷 `<프로젝트>-backups`(이 프로젝트는 `beside-514511-backups`. 서울, 균일 접근, 공개 차단, 소프트 삭제 기본 7일, `db/` 만 30일 뒤 삭제)과 키 없는 `beside-backup@` 계정(그 버킷에만 `roles/storage.objectCreator`)을 만들고, VM 에 붙이는 명령을 출력만 한다. 붙이려면 VM 을 멈춰야 하므로(약 1분, 진행 중 작업은 `INTERNAL_ERROR`) 팀 공지 뒤 출력된 `stop` → `set-service-account ... --scopes=storage-rw,logging-write,monitoring-write` → `start` 를 한다. 2026-11-13 결정(컨테이너에 클라우드 자격 증명을 주지 않는다)은 그대로다: 계정은 VM 호스트만 쓰고 컨테이너는 2번으로 막는다.

- 이 계정은 만들기만 된다(읽기·목록·덮어쓰기·삭제 불가). 그래서 업로드는 `gcloud storage cp` 가 아니라 JSON API + `ifGenerationMatch=0` 이다(`gcloud storage cp` 는 `--no-clobber` 여부와 상관없이 대상 객체를 먼저 GET 해서 403 이 난다 — 스크립트 머리말).
- 버킷의 파일은 VM 쪽에서 지울 수 없다. 앱에서 작업을 지워도 그 사진·GLB 사본은 버킷에 남으므로, 지워야 하면 프로젝트 소유자 계정으로 `gcloud storage rm` 한다.

**4. 백업 타이머(R1, VM — 3번 뒤)** — 2026-11-20 적용함 **[구현됨: 적용]**(위 '적용 기록'). 다시 할 일은 2번과 같다:

```bash
echo 'BUCKET=beside-514511-backups' | sudo tee /etc/beside-backup.env   # 3번에서 만든 버킷(2026-11-20)
sudo install -m 755 deploy/backup/offsite_backup.sh /usr/local/sbin/beside-offsite-backup
sudo install -m 644 deploy/backup/beside-backup.service deploy/backup/beside-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start beside-backup.service          # 첫 실행: zip 전부 + 사진·GLB 전부
journalctl -u beside-backup -n 20 --no-pager        # "db: N new backup(s) sent", "files: N new file(s) sent, 0 failed"
sudo systemctl enable --now beside-backup.timer
systemctl list-timers beside-backup.timer
```

- 실패하면 서비스가 failed 로 남고 다음 정시 20분에 못 보낸 것부터 다시 보낸다. 계정·범위가 없으면 고치는 방법을 로그에 적고 멈춘다. 성공한 실행은 시각을 `/var/lib/beside-backup/last.ok` 에 남기고, 7번 VM 감시가 이것이 3시간 넘게 묵거나(매시 실행 세 번이 실패·누락) 설치 뒤 한 번도 성공하지 못한 채 failed 면 디스코드로 알린다(6시간마다, 다시 성공하면 한 번). 감시를 설치하기 전에는 실패해도 저널에만 남는다.
- 복구 확인(한 번, 개발 PC): `gcloud storage ls gs://beside-514511-backups/db/ | tail -n 1` 의 zip 을 받아 H2 로 열어 작업 수를 본다(FD5 훈련과 같은 `Restore` 절차).

**5. 스냅샷 일정(R2, gcloud)** — 2026-11-20 적용함 **[구현됨: 적용]**(위 '적용 기록'): `bash deploy/gcp/snapshot_schedule.sh`(다시 실행해도 있는 일정과 이미 붙인 디스크는 그대로 둔다). 다음 19:00 UTC 뒤 `gcloud compute snapshots list --filter="sourceDisk~/disks/beside-vm$"` 로 스냅샷을 확인한다(2026-11-25 확인: 2026-11-20 적용분이 만든 READY 5개, 2026-11-20~24 매일 19:14 UTC). 스냅샷은 전원을 내린 것과 같은 상태라, 복구한 VM 에서 H2 파일이 열리지 않으면 `storage/backup` 의 최신 zip 으로 되돌린다.

**6. 외부 가동 확인(R3, gcloud)**: 콘솔 Monitoring → Alerting → Edit notification channels 에서 메일 채널을 만든 뒤 `gcloud beta monitoring channels list --format='table(name.basename(),type,displayName)'` 로 id 를 보고 `bash deploy/gcp/uptime_check.sh create <id>`. 부하 시험·장애 훈련 동안은 `bash deploy/gcp/uptime_check.sh pause`, 끝나면 `resume`(가동 확인 자체는 계속 돌고 기록된다).

**7. VM 감시(R4, VM)** — 2026-11-20 05:34 UTC 적용함, 15:53 UTC 에 🧱 가 든 판으로 다시 설치함 **[구현됨: 적용]**(위 '적용 기록'). 다시 할 일은 VM 을 빈 디스크로 새로 만들 때와 리포의 스크립트가 바뀌었을 때(`install` 줄)뿐이다:

```bash
sudo install -m 755 deploy/watchdog/beside-watchdog.sh /usr/local/sbin/beside-watchdog
sudo install -m 644 deploy/watchdog/beside-watchdog.service deploy/watchdog/beside-watchdog.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now beside-watchdog.timer
systemctl list-timers beside-watchdog.timer
journalctl -u beside-watchdog -n 20 --no-pager      # 정상이면 아무것도 남기지 않는다
```

- 판단: backend 컨테이너 안에서 관리 포트 `/actuator/health`(DB·ping. Spring 의 디스크 여유 검사는 prod 에서 끈다 — 재시작해도 디스크는 비지 않고 진행 중 작업만 끝나므로, 디스크는 아래 80 % 경고가 맡는다. `ProdProfileTest`)를 묻고, 연속 3번(약 1~1.5분) 실패하면 `docker restart` + 디스코드. 10분에 한 번까지, 시작 90초 안은 판단하지 않음, 멈춘(`compose stop`)·재시작 중인 컨테이너는 손대지 않음. `/` 사용률 80 % 이상이면 1시간에 한 번 알린다. VM 밖 백업(4번)의 마지막 성공이 3시간 넘었거나 한 번도 성공하지 못하고 failed 면 6시간에 한 번 알린다(백업이 아직 설치되지 않았거나 실행 중이면 보지 않는다).
- **부하 시험·장애 훈련 전에** `sudo touch /etc/beside-maintenance`(또는 `~/fault/MAINTENANCE`), 끝나면 `sudo rm /etc/beside-maintenance`. 표시가 12시간 넘게 남으면 디스코드로 알린다. 표시가 있는 동안은 backend 검사를 통째로 건너뛰므로 재시작뿐 아니라 아래 재시작 반복 알림(🧱)도 오지 않는다 — FD5 를 다시 재현해 🧱 를 확인할 때는 표시 없이 한다. [loadtest/README.md](loadtest/README.md) 절차에 이 줄과 6번 pause/resume 을 넣는 것은 [계획]이다.
- 재시작하면 그때 PENDING·PROCESSING 이던 작업은 `INTERNAL_ERROR` 로 끝난다(앱은 retry). GPU·추론 서비스 상태는 보지 않는다(backend 의 GPU 알림이 맡는다).
- 재시작 반복: backend 가 시작하자마자 꺼지면(prod 에 H2 DB 파일이 없을 때 — 장애 훈련 FD5, 또는 잘못된 설정) Docker 가 계속 다시 띄우지만 90초를 넘기지 못해 위 판단에 걸리지 않는다. 마지막 정상 확인 뒤 Docker 의 `RestartCount` 가 3 이상 늘면 🧱 를 보내고(1시간마다 다시), 정상이 되면 한 번 알린다. 감시 스크립트의 `docker restart` 는 이 수를 늘리지 않는다 [구현됨: PR #22, `scripts/test_watchdog.sh`]. VM 에는 2026-11-20 15:53 UTC 에 위 설치 명령으로 다시 설치했다 [구현됨: 적용]. FD5 재현은 2026-11-21 5차 부하 시험에서 했다 **[실험 결과]**: 훈련 backend 가 DB 파일 없이 시작을 거부했고(재시작 10번), 따로 띄운 감시 인스턴스가 시작 44.6 s 뒤 🧱 를 VM 안 훈련 수집기로 보냈다([5차](../docs/perf/load/2026-11-21_vm-5th.md) 7절 FD5). 운영 웹훅(팀 디스코드)으로 🧱 를 받은 기록은 없다.

## Runpod Pod 쪽

2026-11-13 결정: GPU 추론은 Runpod Pod 에서 하고, VM 과는 **Pod 안에 띄운 Tailscale(userspace 모드)** 로 잇는다.

| 방법 | 판단 |
|---|---|
| Runpod HTTP 프록시(`https://<pod>-<포트>.proxy.runpod.net`) | 쓰지 않는다. 요청을 100초에서 끊는다(Runpod 문서). Spring 은 `/infer/upload` 를 최대 120초(`inference.timeout-ms`, 2026-11-21 안건 #7 로 600초에서 줄임) 기다려 이 시간 제한도 100초보다 길다. 실제 작업 한 건은 12~16 s 였다(exp-01 **[실험 결과]**) |
| Pod 의 TCP 포트 공개 + 토큰 | 쓰지 않는다. 사진과 토큰이 암호화 없이 인터넷을 지나고, Pod 를 다시 켜면 외부 포트가 바뀌어 그때마다 `.env` 를 고치고 backend 를 다시 띄워야 한다 |
| **Pod 안 Tailscale** | **고름.** 암호화되고 공개 포트가 없다. 노드 상태를 `/workspace` 에 두면 Pod 를 다시 만들어도 같은 100.x 로 돌아오고, 긴 요청(`inference.timeout-ms` 까지)도 그대로 지나간다. 처음 한 번 로그인 링크 승인이 필요하다 |

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

**다시 쓸 때마다**(새로 만든 Pod 든 정지했다 켠 Pod 든) 먼저 `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main origin/main` 으로 볼륨의 리포를 main 에 맞추고, 4번의 마지막 두 줄만 실행한다. `start_pod.sh` 는 리포를 당기지 않는다. `pull` 을 쓰지 않는 이유: 4차 부하 시험(2026-11-19 UTC) 시작 때 볼륨의 리포는 브랜치가 아닌 detached HEAD(3차 중에 바꾼 `b6c0e6c`)였다. detached HEAD 에서 `git pull` 은 인자 없이는 브랜치가 없다며 거부하고, `origin main` 을 주면 빨리감기는 해도 detached 인 채로 남는다(개발 PC git 2.54 로 확인). 볼륨의 커밋이 main 의 조상이 아니면(예: main 에 병합되지 않은 브랜치의 커밋) `--ff-only` 가 실패한다. `checkout -B main origin/main` 은 어느 상태에서든 `main` 브랜치를 origin 의 main 으로 맞춘다. 볼륨 리포에서 고친 파일이 없어야 한다(로컬 `main` 은 덮인다). 4차 때 이 명령으로 main `a9aa091` 에 맞췄다(PR #11 DELETE 취소·시작 기본값·요청별 시간 기록 포함). `git -C /workspace/Beside rev-parse --short HEAD` 가 VM 의 main 과 같은지 본다. 노드 상태가 `/workspace/tailscale` 에 있어서 로그인 없이 같은 100.x 로 돌아오고 VM `.env` 는 그대로 둔다. 이미 떠 있는 것은 다시 띄우지 않는다. 단, 떠 있는 서비스의 모드가 지금 모드와 다르면(패스스루 시험 중에 AnimalLift 를 설치한 경우) 그 서비스를 일부러 내리라고(`touch /workspace/beside-norestart && kill <pid>`, 아래 '서비스 자동 재시작') 알리고 멈춘다. 스크립트는 한 번에 하나만 돈다(앞의 실행이 끝나지 않았으면 거부). 한 볼륨을 두 Pod 가 동시에 쓰지 않는다(같은 노드 키로 서로 밀어낸다). 스크립트가 마지막으로 쓴 Pod id 를 기록해 두고, 다르면 알려 준다.

**추론 서비스 기본값**(2026-11-20 KST = 2026-11-19 16:33 UTC, `3b12c7b`): `start_pod.sh` 는 uvicorn 유휴 연결 75 s(`UVICORN_TIMEOUT_KEEP_ALIVE`)와 torch·BLAS 스레드 8(`THREADS` → `OMP/MKL/OPENBLAS_NUM_THREADS`)로 서비스를 띄운다 **[구현됨]**. 근거는 3차 부하 시험 2절이다: 처리 중앙값 −0.88 s, Pod CPU 한도에 걸린 100 ms 주기 0. 8 은 A40 Pod 의 cgroup CPU 한도(7.65 코어)에 맞춘 값이다. 다른 Pod 종류면 `THREADS` 를 그 Pod 의 cgroup CPU 한도(`cpu.cfs_quota_us / cpu.cfs_period_us` 또는 `cpu.max`, 반올림)로 준다. 콘솔이 보여 주는 vCPU 수(이 A40 Pod 는 9)가 아니다. `THREADS=default` 면 스레드 변수를 넣지 않는다(3차 G2r·F 와 같은 라이브러리 기본). backend 의 HttpClient 는 유휴 연결을 60 s 에 놓는다(`backend/Dockerfile` 의 `jdk.httpclient.keepalive.timeout`, JDK 기본 1200 s). 서비스(75 s)보다 먼저 놓아야, 이미 닫힌 연결로 `POST /infer/upload` 를 보내지 않는다. 값은 서비스를 띄울 때만 들어간다(감독 루프가 다시 띄울 때는 처음 값 그대로다). 이미 떠 있는 서비스가 다른 값이면 `[4/4]` 줄 다음에 NOTE 가 나오므로, 그 서비스를 일부러 내리고(`touch /workspace/beside-norestart && kill <pid>`) 다시 실행한다. `[4/4]` 줄에는 서비스 프로세스가 실제로 받은 값(`/proc/<pid>/environ`)이 나온다. 4차 부하 시험(2026-11-19 UTC, 새 A40 Pod)에서 실제 Pod 로 확인했다 **[실험 결과]**: `[4/4]` 줄에 keep-alive 75 s·threads 8 이 나왔고 `/proc/<pid>/environ` 에도 같은 값이 있었다. G0 에서 Pod CPU 한도에 걸린 주기는 0 이었고, 작업 연결 하나를 31건이 이어 썼다(작업 사이 유휴 약 22 s, [docs/perf/load/2026-11-20_vm-4th.md](../docs/perf/load/2026-11-20_vm-4th.md) 2절).

**서비스 자동 재시작**(2026-11-20, 장애 대응 R7) **[구현됨]**: 그전에는 서비스가 죽으면 누가 `start_pod.sh` 를 다시 실행할 때까지 꺼진 채로 남았다. 이제 `start_pod.sh` 는 서비스를 감독 루프 [runpod/supervise_inference.sh](runpod/supervise_inference.sh) 아래에서 띄운다.

- 서비스 프로세스가 끝나면(오류로 죽음, 메모리 부족으로 죽음, `kill -9`) 같은 환경변수·작업 폴더로 다시 띄운다. keep-alive·스레드·토큰·모드가 처음과 같다.
- 다시 띄우기 전 기다리는 시간은 5 s 다. 연달아 죽으면 두 배씩 늘고 60 s 에서 멈춘다(5 → 10 → 20 → 40 → 60 s). 한 번 300 s 넘게 돌았으면 다시 5 s 부터다. 값은 `RESTART_DELAY_S`·`RESTART_MAX_DELAY_S`·`RESTART_STABLE_S` 로 바꾼다.
- 프로세스가 끝났을 때만 다시 띄운다. 살아 있지만 멈춘 서비스는 그대로 둔다. 멈춘 서비스를 누가 알아채는지는 `/healthz` 가 답하느냐에 달렸다.
  - SIGSTOP 처럼 `/healthz` 도 답하지 못하면: backend 의 `InferenceHealthMonitor` 가 다음 검사(15 s 마다)에서 2 s 안에 답이 없어 DOWN 으로 읽는다. `NOTIFY_WEBHOOK_URL` 을 넣었으면 DOWN 이 45 s 이어진 뒤(`notify.down-grace-ms=45000`) 디스코드 GPU 알림이 간다. 실제 Pod 의 uvicorn SIGSTOP 은 장애 훈련 FD14(2026-11-20)에서 2.0 s 뒤 DOWN 으로 확인했다 **[실험 결과]**. 그때 웹훅은 블랙홀이라 디스코드로 받아 보는 것은 **[계획]** 이다.
  - `/healthz` 는 답하는데 추론만 멈추면: backend 는 UP 으로 보고 작업을 계속 보내며, 감독 루프도 프로세스가 살아 있어 그대로 둔다. 작업마다 `inference.timeout-ms`(120 s, 2026-11-21 안건 #7 — 이 값을 담은 main 을 VM 에 배포하기 전에는 600 s) 뒤 `INFERENCE_TIMEOUT` 으로 실패한다. 이것을 알아채는 것은 장애 대응 R6 의 멈춤 알림이다: `/healthz` 의 `busyForMs`(아직 도는 `predict`·패스스루 모의 생성이 시작한 뒤 ms)가 `inference.stuck-alert-ms`(기본 120 s)를 넘으면 웹훅을 넣었을 때 디스코드 🐢 가 한 번, 풀리면 🟢 가 한 번 간다(아래 '문제 해결'). 변환·리깅에서 멈춘 것은 `busyForMs` 에 들어가지 않아 작업 시간 초과 말고는 잡는 장치가 없다. 알림은 시험(`test_app.py`·`InferenceHealthMonitorTest`·`WebhookNotifierTest`·`InferenceAlertIntegrationTest`)으로 확인했고 **[구현됨]**, 2026-11-20 05:33 UTC VM 배포로 켰다 **[구현됨: 적용]**. 장애 훈련 2차 확인에서 VM 의 훈련 스텁(패스스루, 600 s 지연)으로 받았다 **[실험 결과]**: 작업을 보내고 2분 14초 뒤 🐢, 스텁을 다시 띄우자 🟢. 실제 모델이 멈춘 경우는 **[계획]** 이다.
  - 모델을 불러오지 못한 서비스(장애 훈련 FD18, 2026-11-20: 틀린 `BESIDE_ANIMALLIFT_CKPT` 로 `FileNotFoundError`)도 프로세스는 살아 있어 다시 띄우지 않는다. 서비스는 다시 불러오지 않고 `/healthz` 가 `modelLoaded=false` 로 답하므로 backend 는 LOADING 으로 보고 새 작업은 `inference.offline-wait-ms`(prod 30분)까지 PENDING 으로 기다린다. 2026-11-20 부터 `/healthz` 의 `loadError` 가 이유를 알려 주고 backend 가 디스코드 🧩 를 한 번 보낸다(아래 '문제 해결'). 시험으로 **[구현됨]**, 같은 날 VM 배포(05:33 UTC)로 backend 쪽을 켰고 Pod 쪽은 리포를 main 으로 맞춘 Pod 에서 돈다 **[구현됨: 적용]**. 장애 훈련 2차 확인에서 실제 A40 Pod 를 틀린 `BESIDE_ANIMALLIFT_CKPT` 로 띄워 받았다 **[실험 결과]**: 🟡 와 함께 🧩 "(FileNotFoundError)", 바르게 다시 띄우자 🧩 풀림 알림 뒤 40 s 만에 UP.
  - 어느 경우든 자동으로 다시 띄우지는 않는다. 멈춘 서비스는 `kill -9 $(cat /var/run/beside/inference.pid)` 로 끝내면 감독 루프가 5 s 뒤 같은 설정으로 다시 띄운다(SIGTERM 은 처리 중인 요청이 끝나기를 기다린다 — [loadtest/README.md](loadtest/README.md) F2 와 같은 이유).
- 기록: `/workspace/beside-logs/supervisor.log` 에 시작·종료·재시작마다 UTC 시각을 붙인 한 줄(끝난 pid, 종료 상태, 돈 시간, 몇 번째 재시작, 기다릴 시간)이 남는다. `inference.log` 에도 `=== <시각> supervise_inference.sh restart N ===` 줄이 붙는다.
- `/var/run/beside/inference.pid` 는 지금 떠 있는 uvicorn 프로세스다. model 모드의 `start_inference_wsl.sh` 는 uvicorn 을 `exec` 하므로 pid 가 같다(감독 루프의 바로 아래 자식이다. 아래 시험의 model 모드 부분으로 확인했고, 2026-11-20 장애 훈련 2차 확인에서 실제 A40 Pod(진짜 conda·uvicorn)에서도 `inference.pid` 의 서비스 pid 218 의 부모가 감독 루프 210 이었다 **[실험 결과]**). SIGSTOP·`kill` 은 이 pid 에 보낸다. 감독 루프의 pid 는 `/var/run/beside/supervisor.pid` 다.
- 감독 루프는 한 번에 하나만 돈다(`/var/run/beside/supervisor.lock` 을 잡는다). 서비스는 내려가 있고 감독 루프가 재시작을 기다리는 중이면 `start_pod.sh` 는 그 pid 를 알리고 멈춘다.
- 처음 띄울 때 서비스가 바로 끝나면(포트 충돌, conda 환경 없음 등) `start_pod.sh` 는 감독 루프도 끝내고 실패로 멈춘다. 망가진 설정을 끝없이 다시 띄우지 않게 하려는 것이다.
- 2026-11-20 전의 `start_pod.sh` 로 띄운 서비스에는 감독 루프가 없다. `start_pod.sh` 가 `NOTE: no supervisor watches it` 로 알린다. 일부러 내린 뒤 다시 실행하면 감독 아래에서 뜬다.

**일부러 내릴 때**(모드 바꾸기, 설정 바꾸기, F2·FD18 같은 훈련): 그냥 `kill` 하면 5 s 뒤 예전 설정으로 다시 뜬다. 표시 파일을 먼저 만든다.

```bash
touch /workspace/beside-norestart && kill $(cat /var/run/beside/inference.pid)   # F2 처럼 꺼진 채로 둘 때는 kill -9
bash /workspace/Beside/deploy/runpod/start_pod.sh                                # 다시 띄울 때. 표시 파일을 지운다
```

- 표시 파일이 있으면 서비스가 끝나도 다시 띄우지 않고, 감독 루프도 끝난다. 재시작을 기다리는 중에 만들어도 같다.
- `start_pod.sh` 는 서비스를 새로 띄울 때 표시 파일을 지우고 그렇게 했다고 출력한다. 파일이 볼륨(`/workspace`)에 있어 Pod 를 새로 만들어도 남기 때문이다. 지우지 않으면 다음 Pod 의 서비스도 재시작이 꺼진 채로 돈다.
- 서비스가 떠 있는데 표시 파일이 있으면 `start_pod.sh` 가 NOTE 로 알린다.
- 감독 루프와 서비스를 함께 내리려면 `kill $(cat /var/run/beside/supervisor.pid)` 한다. 감독 루프가 SIGTERM 을 서비스에 넘기고, 서비스가 끝나면 함께 끝난다.
- `ps` 에는 감독 루프의 인자에도 uvicorn 명령이 보인다. `pkill -f uvicorn` 은 둘 다 맞히므로 pid 로 보낸다.

확인한 것과 남은 것:

- **[실험 결과]** 2026-11-20 장애 훈련 2차 확인, 실제 A40 Pod `03rnszh20mywif`(리포 main `ce1a685`): 감독 루프 pid 210, 서비스 pid 218(부모 210). 05:48:25.2 `kill -9` → `supervisor.log` "exited (status 137, signal 9) after 79 s; restart 1 in 5 s", 05:48:30 새 pid 315 → backend DOWN 05:48:35, LOADING 05:48:51, UP 05:49:06.3(kill 에서 41 s, Pod·VM 시계를 섞은 값). 표시 파일을 만든 뒤의 `kill` 에는 "norestart exists - not restarting" 으로 다시 띄우지 않았다. 기록은 [docs/ops/2026-11-20_failure-drills.md](../docs/ops/2026-11-20_failure-drills.md) '2차 확인'.
- **[구현됨]** WSL(Ubuntu 26.04, bash 5.3)에서 [runpod/test_start_pod_supervisor.sh](runpod/test_start_pod_supervisor.sh) 127개 검사가 통과했다(가짜 서비스·가짜 Tailscale·가짜 venv·가짜 conda 환경, 127.0.0.1 밖으로 나가지 않음). 같은 시험을 6개 동시에 돌려도 통과했다.
  - 감독 루프만: `kill -9` 뒤 새 pid 로 다시 뜸, pid 파일이 새 프로세스를 가리킴, SIGSTOP 에는 다시 띄우지 않음, 기다리는 시간 1·2·4·4 s(시험 값: 처음 1 s, 상한 4 s), 오래 돈 뒤에는 처음 값으로 돌아감, 표시 파일(끝날 때·기다리는 중), 두 번째 감독 루프 거부, SIGTERM 전달(멈춘 서비스도), 감독 루프를 `kill -9` 해도 서비스가 잠금을 쥐지 않음.
  - `start_pod.sh` 와 함께(passthrough): `[4/4]` 줄의 설정, 두 번 띄우지 않음, 다시 뜬 서비스의 환경변수·작업 폴더가 처음과 같음, 모드가 다르면 거부, 재시작을 기다리는 감독 루프가 있으면 거부, 표시 파일을 지우고 다시 띄움, 시작하자마자 끝나는 서비스면 감독 루프도 끝냄, 감독 없는 서비스 알림.
  - `start_pod.sh` 와 함께(model, 2026-11-20 검토 반영): 진짜 `scripts/start_inference_wsl.sh` 가 가짜 conda 환경(`conda activate` 를 기록하고 그 환경의 `python -m uvicorn` 이 가짜 서비스를 띄움)으로 뜬다. `inference.pid` 가 uvicorn 명령의 프로세스이고 감독 루프의 바로 아래 자식임, 서비스 환경의 `BESIDE_ANIMALLIFT_DIR`·`BESIDE_WORK_DIR`·`CONDA_HOME`·`PORT`·`BESIDE_HOST`·keep-alive·스레드·토큰, `kill -9` 뒤 같은 감독 루프가 `start_inference_wsl.sh` 를 다시 돌려(conda 활성화 두 번, 설정 줄 두 번) 같은 값으로 띄움, 표시 파일 + `kill` 로 일부러 내리면 그대로 꺼져 있음.
  - 시험이 잡는지 일부러 고장 낸 판으로 확인했다: 8가지(잠금 상속, 표시 파일 무시, 두 배 안 함, 처음 값으로 안 돌아감, 예전 `start_pod.sh`, 시작 실패 때 감독 루프를 안 끔, 표시 파일을 안 지움, SIGTERM 을 안 넘김)가 모두 실패로 나왔다. model 부분은 3가지(`start_inference_wsl.sh` 에서 `exec` 를 뺌, `start_pod.sh` 가 `ANIMALLIFT_DIR` 를 넘기지 않음, 감독 루프가 다시 띄울 때 `PORT` 를 잃음)가 각각 검사 9·4·5개 실패로 나왔고, 실패한 실행 뒤에도 남은 프로세스가 없었다.
- 실제 Pod 의 `kill -9` 자동 복구와 그동안의 알림은 위 2차 확인 [실험 결과]로 쟀다(41 s, 45 s 유예 안이라 🟡·🟢 만). 부하 중 변형([loadtest/README.md](loadtest/README.md) F2, 표시 파일 없는 `kill -9`)은 **[계획]**.

**네트워크 볼륨이 아니라 Pod 볼륨 디스크로 만든 Pod 를 Terminate 했을 때**는 `/workspace` 가 지워져 새 노드가 된다. 다시 로그인하고, Tailscale 관리 콘솔에서 옛 `beside-gpu` 노드를 지운 뒤, VM 의 `INFERENCE_BASE_URL` 을 새 주소로 바꾼다.

| `MODE` | 하는 일 |
|---|---|
| `passthrough`: `/workspace/AnimalLift` 가 없을 때의 기본값 | `/workspace/beside-venv` 에 `requirements.txt` 를 설치하고 고정 GLB 를 돌려준다. 연결 시험용이다. `/workspace/beside-sample.glb` 가 없으면 삼각형 하나짜리 GLB 를 만든다. 진짜 강아지를 보이려면 그 자리에 GLB 를 올려 둔다 |
| `model`: `/workspace/AnimalLift` 가 있을 때의 기본값 | `scripts/start_inference_wsl.sh` 로 AnimalLift 를 띄운다. `nvidia-smi` 에 GPU 가 없으면 시작하지 않는다(GPU 없이 다시 켜진 Pod 에서 모델이 CPU 로 조용히 올라가 모든 작업이 `inference.timeout-ms`(120초, 전에는 600초) 뒤 실패하는 것을 막는다). conda(`/workspace/miniconda3`, env `animallift`)·AnimalLift·체크포인트·리깅 템플릿(`/workspace/beside-rig/template-rigged.glb`)은 모두 `/workspace` 아래에 둔다. 설치는 [runpod/install_animallift.sh](runpod/install_animallift.sh)(`mkdir -p /workspace/beside-logs && nohup bash /workspace/Beside/deploy/runpod/install_animallift.sh > /workspace/beside-logs/install.log 2>&1 &`, 진행은 `install.status`). `/workspace/AnimalLift.installing` 에 받고 모두 끝난 뒤에만 `/workspace/AnimalLift` 로 옮긴다. 자세한 것은 [ANIMALLIFT_SETUP.md](../generation/inference_service/ANIMALLIFT_SETUP.md) |

파일 위치:

| 경로 | 내용 |
|---|---|
| `/workspace/tailscale/` | 바이너리와 노드 상태(노드 키, 권한 700), 마지막 Pod id |
| `/workspace/beside-token` | 추론 토큰(권한 600). `BESIDE_INFERENCE_TOKEN` 환경 변수가 있으면 그쪽이 먼저다 |
| `/workspace/.cache/` | SD VAE·DINOv2 가중치 캐시(`HF_HOME`, `TORCH_HOME`). 없으면 컨테이너 디스크에 받아 정지할 때마다 다시 받는다 |
| `/workspace/beside-logs/` | `start.log`, `tailscaled.log`, `tailscale-up.log`, `inference.log`, `supervisor.log`(서비스 시작·종료·재시작). 실행마다 시각 표시를 붙여 이어 쓴다 |
| `/workspace/beside-norestart` | 있으면 감독 루프가 끝난 서비스를 다시 띄우지 않는다(일부러 내릴 때 만든다). `start_pod.sh` 가 서비스를 새로 띄울 때 지운다 |
| `/workspace/beside-work/` | 작업 폴더(Spring 이 GLB 를 받은 뒤 `DELETE` 로 지운다). 남은 폴더는 `BESIDE_WORK_SWEEP_MINUTES` 로 지울 수 있다(`start_pod.sh` 는 켜지 않는다) |
| `/var/run/beside/` | 소켓, pid(`inference.pid` 는 uvicorn, `supervisor.pid` 는 감독 루프, `tailscaled.pid`), 잠금. 정지하면 사라진다 |

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

앱 코드 변경 없이 주소만 바꾼다: 디자인 UI 홈 맨 아래 **서버 설정**(2026-11-22 시안 E 부터. 그 전 빌드는 홈 오른쪽 위) → `https://<도메인>` 입력 → **연결 확인**(healthz 가 `real,prod`) → **저장**. HTTPS 이므로 개발용 "Allow downloads over HTTP" 가 필요 없다. 주소를 바꾸면 앱의 이전 작업 기록은 지워진다([unity/README.md](../unity/README.md)).

## 문제 해결

| 증상 | 확인 | 조치 |
|---|---|---|
| 브라우저 인증서 경고, caddy 로그에 `could not get certificate` | `dig +short <도메인>` 이 VM IP 인지, 클라우드 방화벽 80·443 | DNS·방화벽을 고치고 `... restart caddy`. `caddy_data` 볼륨을 지우며 반복하지 않는다(Let's Encrypt 실패 횟수 제한) |
| 맞는 비밀번호인데 계속 401 | `... exec caddy printenv BASIC_AUTH_HASH` 가 `$2a$14$` 로 시작하는 완전한 해시인지 | `.env` 에서 작은따옴표로 감싸고 `up -d`(환경변수가 바뀌면 caddy 가 새로 만들어진다) |
| 업로드 413 | 응답 본문이 v1 오류 봉투(`PAYLOAD_TOO_LARGE`)면 Spring(장당 5MB·요청 20MB), 본문이 비었으면 Caddy(25MB) | 사진을 줄인다. 상한을 바꾸려면 Spring 설정과 Caddyfile 을 함께(계약 회의) |
| 502 Bad Gateway | `... ps` 에서 backend 상태, `... logs backend` | 기동 중(첫 30~60초)이면 기다린다. 종료됐으면 로그의 원인(DB 잠김·메모리) |
| 작업이 `INFERENCE_UNAVAILABLE` | Pod(또는 GPU PC)가 켜져 있는지, Pod 를 다시 켠 뒤 `start_pod.sh` 를 실행했는지, `tailscale status`, VM 호스트와 backend 컨테이너에서 각각 `curl http://<GPU 쪽 100.x>:<8002 또는 8001>/healthz`, Pod 의 `/workspace/beside-logs/`(`supervisor.log` 에 서비스가 죽고 다시 뜬 기록) | prod 는 GPU 가 응답하지 않으면 30분 동안 PENDING 으로 기다린 뒤 이 코드로 끝난다(`inference.offline-wait-ms`). 호스트에서는 되는데 컨테이너에서만 안 되면 `docker-compose.yml` 의 backend 주석대로 호스트 네트워크로 옮긴다 |
| Discord 🔑 "백엔드의 추론 토큰을 받지 않아요" (healthz `tokenOk=false`) | backend 컨테이너 안에서 `sh -c 'curl -sS -H "X-Inference-Token: $INFERENCE_TOKEN" http://<GPU 쪽 100.x>:<포트>/healthz'` 의 `tokenOk`(토큰은 출력되지 않는다). VM `deploy/.env` 의 `INFERENCE_TOKEN` 과 Pod `/workspace/beside-token` 중 어느 쪽이 바뀌었는지 | 이 동안 새 작업은 기다리지 않고 바로 `INFERENCE_UNAVAILABLE`(401)로 끝난다. 틀린 쪽을 고친다: Pod 는 위 '처음 한 번' 4번의 토큰 줄로 다시 쓰고 `touch /workspace/beside-norestart && kill $(cat /var/run/beside/inference.pid)` 뒤 4번의 마지막 두 줄(감독 루프는 처음 토큰으로 다시 띄우므로 표시 파일 없이 `kill` 만 하면 토큰이 바뀌지 않는다. 'Runpod Pod 쪽' '서비스 자동 재시작'), VM 은 `up -d backend`. Pod 쪽을 고치면 약 15초(`inference.health-poll-ms`) 안에 🔑 "다시 받아요" 가 온다. backend 를 다시 띄운 경우 새 backend 는 앞 알림을 모르므로 회복 메시지 없이 🔑 가 다시 오지 않는 것으로 확인한다. 실패한 작업은 앱에서 다시 시도 |
| Discord 🐢 "작업 하나가 …째 끝나지 않아요" (healthz `busyForMs` > `inference.stuck-alert-ms`, 기본 120000) | Pod `/workspace/beside-logs/inference.log` 의 마지막 `job …` 줄, `nvidia-smi`, `curl -sS http://<GPU 쪽 100.x>:<포트>/healthz` 의 `busyForMs` 가 계속 느는지 | 그 작업은 `inference.timeout-ms`(120초, 2026-11-21 안건 #7 — 이 값을 VM 에 배포하기 전에는 600초) 뒤 `INFERENCE_TIMEOUT` 이 되고, 뒤 작업도 GPU 잠금을 기다리다 차례로 시간 초과된다. 🐢 기준(120 s)이 시간 제한과 같아 🐢 가 올 때쯤 첫 작업은 이미 실패했을 수 있다 [계산]. `predict` 는 DELETE 로도 멈추지 않으므로 몇 분 더 늘면 Pod 에서 `kill -9 $(cat /var/run/beside/inference.pid)` 한다(SIGTERM 은 처리 중인 요청을 기다린다). 감독 루프가 5 s 뒤 같은 설정으로 다시 띄운다(모델을 다시 불러오는 동안 🟡, 'Runpod Pod 쪽' '서비스 자동 재시작'). 2026-11-20 전 `start_pod.sh` 로 띄워 감독 루프가 없으면 4번의 마지막 두 줄로 다시 띄운다. 변환·리깅에서 멈춘 것은 `busyForMs` 에 들어가지 않아 🐢 가 오지 않는다(작업 시간 초과만). 🟢 "오래 걸리던 작업이 끝났어요" 가 오면 풀린 것이다 |
| Discord 🧩 "모델을 불러오지 못했어요 (<예외 이름>)" (healthz `modelLoaded=false` + `loadError`, 🟡 뒤 🟢 가 오지 않음) | Pod 에서 `curl -sS http://127.0.0.1:8002/healthz` 의 `loadError`, `/workspace/beside-logs/inference.log` 의 `model failed to load` 와 traceback(경로는 여기에만 있다), 체크포인트(`BESIDE_ANIMALLIFT_CKPT`, 없으면 `/workspace/AnimalLift/ckpt/latest.pt`)가 있는지, `nvidia-smi` | 서비스는 다시 불러오지 않고, 감독 루프도 프로세스가 살아 있어 다시 띄우지 않는다(장애 훈련 FD18). 그동안 새 작업은 PENDING 으로 기다리다 `inference.offline-wait-ms`(prod 30분) 뒤 `INFERENCE_UNAVAILABLE` 로 끝난다. 원인을 고친 뒤 다시 띄운다: 파일만 고쳤으면 `kill $(cat /var/run/beside/inference.pid)`(감독 루프가 5 s 뒤 같은 환경변수로 다시 띄운다), 환경변수(`BESIDE_ANIMALLIFT_CKPT` 등)를 바꿔야 하면 `touch /workspace/beside-norestart && kill $(cat /var/run/beside/inference.pid)` 뒤 새 값을 넣고 4번의 마지막 두 줄. 다시 불러오기 시작하면 🧩 "풀렸어요", 모델이 올라오면 🟢. 그동안 실패한 작업은 앱에서 다시 시도 |
| Pod `supervisor.log` 에 `restart N in 60 s` 가 이어짐(서비스가 뜨자마자 죽음) | `inference.log` 에서 각 `supervise_inference.sh restart` 줄 바로 앞의 오류(포트, CUDA, conda, 토큰) | 감독 루프는 60 s 마다 계속 다시 띄운다. `kill $(cat /var/run/beside/supervisor.pid)` 로 멈추고, 원인을 고친 뒤 `start_pod.sh` 를 실행한다 |
| `permission denied ... docker.sock` | `groups` 에 docker 가 있는지 | `sudo usermod -aG docker $USER` 뒤 다시 로그인 |
| `required variable ... is missing` | `deploy/.env` 에 그 변수 | 견본(`.env.example`)의 모든 변수를 채운다. `--env-file deploy/.env` 를 빠뜨리지 않는다 |
| grafana 가 계속 재시작, 로그에 `GRAFANA_ADMIN_PASSWORD 를 넣는다` | `deploy/.env` 의 `GRAFANA_ADMIN_PASSWORD` | 값을 넣고 `$M up -d grafana` |
| `/grafana/` 가 Basic 인증 뒤 502 | `$M ps` 에 grafana 가 있는지 | monitoring 프로필을 안 띄웠다: `$M up -d`. 쓰지 않을 거면 그대로 둬도 다른 경로에는 영향 없다 |
| Basic 인증은 통과했는데 Grafana 가 401 · 'invalid username or password' | Caddyfile 의 `/grafana*` reverse_proxy 에 `header_up -Authorization` 이 있는지 | Caddy 가 팀 계정 헤더를 넘기면 Grafana 가 그것을 자기 계정으로 검사한다. 헤더를 지우고 `... restart caddy` |
| 대시보드가 비어 있음 · Prometheus `up` 이 0 | `$M exec backend curl -fsS http://localhost:8081/actuator/prometheus \| head` | 응답이 없으면 그 이미지에 Actuator(관리 포트 8081)가 없다 — 최신 main 으로 다시 빌드. 응답은 있는데 패널만 비면 대시보드 상단 `env` 가 `prod` 인지 |
