# 장애 대응 런북

장애마다 감지, 자동 복구, 사람이 할 일, 앱에 보이는 것, 남은 일을 적는다.

- 근거: [2026-11-20 장애 훈련](2026-11-20_failure-drills.md)(FD 번호)과 그 전 부하 시험(F1a·F1b-real·F2-real), 그 뒤 [5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 7절의 다시 재현(FD16b·F1b·FD9·FD5).
- 이중화 결정: [REDUNDANCY.md](REDUNDANCY.md).
- 표기:
  - [구현됨] 코드·시험이 리포에 있다(운영 VM·Pod 에 적용됐는지는 따로 적는다).
  - [적용] 운영에 켜 두었다.
  - [실험 결과] 훈련에서 쟀다. [실험 결과 2차] 대응을 배포한 뒤 2차 확인 창(2026-11-20 05:37~05:53 UTC)에서 쟀다. [실험 결과 5차] 5차 부하 시험 안의 장애 재현(2026-11-21 03:13~03:51 UTC, main `fe10683`, [결과](../perf/load/2026-11-21_vm-5th.md) 7절)에서 쟀다.
  - [계획] 하지 않음.
- 대응 코드(R1~R7, FD18 알림)는 PR #20 으로 main(`ce1a685`)에 들어갔다. 적용은 2026-11-20 에 나눠서 했다: 버킷·백업 타이머·메타데이터 차단(R1)과 스냅샷 일정(R2)은 1차 훈련 전후에, 배포(backend 의 R1 매시 백업·R6·FD18 알림, Caddy 의 R5)는 05:32~05:33 UTC, VM 감시(R4)는 05:34 UTC 에 했다. R3 는 [계획]. R7 은 VM 이 아니라 Pod 쪽이고, main 의 `start_pod.sh` 로 Pod 를 켤 때마다 들어간다.
- 남은 장애 고침(FD16b·FD5·FD4·FD9)은 PR #22 로 main(`7230dea`)에 들어갔고, 2026-11-20 15:50:40~15:53:14 UTC 에 VM 에 배포, 15:53 UTC 에 VM 감시(🧱 가 든 판)를 다시 설치했다 [적용]. 추론 서비스의 healthz `instanceId` 는 Pod 쪽이라 Pod 를 main 으로 켤 때 들어간다(5차의 Pod 는 main `fe10683` 으로 켜 `instanceId` 를 냈다). FD16b·FD5·FD9 는 2026-11-21 5차 부하 시험 때 운영 VM 의 훈련 backend 로 다시 재현했다 [실험 결과 5차]. FD4 는 다시 재현하지 않았다 [계획].
- `inference.timeout-ms` 120 s(2026-11-21 안건 #7)는 2026-11-21 01:07 UTC main `fe10683` 배포로 운영 VM 에 들어갔다 [적용]([5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 1절). 그 전 운영은 600 s 였다.

## 먼저 볼 곳

| 무엇 | 어디서 |
|---|---|
| 공개 경로 | `curl -s https://beside-team.duckdns.org/api/v1/healthz` → `{"status":"ok","profile":"real,prod",...}` |
| backend 상태 | VM `docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps`. DB 까지 보려면 `exec -T backend curl -s localhost:8081/actuator/health` |
| GPU 상태 변화 | backend 로그 `inference service A -> B`, 디스코드 GPU 알림 |
| 대기열 | `exec -T backend curl -s localhost:8081/actuator/prometheus \| grep ^beside_jobs_queue` |
| VM 감시(R4) | `journalctl -u beside-watchdog -n 20 --no-pager`(정상이면 비어 있다), 디스코드 🔁(재시작)·✅(재시작 뒤 정상) |
| Pod | Runpod 콘솔, Pod `/workspace/beside-logs/{inference,start,supervisor}.log`, `curl -s 127.0.0.1:8002/healthz` |

## 시나리오별 대응

### VM·backend·앞단

| 장애 | 감지 | 자동 | 사람이 할 일 | 앱에 보이는 것 |
|---|---|---|---|---|
| backend 죽음(FD1) | 외부 가동 감시 R3 [계획: 채널] | `restart: unless-stopped`, 19 s [실험 결과] | 없음. 진행 중이던 작업은 `INTERNAL_ERROR` 라 앱에서 다시 시도 | 잠깐 502, 진행 중 작업 실패 |
| backend 멈춤(FD2) | 1차: Docker unhealthy 101 s, **재시작은 없음** [실험 결과]. R4 감시 스크립트가 actuator health 3번(30 s 간격) 실패면 재시작 + 디스코드 🔁 [적용] 2026-11-20 05:34 | R4 가 멈춘 지 112 s 뒤 재시작 [실험 결과 2차]. 정상까지는 재지 못했다(훈련 스크립트가 곧바로 backend 를 다시 만들어서). 부팅 14~15 s 를 더해 약 2분 [계산] | 보통 없음. 🔁 를 받으면 원인을 본다. 점검 표시(`/etc/beside-maintenance`)가 있거나 감시가 꺼져 있으면 `docker restart beside-backend-1`. 멈춘 동안 쌓인 POST 가 SIGCONT 뒤 처리될 수 있다 | 응답 없음(1차 189 s, R4 로 약 2분) |
| Caddy 죽음(FD7) | R3 | 재시작 1.4 s [실험 결과]. 인증서는 `caddy_data` | 없음. `caddy_data` 는 지우지 않는다(Let's Encrypt 한도) | 1~2 s 연결 끊김 |
| VM 재시작·호스트 유지보수(FD3) | R3 | GCE 자동 재시작·실시간 이전(켜짐). 컨테이너·Tailscale 자동 복귀 [실험 결과] | 없음. 외부 82.8 s [실험 결과] | 1~2분 연결 실패 |
| 디스크 가득 참(FD4) | **healthz 는 200** [실험 결과]. R4 가 디스크 80 % 이상이면 시간당 1번 알림 [적용] 2026-11-20 05:34, 재현은 안 함 | 없음. 비우면 재시작 없이 회복 [실험 결과] | 공간 확보(오래된 `results/`·`uploads/` 정리는 보존 정책 안건 #23 — 2026-11-21 결정: 사진 7일·GLB 30일, 자동 삭제는 [계획]이라 그 전에는 손으로), 그다음 실패한 작업 다시 시도 | 1차: 2 MB 남았을 때 `CONVERSION_FAILED`(원인과 다른 코드), 0 이면 업로드 500. 이제 GLB 를 쓰거나 결과 폴더로 복사하지 못하면 `INTERNAL_ERROR`("서버에 문제", 다시 시도 가능) [구현됨: PR #22] [적용] 2026-11-20 15:53, 재현은 [계획]. 업로드 500 은 그대로(이미 `INTERNAL_ERROR`) |
| DB 파일 손상·유실(FD5) | 1차: **없음**, H2 가 빈 DB 를 만들고 healthz 200 [실험 결과]. 이제 prod backend 는 DB 파일이 없으면 시작하지 않는다(로그 `H2 database file ... does not exist`) [구현됨: PR #22] [적용] 2026-11-20 15:53(컨테이너 `STORAGE_DB_CREATEIFMISSING=false` 확인). 시작하자마자 꺼지는 것을 Docker 가 계속 다시 띄우면 R4 가 🧱 "시작하자마자 계속 꺼져요" 를 보낸다 [적용] 2026-11-20 15:53 다시 설치. 5차 재현: 훈련 backend 가 DB 파일 없이 시작을 거부했고(10번 다시 시작, 빈 DB 없음), 따로 띄운 감시 인스턴스(웹훅 = 훈련 수집기, 점검 표시 무시)가 44.6 s 뒤 🧱 를 보냈다 [실험 결과 5차]. 운영에 설치한 감시로 받은 것은 아니다. 점검 표시 `/etc/beside-maintenance` 가 있으면 🧱 도 오지 않는다 | 없음 | 아래 "DB 복구". `STORAGE_DB_CREATE_IF_MISSING=true` 로 띄우지 않는다(빈 DB 로 뜬다) | 1차: 기존 작업이 404. 이제 복구할 때까지 접속 실패(502) [실험 결과 5차] |
| VM·디스크·zone 통째 유실(FD6) | R3 | 없음 | 아래 "VM 다시 세우기". 훈련에서 서비스 응답까지 89 s, 운영 설정까지 145 s [실험 결과] | 복구까지 접속 불가 |
| TLS 갱신·DuckDNS 장애(FD8) | 인증서 만료·이름 풀이 실패 | Caddy 가 자동 갱신(포트 80·443·DNS 필요) | DuckDNS 웹에서 A 레코드 확인. 재현은 하지 않음 [계획] | 연결 실패 |

### VM↔GPU 경로·추론 서비스·GPU

| 장애 | 감지 | 자동 | 사람이 할 일 | 앱에 보이는 것 |
|---|---|---|---|---|
| tailnet 끊김(FD10, 3차의 50~83 s) | 14 s 뒤 DOWN, 디스코드는 45 s 유예 뒤 [실험 결과] | 오프라인 대기(30분)가 버팀. 진행 중 작업도 TCP 재전송으로 살아남 [실험 결과] | 길면 `tailscale status`·Pod 상태 확인 | 기다림(최대 122 s 관찰) |
| 느리고 손실 많은 경로(FD11) | 없음(UP) | 없음. 느려질 뿐 실패 없음 [실험 결과] | 오래가면 Tailscale 경로(직접/DERP) 확인 | 기다림(최대 112 s 관찰) |
| 추론 서비스 전체 멈춤(FD14) | 2 s 뒤 DOWN [실험 결과] | 풀리면 1.5 s 뒤 UP | 계속 멈춰 있으면 Pod 에서 서비스 재시작(R7 은 살아 있는 프로세스는 건드리지 않는다) | 기다림 |
| 추론 서비스 죽음(F2-real, FD12) | DOWN. 45 s 안에 돌아오면 DOWN 알림 없이 🟡·🟢 만 [실험 결과 2차] | R7 감독 루프가 5 s 뒤 다시 띄움 [적용]. 실제 Pod 에서 `kill -9` → UP 41 s, 사람 손 없음 [실험 결과 2차]. 그동안 오프라인 대기가 기다림 | 보통 없음. 감독 루프가 없는 서비스(2026-11-20 전 `start_pod.sh` 로 띄움)면 `start_pod.sh` 다시. 일부러 내려 둘 때는 `touch /workspace/beside-norestart` | 기다림, 30분 넘으면 `INFERENCE_UNAVAILABLE` |
| healthz 는 UP, 추론만 멈춤(FD13) | 1차에는 **없음** [실험 결과]. R6 의 `busyForMs` 가 120 s 를 넘으면 🐢 알림 [적용]. 스텁 작업 시작 2분 14초 뒤 🐢, 풀리면 🟢 [실험 결과 2차]. 변환·리깅 멈춤은 못 본다 | 없음. 작업마다 `inference.timeout-ms` 시간 초과: 120 s(2026-11-21 #7, 운영 VM 은 2026-11-21 `fe10683` 배포부터 [적용]. 그 전에는 600 s) | 🐢 를 받으면 Pod 에서 서비스 재시작. 2차에서 스텁을 다시 띄우자 멈춰 있던 작업은 `INFERENCE_UNAVAILABLE` 로 끝났다 [실험 결과 2차] | 작업마다 2분(옛 값이면 10분) 뒤 `INFERENCE_TIMEOUT`, 앱은 600 s 에 포기. 🐢 기준도 120 s 라 🐢 가 올 때쯤 첫 작업은 이미 실패했을 수 있다 [계산]. 5차에서 스텁 180 s·시간 초과 120 s 로 돌리자 실패와 🐢 가 31 ms 차이로 왔고, 다음 작업은 완료됐다(연쇄 실패 없음) [실험 결과 5차] |
| 토큰 불일치(FD17) | 1차에는 작업 실패 웹훅뿐 [실험 결과]. R6 의 `tokenOk=false` 면 🔑 알림 [적용]. 토큰을 바꾸고 9.8 s(스텁 재시작 포함) 뒤 🔑, 되돌리고 4.3 s 뒤 🔑 풀림 [실험 결과 2차] | 없음 | VM `.env` 의 `INFERENCE_TOKEN` 과 Pod Secret 을 맞춘다. 출력하지 말고, 새로 만들었으면 둘 다 바꾼다 | 모든 작업이 바로 `INFERENCE_UNAVAILABLE` |
| 모델 로드 실패(FD18) | 1차에는 LOADING 에 머물러 구별 안 됨 [실험 결과]. healthz 의 `loadError` 가 생기면 🧩 알림 [적용]. 실제 Pod 에서 🟡 와 함께 🧩 "(FileNotFoundError)" [실험 결과 2차] | 없음. 작업은 30분 기다린다 | `inference.log` 의 오류(체크포인트·볼륨) 확인 → 바로잡고 재시작(표시 파일 + `kill`, 그다음 `start_pod.sh`). 다시 시작 뒤 UP 1차 52.6 s, 2차 40 s [실험 결과] | 기다림, 30분 뒤 실패 |
| GPU 메모리 부족(FD15) | GPU 상태 알림 없음. 작업 실패 웹훅만 [실험 결과] | 메모리가 풀리면 재시작 없이 회복 [실험 결과] | `nvidia-smi` 로 다른 프로세스 확인 | 그 사이 `INFERENCE_FAILED`, 앱에서 다시 시도 |
| Pod 정지·회수(FD16a·b, 2~4차) | 30.6 s 뒤 DOWN [실험 결과], 5차는 정지 요청 21.2 s 뒤 [실험 결과 5차] | 1차: 없음. 이제 끊긴 GLB 내려받기는 60 s 동안 데이터가 없으면 끊고(`inference.download-idle-ms`), 다시 켠 서비스의 healthz `instanceId` 가 바뀌면 그 전에 보낸 요청을 바로 포기한다(prod) [구현됨: PR #22]. backend 쪽은 [적용] 2026-11-20 15:53, `instanceId` 는 Pod 를 main 으로 켠 추론 서비스가 낸다(옛 서비스면 60 s 내려받기 제한만 동작) | 아래 "GPU Pod 다시 세우기" | 기다림. 1차: 정지 직후 보낸 작업 하나가 600 s 슬롯을 잡아 최대 602 s [실험 결과]. 이제 그 작업은 내려받는 중이었다면 늦어도 60 s 뒤 `CONVERSION_FAILED`(POST 중이었다면 `instanceId` 를 내는 서비스로 Pod 가 돌아온 뒤 `INFERENCE_UNAVAILABLE` — 옛 서비스면 POST 는 `inference.timeout-ms` 까지 기다린다: 120 s(2026-11-21 #7))로 끝나고 다음 작업이 슬롯을 받는다 [계산: 시험으로 확인]. 5차에서 실제 Pod 를 정지하고 같은 Pod 를 다시 켜자 `instanceId` 길로 끝났다: 처리 중이던 1건이 52 s 만에 `INFERENCE_UNAVAILABLE`, 기다린 5건 모두 완료, 최대 74.3 s [실험 결과 5차]. 60 s 내려받기 길과 시간 초과 길은 재현하지 않았다 [계획] |
| Runpod 재고 없음 | Pod start·create 실패 | 없음 | 배치 조건(`minVcpuCountPerGpu 4`, `minRamPerGpu 24`)을 주고 새 Pod. 다른 데이터센터는 볼륨이 묶여 있어 바로는 못 쓴다([REDUNDANCY.md](REDUNDANCY.md)) | GPU 없음 → 기다리다 실패 |

### 알림·감시

| 장애 | 지금 | 대응 |
|---|---|---|
| 디스코드 웹훅 실패(FD9) | 작업은 안 막힘. 1차: 알림은 5 s 시간 초과로 하나씩 밀렸다(8분에 32건) [실험 결과] | GPU 상태·알림을 작업 결과보다 먼저 보낸다. 실패하면 5 s 에서 두 배씩(최대 5분) 쉬며 다시 해 본다(429 는 Retry-After). 실패한 GPU 알림은 다시 보내고(8번까지), 실패한 작업 결과는 버리고 세어 두었다가 다시 보내지면 📭 한 줄로 알린다. 3건 이상 밀린 작업 결과는 📦 한 메시지로 묶고, 1분 넘게 늦은 메시지에는 그 시각(⏱)을 붙인다 [구현됨: PR #22] [적용] 2026-11-20 15:53. 5차 재현(훈련 수집기 240 s `docker pause` + GPU DOWN/UP): 재시도 간격 5 → 160 s, 회복 뒤 GPU 메시지가 먼저, 그다음 📭·📦, GPU 메시지 손실 0, 작업 55건 모두 완료 [실험 결과 5차]. 받는 쪽이 요청을 받아 둔 채 멈추면 늦게 닿은 것과 다시 보낸 것이 겹쳐 중복이 생긴다('적어도 한 번') [실험 결과 5차]. 연결 자체가 안 되는 블랙홀은 재현하지 않았다 [계획]. VM 감시(R4)의 웹훅은 따로다 |
| backend·VM 이 죽어 알림 자체가 없음 | backend 웹훅은 자기와 함께 멈춘다. backend 가 멈추면 VM 감시(R4)가 재시작하며 🔁 를 보낸다 [적용, 실험 결과 2차]. VM·Caddy 가 죽으면 알림이 없다 | R3 외부 가동 감시(1분, 2개 지역 2분 실패 → 메일) [계획: 사용자가 콘솔에서 알림 채널을 만든 뒤 `deploy/gcp/uptime_check.sh create <channel>`] |

## 절차

### DB 복구 (FD5 에서 연습, 20 s)
1. `docker compose ... stop backend`
2. 어느 백업으로 돌아갈지 고른다.
   - VM 안 `storage/backup/beside-*.zip`(매시 5분, 48개 [적용] 2026-11-20 05:33 UTC 배포, 설정 `0 5 * * * *` 확인. 그 전 zip 은 매일 04:30 KST 것)
   - 또는 버킷 `gs://beside-514511-backups/db/`(매시 20분에 올림 [적용]). VM 의 서비스 계정 `beside-backup@` 은 버킷을 읽지 못하므로(`roles/storage.objectCreator` 뿐) 소유자 계정으로 받아 볼륨의 `backup/` 에 넣은 뒤 3번으로 간다. 이 순서는 연습하지 않았다 [계획].
     1. 개발 PC(소유자 계정으로 `gcloud` 로그인)에서 `gcloud storage cp gs://beside-514511-backups/db/<zip> .`. 다음 단계의 SSH 별칭과 키가 개발 PC 에만 있으므로 Cloud Shell 에서 받지 않는다.
     2. VM 으로 옮긴다: 개발 PC 에서 `scp <zip> beside-gcp:~`([deploy/README.md](../../deploy/README.md) 의 SSH 별칭).
     3. VM 에서 backend 이미지로 넣는다. 파일과 `backup/` 폴더의 주인이 uid 10001 이어야 backend 의 매시 백업이 계속 된다(sudo 로 폴더를 만들면 root 소유가 되어 매시 백업이 실패한다):
        `docker run --rm -v beside-storage:/app/storage -v "$HOME/<zip>:/in.zip:ro" --entrypoint sh beside-backend:local -c 'mkdir -p /app/storage/backup && cp /in.zip /app/storage/backup/<zip>'`
3. backend 이미지로 복구한다(파일 주인 uid 10001). zip 이 볼륨에 없으면 'zip 없음' 을 출력하고 멈춰 DB 파일을 지우지 않는다. 이 줄이 나오면 4번으로 가지 않는다(지금 DB 그대로 떠서 복구된 것처럼 보인다):
   `docker run --rm -v beside-storage:/app/storage --entrypoint sh beside-backend:local -c 'test -f /app/storage/backup/<zip> || { echo "zip 없음: /app/storage/backup/<zip> — 아무것도 바꾸지 않았어요" >&2; exit 1; }; rm -f /app/storage/db/beside-real.mv.db && java -Dloader.main=org.h2.tools.Restore -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher -file /app/storage/backup/<zip> -dir /app/storage/db -db beside-real'`
4. `up -d backend` → healthz.
5. 아는 작업 id 로 확인한다. 백업 뒤에 만든 작업은 잃는다(RPO = 백업 간격).
- **주의**: 1차 훈련 때는 DB 파일이 없으면 backend 가 빈 DB 로 그냥 떴다. 이제 prod 는 시작하지 않는다 [적용] 2026-11-20 15:53(PR #22). 복구 전에는 backend 가 뜨지 않는 것이 정상이다. 새 서버의 첫 시작만 `STORAGE_DB_CREATE_IF_MISSING=true` 를 붙인다([deploy/README.md](../../deploy/README.md) 'VM 첫 설정' 6번).

### VM 다시 세우기 (FD6 에서 연습)
1. 고른 스냅샷(매일 19:00 UTC, 7일 [적용]. 또는 수동 스냅샷)으로 VM 을 만든다.
   - 지금 VM 이 살아 있으면 운영 Tailscale 과 같은 노드 키라 서로 끊는다. 먼저 운영 VM 을 멈추거나, 외부 IP 없이 확인만 한다.
   - `--service-account=beside-backup@beside-514511.iam.gserviceaccount.com --scopes=storage-rw,logging-write,monitoring-write` 를 준다(VM 밖 백업이 계속되게).
2. 스냅샷 시각에 돌던 설정으로 뜬다(훈련 때는 부하 덮어쓰기). `cd ~/Beside && docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d backend` 로 운영 설정에 맞춘다.
3. 고정 IP `beside-ip` 를 새 VM 에 옮기거나, DuckDNS A 레코드를 바꾼다. `caddy_data` 는 스냅샷에 들어 있어 인증서를 새로 받지 않는다.
4. healthz·공개 경로 61개 확인. 스냅샷 뒤에 생긴 작업은 VM 밖 백업 zip(매시)으로 DB 를 더 최근으로 돌릴 수 있다(위 'DB 복구' 2번의 버킷 순서).
- 3~4번(진짜 전환)은 연습하지 않았다 [계획].

### GPU Pod 다시 세우기 (FD16a: 98 s)
1. 멈춘 Pod 를 start 한다. "not enough free GPUs" 면 같은 볼륨 `z4kgw4v5zi`, EU-SE-1, A40, `minVcpuCountPerGpu 4`, `minRamPerGpu 24`, Secret 토큰으로 새 Pod 를 만든다.
2. Pod 에서 `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main origin/main`, 그다음 `nohup bash /workspace/beside-bootstrap.sh > /workspace/beside-logs/start.log 2>&1 &`.
   - 털 맵 주의(PR #25, main 병합 2026-11-21): 이 단계로 main 을 받으면 변환기가 `base.glb` 안에 털 맵을 넣고, 이미 깔린 앱도 새 작업마다 셸 20겹 털을 GLB 의 `furLength` 로 그린다(리포의 AnimalLift 출력 1건을 로컬 변환한 값 약 0.070 — 지금까지 기기에서 쓴 `Dog_Fur_Mat` 는 0.025). 기기 확인([GLB_SPEC §4.1](../asset/GLB_SPEC.md) '알려진 점') 전에 피하는 방법은 둘이다. ① 추론 서비스의 털 맵 끄기 `BESIDE_EMBED_HAIR=0` **[구현됨]**(브랜치 Unity 포트, 2026-11-21 — 그 포트가 main 에 들어간 뒤의 `origin/main` 에만 있다): 시작 명령 앞에 붙인다, `BESIDE_EMBED_HAIR=0 nohup bash /workspace/beside-bootstrap.sh > /workspace/beside-logs/start.log 2>&1 &`. 시작 스크립트는 볼륨에만 있고 리포에 없지만, 같은 식으로 앞에 붙인 `BESIDE_REQUEST_TIMING=1` 은 서비스의 `/proc/<pid>/environ` 까지 갔다([4차 부하 시험](../perf/load/2026-11-20_vm-4th.md) '추론 서비스 설정'). 서비스는 이 값을 시작할 때 한 번 읽는다. 이 스위치로 Pod 를 켠 적은 아직 없다 [계획] — 켜면 새 작업 폴더에 `hair_maps.json` 사본이 생기지 않는지(꺼지면 털 맵도 사본도 쓰지 않는다) 본다. ② `origin/main` 대신 병합 전 main 커밋에 고정한다: `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main 24bce92`(2026-11-21 기준 병합 전 main, 털 맵도 스위치도 없다). Pod 를 켤 때마다 이 단계에서 다시 고른다. 기기 확인이 끝나면 위 원래 명령으로 돌아간다.
3. 볼륨의 Tailscale 상태로 같은 `100.83.86.62` 에 붙는다. VM `.env` 는 바꾸지 않는다. 같은 볼륨의 Pod 를 둘 켜지 않는다.
4. 1차 훈련 때는 정지 직후 보낸 작업이 최대 600 s 동안 슬롯을 잡았다(FD16b). 이제는 내려받기가 60 s 동안 멈추면 끊기고, 새 서비스의 `instanceId` 를 본 뒤에는 그 전 요청을 바로 포기한다(backend [적용] 2026-11-20 15:53, PR #22). 2단계로 main 을 받아 켠 서비스는 `instanceId` 를 낸다. 옛 서비스(`instanceId` 없음)면 60 s 제한만 동작하고 POST 중이던 요청은 `inference.timeout-ms`(120 s, 2026-11-21 #7)까지 기다리므로, 급할 때 backend 를 재시작해 대기열을 넘긴다. 이때 PENDING 은 `INTERNAL_ERROR` 가 되므로 앱에서 다시 시도해야 한다.

## 남은 일 [계획]
- FD16b·FD5·FD9 의 고침은 2026-11-21 5차 때 다시 재현해 뜻한 대로 동작함을 확인했다 [실험 결과 5차]([결과](../perf/load/2026-11-21_vm-5th.md) 7절). 남은 것:
  - FD4(디스크 가득 참) 다시 재현.
  - FD16b 의 다른 두 길: Pod 가 120 s 넘게 늦을 때의 `INFERENCE_TIMEOUT`, GLB 내려받기 60 s 무응답의 `CONVERSION_FAILED`. 5차는 같은 Pod 가 곧바로 GPU 를 다시 받아 `instanceId` 길로만 끝났다.
  - FD9 의 진짜 블랙홀(연결 자체가 안 됨). 5차의 `docker pause` 에서 본 중복('적어도 한 번')을 받아들일지 정한다.
  - 다시 켠 Pod 에는 추론 서비스가 저절로 뜨지 않아 5차에도 손으로 bootstrap 했다. Pod 시작 명령에 넣을지와 볼륨에 남은 작업 폴더(`207d1dce`) 정리를 정한다([5차](../perf/load/2026-11-21_vm-5th.md) 11절 4번).
- FD4 는 계약에 있는 `INTERNAL_ERROR` 를 쓴다(뜻이 "서버 오류"라 맞고, 앱 문구도 "서버에 문제가 생겼어요"). 디스크 부족 전용 코드는 만들지 않았다. 필요하면 계약 회의 안건으로 낸다.
- R3: 알림 채널을 만든 뒤 가동 확인과 알림 정책을 켠다. 부하 시험 때는 `uptime_check.sh pause`.
- 스냅샷 일정이 만든 첫 스냅샷(2026-11-20 19:00 UTC 뒤)을 확인한다.
- 실제 모델이 멈춘 경우의 🐢 와 R4 의 디스크·백업 알림을 재현한다. FD2·FD13·FD17·FD18·R7 은 2026-11-20 2차 확인에서 다시 재현했다([결과](2026-11-20_failure-drills.md) '2차 확인').
