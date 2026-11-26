# 장애 대응 런북

장애마다 감지, 자동 복구, 사람이 할 일, 앱에 보이는 것, 남은 일을 적는다.

- 근거: [2026-11-20 장애 훈련](2026-11-20_failure-drills.md)(FD 번호)과 그 전 부하 시험(F1a·F1b-real·F2-real). PR #22 고침의 다시 재현(FD16b·FD9·FD5)과 시간 초과 120 s 의 느린 추론(F1b)은 [5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 7절(2026-11-21).
- 이중화 결정: [REDUNDANCY.md](REDUNDANCY.md).
- 표기:
  - [구현됨] 코드·시험이 리포에 있다(운영 VM·Pod 에 적용됐는지는 따로 적는다).
  - [적용] 운영에 켜 두었다.
  - [실험 결과] 훈련에서 쟀다. [실험 결과 2차] 대응을 배포한 뒤 2차 확인 창(2026-11-20 05:37~05:53 UTC)에서 쟀다.
  - [계획] 하지 않음.
- 대응 코드(R1~R7, FD18 알림)는 PR #20 으로 main(`ce1a685`)에 들어갔다. 적용은 2026-11-20 에 나눠서 했다: 버킷·백업 타이머·메타데이터 차단(R1)과 스냅샷 일정(R2)은 1차 훈련 전후에, 배포(backend 의 R1 매시 백업·R6·FD18 알림, Caddy 의 R5)는 05:32~05:33 UTC, VM 감시(R4)는 05:34 UTC 에 했다. R3 는 [계획]. R7 은 VM 이 아니라 Pod 쪽이고, main 의 `start_pod.sh` 로 Pod 를 켤 때마다 들어간다.
- 남은 장애 고침(FD16b·FD5·FD4·FD9)은 PR #22 로 main(`7230dea`)에 들어갔고, 2026-11-20 15:50:40~15:53:14 UTC 에 VM 에 배포, 15:53 UTC 에 VM 감시(🧱 가 든 판)를 다시 설치했다 [적용]. 추론 서비스의 healthz `instanceId` 는 Pod 쪽이라 Pod 를 main 으로 다시 켤 때 들어간다. 2026-11-25 확인: 5차 부하 시험(2026-11-21)에서 Pod 를 main `fe10683` 으로 켜 `instanceId` 를 받았고, FD16b·FD5·FD9 를 다시 재현했다 [실험 결과] ([5차 7절](../perf/load/2026-11-21_vm-5th.md)). 단 운영 backend 가 아니라 훈련 backend 에서 했고, 알림(🧱 포함)은 팀 채널이 아니라 VM 안 훈련 수집기(`drill-hook`)로 갔다. FD4 는 다시 재현하지 않았다 [계획].
- `inference.timeout-ms` 120 s(2026-11-21 안건 #7)는 2026-11-21 01:07 UTC main `fe10683` 배포로 운영 VM 에 들어갔다 [적용]([5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 1절). 그 전 운영은 600 s 였다.

## 먼저 볼 곳

- 2026-11-26 부터(배포 뒤): Grafana **Beside 로그** 에서 서비스별 로그 줄·ERROR·실패 코드를, **Beside 운영** 에서 지표를 본다. Grafana 지표 알림(📈, 팀 채널)은 [deploy/README.md](../../deploy/README.md) '로그 · 알림'. 사후 보고서는 [POSTMORTEM_TEMPLATE.md](POSTMORTEM_TEMPLATE.md), 목표는 [SLO.md](SLO.md).

| 무엇 | 어디서 |
|---|---|
| 공개 경로 | `curl -s https://beside-team.duckdns.org/api/v1/healthz` → `{"status":"ok","profile":"real,prod",...}` |
| backend 상태 | VM `docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps`. DB 까지 보려면 `exec -T backend curl -s localhost:8081/actuator/health` |
| GPU 상태 변화 | backend 로그 `inference service A -> B`, 디스코드 GPU 알림 |
| 대기열 | `exec -T backend curl -s localhost:8081/actuator/prometheus \| grep ^beside_jobs_queue` |
| 요청 하나 | 앱 `[BesideMetric]` 줄의 `request_id`(= 요청·응답 헤더 `X-Request-Id`)로 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs backend \| grep <id>`. prod 는 한 줄에 JSON 하나(`"requestId":"<id>"`, 스택은 `stack_trace` 필드), 다른 프로필은 텍스트 `rid=<id>` [구현됨: 적용] 배포 창 1(2026-11-25 17:21 UTC). 2026-11-26 08:07 UTC 업로드 1건: 응답 `X-Request-Id` 와 같은 값이 `Job created` JSON 줄의 `requestId` 에 있었다 |
| 작업 하나 | 같은 명령에 `grep <jobId>`. 워커 줄은 `"jobId"`(prod) 또는 `job=<jobId>`(텍스트)이고, 접수 줄 `Job created: <jobId>` 의 요청 id 로 만든 요청도 찾는다 [구현됨: 적용] 배포 창 1. 2026-11-26 같은 작업의 크롭·PROCESSING·COMPLETED 줄에 `jobId` 가 있었다(워커 줄의 `requestId` 는 비어 있다) |
| 오류 응답 수 | `exec -T backend curl -s localhost:8081/actuator/prometheus \| grep ^beside_api_errors_total`(status·code 별). 5xx 는 로그에 `Internal error: ... requestId=<id>` 와 스택 [구현됨: 적용] 배포 창 1. 운영에서 5xx 줄은 아직 보지 못했다 |
| VM 감시(R4) | `journalctl -u beside-watchdog -n 20 --no-pager`(정상이면 비어 있다), 디스코드 🔁(재시작)·✅(재시작 뒤 정상) |
| Pod | Runpod 콘솔, Pod `/workspace/beside-logs/{inference,start,supervisor}.log`, `curl -s 127.0.0.1:8002/healthz` |

## 시나리오별 대응

### VM·backend·앞단

| 장애 | 감지 | 자동 | 사람이 할 일 | 앱에 보이는 것 |
|---|---|---|---|---|
| backend 죽음(FD1) | 외부 가동 감시 R3 [계획: 채널] | `restart: unless-stopped`, 19 s [실험 결과] | 없음. 진행 중이던 작업은 `INTERNAL_ERROR` 라 앱에서 다시 시도 | 잠깐 502, 진행 중 작업 실패 |
| backend 멈춤(FD2) | 1차: Docker unhealthy 101 s, **재시작은 없음** [실험 결과]. R4 감시 스크립트가 actuator health 3번(30 s 간격) 실패면 재시작 + 디스코드 🔁 [적용] 2026-11-20 05:34 | R4 가 멈춘 지 112 s 뒤 재시작 [실험 결과 2차]. 정상까지는 재지 못했다(훈련 스크립트가 곧바로 backend 를 다시 만들어서). 부팅 14~15 s 를 더해 약 2분 [계산] | 보통 없음. 🔁 를 받으면 원인을 본다. 점검 표시(`/etc/beside-maintenance`)가 있거나 감시가 꺼져 있으면 `docker restart beside-backend-1`. 멈춘 동안 쌓인 POST 가 SIGCONT 뒤 처리될 수 있다 | 응답 없음(1차 189 s, R4 로 약 2분) |
| Caddy 죽음(FD7) | R3 | 재시작 1.4 s [실험 결과]. 인증서는 `caddy_data` | 없음. `caddy_data` 는 지우지 않는다(Let's Encrypt 한도) | 1~2 s 연결 끊김 |
| backend·Caddy 가 꺼졌다가 Docker 가 다시 띄움(커널 OOM kill, Java 힙 부족, 크래시) | 전에는 알림이 없었다(🧱 는 backend 가 3번 넘게 꺼질 때만). 이제 R4 가 Docker `RestartCount` 가 늘면 한 번 알린다 [구현됨: 적용] 배포 창 1. 메모리면 🧠(`State.OOMKilled`, Docker 의 `oom` 이벤트, backend 종료 코드 3), 아니면 ♻️ 와 종료 코드. 컨테이너마다 10분에 한 번이고, 그 사이에 또 꺼진 것은 다음 메시지의 횟수에 합친다. 점검 표시가 있으면 오지 않는다. 재현은 [계획] | `restart: unless-stopped`. Java 힙 부족이면 JVM 이 종료 코드 3 으로 끝나고 힙 덤프 `/app/storage/backend-oom.hprof` 를 남긴다 [구현됨: 적용] 배포 창 1 | 🧠: `docker stats --no-stream` 으로 메모리를 보고, 되풀이되면 `mem_limit` 를 다시 정한다. 힙 덤프는 꺼내 분석한 뒤 꼭 지운다(남아 있으면 다음 덤프가 안 생긴다, [deploy/README.md](../../deploy/README.md) '메모리'). ♻️: `docker logs --tail 50 <컨테이너>` | 잠깐 502. backend 면 진행 중 작업이 `INTERNAL_ERROR`(앱에서 다시 시도) |
| Caddy 멈춤·제거(`stop`·`rm`. Docker 가 다시 띄우지 않는다) | R4 가 두 번 연속(30 s 간격) 실행 중이 아니면 🚪, 1시간마다 다시, 돌아오면 ✅ [구현됨: 적용] 배포 창 1. 점검 표시가 있으면 오지 않는다. 재현은 [계획] | 없음 | `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d caddy`. 일부러 멈출 때는 점검 표시(`/etc/beside-maintenance`)를 먼저 둔다 | 공개 주소 전체 접속 불가 |
| VM 재시작·호스트 유지보수(FD3) | R3 | GCE 자동 재시작·실시간 이전(켜짐). 컨테이너·Tailscale 자동 복귀 [실험 결과] | 없음. 외부 82.8 s [실험 결과] | 1~2분 연결 실패 |
| 디스크 가득 참(FD4) | **healthz 는 200** [실험 결과]. R4 가 디스크 80 % 이상이면 시간당 1번 알림 [적용] 2026-11-20 05:34, 재현은 안 함 | 없음. 비우면 재시작 없이 회복 [실험 결과] | 공간 확보(오래된 `results/`·`uploads/` 정리는 보존 정책 안건 #23 — 2026-11-21 결정: 사진 7일·GLB 30일, 자동 삭제는 [계획]이라 그 전에는 손으로), 그다음 실패한 작업 다시 시도 | 1차: 2 MB 남았을 때 `CONVERSION_FAILED`(원인과 다른 코드), 0 이면 업로드 500. 이제 GLB 를 쓰거나 결과 폴더로 복사하지 못하면 `INTERNAL_ERROR`("서버에 문제", 다시 시도 가능) [구현됨: PR #22] [적용] 2026-11-20 15:53, 재현은 [계획]. 업로드 500 은 그대로(이미 `INTERNAL_ERROR`) |
| DB 파일 손상·유실(FD5) | 1차: **없음**, H2 가 빈 DB 를 만들고 healthz 200 [실험 결과]. 이제 prod backend 는 DB 파일이 없으면 시작하지 않는다(로그 `H2 database file ... does not exist`) [구현됨: PR #22] [적용] 2026-11-20 15:53(컨테이너 `STORAGE_DB_CREATEIFMISSING=false` 확인). 시작하자마자 꺼지는 것을 Docker 가 계속 다시 띄우면 R4 가 🧱 "시작하자마자 계속 꺼져요" 를 보낸다 [적용] 2026-11-20 15:53 다시 설치(점검 표시 `/etc/beside-maintenance` 가 있으면 🧱 도 오지 않는다). 5차(2026-11-21, 훈련 backend)에서 재현: 시작 거부 10번·빈 DB 없음, 🧱 시작 44.6 s 뒤 [실험 결과]([5차 7절](../perf/load/2026-11-21_vm-5th.md) FD5). 그 🧱 는 따로 띄운 감시 인스턴스(점검 표시 무시)가 훈련 수집기로 보냈다. 운영 R4 가 팀 채널로 🧱 를 보내는 것은 재현하지 않았다 | 없음 | 아래 "DB 복구". `STORAGE_DB_CREATE_IF_MISSING=true` 로 띄우지 않는다(빈 DB 로 뜬다) | 1차: 기존 작업이 404. 이제 복구할 때까지 접속 실패(502) |
| VM·디스크·zone 통째 유실(FD6) | R3 | 없음 | 아래 "VM 다시 세우기". 훈련에서 서비스 응답까지 89 s, 운영 설정까지 145 s [실험 결과] | 복구까지 접속 불가 |
| TLS 갱신·DuckDNS 장애(FD8) | 인증서 만료·이름 풀이 실패 | Caddy 가 자동 갱신(포트 80·443·DNS 필요) | DuckDNS 웹에서 A 레코드 확인. 재현은 하지 않음 [계획] | 연결 실패 |

### VM↔GPU 경로·추론 서비스·GPU

| 장애 | 감지 | 자동 | 사람이 할 일 | 앱에 보이는 것 |
|---|---|---|---|---|
| tailnet 끊김(FD10, 3차의 50~83 s) | 14 s 뒤 DOWN, 디스코드는 45 s 유예 뒤 [실험 결과] | 오프라인 대기(30분)가 버팀. 진행 중 작업도 TCP 재전송으로 살아남 [실험 결과] | 길면 `tailscale status`·Pod 상태 확인 | 기다림(최대 122 s 관찰) |
| 느리고 손실 많은 경로(FD11) | 없음(UP) | 없음. 느려질 뿐 실패 없음 [실험 결과] | 오래가면 Tailscale 경로(직접/DERP) 확인 | 기다림(최대 112 s 관찰) |
| 추론 서비스 전체 멈춤(FD14) | 2 s 뒤 DOWN [실험 결과] | 풀리면 1.5 s 뒤 UP | 계속 멈춰 있으면 Pod 에서 서비스 재시작(R7 은 살아 있는 프로세스는 건드리지 않는다) | 기다림 |
| 추론 서비스 죽음(F2-real, FD12) | DOWN. 45 s 안에 돌아오면 DOWN 알림 없이 🟡·🟢 만 [실험 결과 2차] | R7 감독 루프가 5 s 뒤 다시 띄움 [적용]. 실제 Pod 에서 `kill -9` → UP 41 s, 사람 손 없음 [실험 결과 2차]. 그동안 오프라인 대기가 기다림 | 보통 없음. 감독 루프가 없는 서비스(2026-11-20 전 `start_pod.sh` 로 띄움)면 `start_pod.sh` 다시. 일부러 내려 둘 때는 `touch /workspace/beside-norestart` | 기다림, 30분 넘으면 `INFERENCE_UNAVAILABLE` |
| healthz 는 UP, 추론만 멈춤(FD13) | 1차에는 **없음** [실험 결과]. R6 의 `busyForMs` 가 120 s 를 넘으면 🐢 알림 [적용]. 스텁 작업 시작 2분 14초 뒤 🐢, 풀리면 🟢 [실험 결과 2차]. 변환·리깅 멈춤은 못 본다 | 없음. 작업마다 `inference.timeout-ms` 시간 초과: 120 s(2026-11-21 #7) [적용]. 2026-11-25 확인: 운영 VM 은 5차 시험 전 `fe10683` 배포(2026-11-21 01:07 UTC)부터 120 s 이고, 지금은 `625c2a5`(2026-11-22 07:05~07:08 UTC 배포, `application-real.properties:17` `120000`) | 🐢 를 받으면 Pod 에서 서비스 재시작. 2차에서 스텁을 다시 띄우자 멈춰 있던 작업은 `INFERENCE_UNAVAILABLE` 로 끝났다 [실험 결과 2차] | 작업마다 2분 뒤 `INFERENCE_TIMEOUT`, 앱은 600 s 에 포기. 🐢 기준도 120 s 라 🐢 가 올 때쯤 첫 작업은 이미 실패했을 수 있다: 5차 F1b(훈련 스텁 180 s)에서 실패(처리 120.0 s)와 🐢 가 32 ms 차로 왔고(수집기 도착 기준, drill-hook-all.log), 다음 작업은 완료했다 [실험 결과] |
| 토큰 불일치(FD17) | 1차에는 작업 실패 웹훅뿐 [실험 결과]. R6 의 `tokenOk=false` 면 🔑 알림 [적용]. 토큰을 바꾸고 9.8 s(스텁 재시작 포함) 뒤 🔑, 되돌리고 4.3 s 뒤 🔑 풀림 [실험 결과 2차] | 없음 | VM `.env` 의 `INFERENCE_TOKEN` 과 Pod Secret 을 맞춘다. 출력하지 말고, 새로 만들었으면 둘 다 바꾼다 | 모든 작업이 바로 `INFERENCE_UNAVAILABLE` |
| 모델 로드 실패(FD18) | 1차에는 LOADING 에 머물러 구별 안 됨 [실험 결과]. healthz 의 `loadError` 가 생기면 🧩 알림 [적용]. 실제 Pod 에서 🟡 와 함께 🧩 "(FileNotFoundError)" [실험 결과 2차] | 없음. 작업은 30분 기다린다 | `inference.log` 의 오류(체크포인트·볼륨) 확인 → 바로잡고 재시작(표시 파일 + `kill`, 그다음 `start_pod.sh`). 다시 시작 뒤 UP 1차 52.6 s, 2차 40 s [실험 결과] | 기다림, 30분 뒤 실패 |
| GPU 메모리 부족(FD15) | GPU 상태 알림 없음. 작업 실패 웹훅만 [실험 결과] | 메모리가 풀리면 재시작 없이 회복 [실험 결과] | `nvidia-smi` 로 다른 프로세스 확인 | 그 사이 `INFERENCE_FAILED`, 앱에서 다시 시도 |
| Pod 정지·회수(FD16a·b, 2~4차) | 30.6 s 뒤 DOWN [실험 결과], 5차는 정지 요청 21.2 s 뒤 [실험 결과] | 1차: 없음. 이제 끊긴 GLB 내려받기는 60 s 동안 데이터가 없으면 끊고(`inference.download-idle-ms`), 다시 켠 서비스의 healthz `instanceId` 가 바뀌면 그 전에 보낸 요청을 바로 포기한다(prod) [구현됨: PR #22]. backend 쪽은 [적용] 2026-11-20 15:53. `instanceId` 를 내는 추론 서비스는 Pod 를 main 으로 켜면 들어간다(5차 2026-11-21 부터 그렇게 켰다) | 아래 "GPU Pod 다시 세우기" | 기다림. 1차: 정지 직후 보낸 작업 하나가 600 s 슬롯을 잡아 최대 602 s [실험 결과]. 이제 그 작업은 내려받는 중이었다면 늦어도 60 s 뒤 `CONVERSION_FAILED`(POST 중이었다면 `instanceId` 를 내는 서비스로 Pod 가 돌아온 뒤 `INFERENCE_UNAVAILABLE` — 옛 서비스면 POST 는 `inference.timeout-ms` 120 s 까지 기다린다)로 끝나고 다음 작업이 슬롯을 받는다 [계산: 시험으로 확인]. 5차(2026-11-21, 훈련 backend·실제 Pod)에서는 같은 Pod 가 바로 GPU 를 다시 받아 `instanceId` 길로 끝났다: 처리 중 1건이 52 s 에 `INFERENCE_UNAVAILABLE`, 기다린 5건 모두 완료·최대 74.3 s [실험 결과]. 120 s 시간 초과 길과 GLB 내려받기 60 s 길은 재현하지 않았다 [계획] |
| Runpod 재고 없음 | Pod start·create 실패 | 없음 | 배치 조건(`minVcpuCountPerGpu 4`, `minRamPerGpu 24`)을 주고 새 Pod. 다른 데이터센터는 볼륨이 묶여 있어 바로는 못 쓴다([REDUNDANCY.md](REDUNDANCY.md)) | GPU 없음 → 기다리다 실패 |

### 알림·감시

| 장애 | 지금 | 대응 |
|---|---|---|
| 디스코드 웹훅 실패(FD9) | 작업은 안 막힘. 1차: 알림은 5 s 시간 초과로 하나씩 밀렸다(8분에 32건) [실험 결과] | GPU 상태·알림을 작업 결과보다 먼저 보낸다. 실패하면 5 s 에서 두 배씩(최대 5분) 쉬며 다시 해 본다(429 는 Retry-After). 실패한 GPU 알림은 다시 보내고(8번까지), 실패한 작업 결과는 버리고 세어 두었다가 다시 보내지면 📭 한 줄로 알린다. 3건 이상 밀린 작업 결과는 📦 한 메시지로 묶고, 1분 넘게 늦은 메시지에는 그 시각(⏱)을 붙인다 [구현됨: PR #22] [적용] 2026-11-20 15:53. 5차(2026-11-21, 훈련 backend·수집기 `docker pause` 240 s)에서 재현: 재시도 간격 5 → 160 s, 회복 뒤 GPU 메시지가 먼저, 그다음 📭·📦, GPU 메시지 손실 0, 작업 55건 모두 완료 [실험 결과]. `pause` 는 연결을 받아 둔 채 멈춰, 늦게 닿은 요청과 다시 보낸 것이 겹쳤다('적어도 한 번'). 연결 자체가 안 되는 진짜 블랙홀은 [계획]. VM 감시(R4)의 웹훅은 따로다 |
| backend·VM 이 죽어 알림 자체가 없음 | backend 웹훅은 자기와 함께 멈춘다. backend 가 멈추면 VM 감시(R4)가 재시작하며 🔁 를 보낸다 [적용, 실험 결과 2차]. Docker 가 backend·Caddy 를 다시 띄우면 ♻️·🧠, Caddy 가 멈춰 있으면 🚪 를 R4 가 보낸다 [구현됨: 적용] 배포 창 1. VM 이 죽으면 알림이 없다 | R3 외부 가동 감시(1분, 2개 지역 2분 실패 → 메일) [계획: 사용자가 콘솔에서 알림 채널을 만든 뒤 `deploy/gcp/uptime_check.sh create <channel>`] |

## 절차

### DB 복구 (FD5 에서 연습, 20 s)
1. `docker compose ... stop backend`
2. 어느 백업으로 돌아갈지 고른다.
   - VM 안 `storage/backup/beside-*.zip`(5분마다, 288개 [적용] 배포 창 1: 2026-11-25 17:16~17:21 UTC. 그 전은 매시 5분·48개(2026-11-20 05:33 UTC 배포), 그보다 전은 매일 04:30 KST)
   - 또는 버킷 `gs://beside-514511-backups/db/`(5분마다, 매시 3·8·13…분에 올림 [적용] 배포 창 1. 그 전은 매시 20분). VM 의 서비스 계정 `beside-backup@` 은 버킷을 읽지 못하므로(`roles/storage.objectCreator` 뿐) 소유자 계정으로 받아 볼륨의 `backup/` 에 넣은 뒤 3번으로 간다. 이 순서는 연습하지 않았다 [계획].
     1. 개발 PC(소유자 계정으로 `gcloud` 로그인)에서 `gcloud storage cp gs://beside-514511-backups/db/<zip> .`. 다음 단계의 SSH 별칭과 키가 개발 PC 에만 있으므로 Cloud Shell 에서 받지 않는다.
     2. VM 으로 옮긴다: 개발 PC 에서 `scp <zip> beside-gcp:~`([deploy/README.md](../../deploy/README.md) 의 SSH 별칭).
     3. VM 에서 backend 이미지로 넣는다. 파일과 `backup/` 폴더의 주인이 uid 10001 이어야 backend 의 주기 백업이 계속 된다(sudo 로 폴더를 만들면 root 소유가 되어 주기 백업이 실패한다):
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
4. healthz·공개 경로 확인(`bash scripts/check_public_routes.sh`, 2026-11-26 부터 63개). 스냅샷 뒤에 생긴 작업은 VM 밖 백업 zip(5분마다)으로 DB 를 더 최근으로 돌릴 수 있다(위 'DB 복구' 2번의 버킷 순서).
- 3~4번(진짜 전환)은 연습하지 않았다 [계획]. 운영 VM 이 살아 있을 때의 계획 전환은 아래 '대기 서버로 넘기기·되돌리기'.

### 대기 서버로 넘기기·되돌리기 (계획 전환, REDUNDANCY 1-5) — 스크립트 [구현됨], 훈련 [계획]
운영 VM `beside-vm`(zone a)이 살아 있을 때 대기 VM `beside-standby`(zone b)로 넘기고 되돌린다. 대기 VM 은 켜져 있지만 서비스는 멈춰 있다. 개발 PC 에서 한다(소유자 `gcloud` 로그인, `~/.ssh/beside-vm.key`, Git Bash 면 `gcloud` 를 PATH 에). 운영 VM 이 죽었으면 위 'VM 다시 세우기'.
- 대기 VM 에 SSH: `ssh -i ~/.ssh/beside-vm.key -o HostKeyAlias=beside-standby -o ProxyCommand="ssh -i ~/.ssh/beside-vm.key beside@34.64.91.254 -W %h:%p" beside@<대기 VM 내부 IP>`(외부 IP 를 쓰지 않는다. 내부 IP 는 `gcloud compute instances list`).
- 준비 기록: [2026-11-26 준비](data/2026-11-26_failover-prep/README.md).

**준비** (훈련 전날까지. 운영은 읽기만 한다)
1. 대기 VM 에 서비스 계정 `beside-backup@`(storage-rw)이 붙어 있는지 본다: `gcloud compute instances describe beside-standby --zone=asia-northeast3-b --format="value(serviceAccounts[0].email)"`. 없으면 넘긴 뒤 VM 밖 백업이 실패한다. 이미 있는 VM 은 멈춤 → `set-service-account`(위 'VM 다시 세우기' 1번의 값) → 시작.
2. 대기 VM 에 임시 외부 IP 가 있어야 한다(git·이미지·Tailscale 이 밖으로 나간다). 없으면 `bash deploy/gcp/clone_vm.sh ip standby`. 전환 4단계가 이 IP 를 뗀다.
3. Tailscale 로그인: 대기 VM 에서 `sudo systemctl enable --now tailscaled`(`clone_init.sh` 가 꺼 둔다) → `sudo tailscale up --hostname=beside-standby --accept-dns=false`(운영 VM 과 같은 옵션, [deploy/README.md](../../deploy/README.md) 'Tailscale').
   - 나온 주소를 팀 tailnet 계정으로 사람이 열어 승인한다. 관리 화면에 `beside-vm`·`beside-standby` 두 노드가 따로 보이고 "Duplicate node key" 가 없어야 한다. ACL 이 `beside-standby` 를 막지 않는지도 본다.
   - Pod 가 켜져 있으면 대기 VM 에서 `tailscale ping <Pod 의 100.x>` 로 닿는지 본다.
   - 복사본에서 `tailscale logout` 은 하지 않는다.
   - 승인이 안 되면 훈련을 미룬다. `failover_vm.sh` 가 멈추고(`BESIDE_ALLOW_NO_TAILNET=1` 로만 넘어간다), 넘기면 GPU Pod 에 닿지 않아 작업이 PENDING 으로 기다리다 30분 뒤 `INFERENCE_UNAVAILABLE` 로 끝난다.
4. `bash scripts/sync_standby.sh`: 대기 VM 을 운영 커밋으로 빨리 감고, 모델·이미지를 빌드·받는다. 아무것도 시작하지 않는다. 끝나면 `~/.beside-built` 에 커밋을 남긴다(도중에는 `pending`). 운영을 다시 배포하면 이것도 다시 돌린다.
5. `DRY_RUN=1 bash scripts/failover_vm.sh --planned beside-vm beside-standby`: 점검만 한다. 같은 커밋, 대상 이미지가 다 있음, 빌드 표시, tailnet 을 본다. `would STOP` 줄이 있으면 진행하지 않는다.
6. 공지:
   - 1시간 창이고, 끊김은 수 분이다(목표 ≤ 3분 [제안값]).
   - 그때 진행 중인 작업은 `INTERNAL_ERROR` 로 끝나니 앱에서 다시 시도한다.
   - 원본 Grafana 가 팀 Discord 로 알림을 보낼 수 있다('backend 지표 수집 실패' 2분, '작업 실패가 이어짐').

**당일** (창 안)
1. 재기 준비:
   - Runpod CPU Pod 를 만든다(GCP 밖에서 재기. 개발 PC 는 쓰지 않는다).
   - 그 Pod 에서 다음을 켜 둔다:
     `python3 drill_client.py run --base https://beside-team.duckdns.org --photo dog.jpg --out out/d1 --run d1 --interval 20 --duration 3600`
     기본값 그대로면 10분 뒤 멈춘다. GPU Pod 를 켜지 않았으면 `--no-jobs` 로 healthz 만 잰다.
   - 개발 PC 에서 `export BESIDE_TIMELINE_DIR=docs/ops/data/<날짜>_failover-drills` 를 해 둔다. 타임라인 TSV 가 그 폴더에 생긴다.
2. Grafana 화면을 캡처한다(넘긴 뒤에는 대기 VM 의 Prometheus·Loki·Tempo 가 스냅샷 시점 기록부터 이어진다).
3. 표지 작업 A 를 만든다: `python3 drill_client.py mark --base https://beside-team.duckdns.org --photo dog.jpg --out out/d1 --run d1a --count 1`(id 는 `out/d1/marked-d1a.txt`).
4. VM 밖 업로드(매시 3·8·13…분)가 한 번 지나간 뒤 표지 작업 B 를 만든다(같은 명령, `--run d1b`). B 는 넘기기 **전에** 만든다. 마지막 백업 뒤에 생긴 B 까지 남아야 RPO 0 이다.
5. 넘기기(D-1): `bash scripts/failover_vm.sh --planned beside-vm beside-standby`
   - 순서: 점검 → 운영 점검 표시·backend 멈춤 → DB·`uploads`·`results`·`preprocessed`·`events.jsonl`·`.env` 복사 → 대상 `compose up`·healthz → 고정 IP 옮기기 → 공개 healthz·`check_public_routes.sh` → 역할·감시·백업 타이머·스냅샷 일정 넘기기.
   - 단계마다 UTC 시각이 `failover-<시각>-beside-vm-to-beside-standby.tsv` 에 남는다.
   - 되돌림: 1단계(멈춤), 2단계(복사), 3단계(대상 기동·healthz), 4단계(IP), 5단계(공개 healthz)가 실패하면, 스크립트가 IP 를 운영 VM 에 다시 붙이고 그 backend 를 다시 켠다. 그 뒤 `https://beside-team.duckdns.org` 를 손으로 확인한다.
   - 경로 검사(63개)가 실패하면 기록만 하고 되돌리지 않는다. 그 줄을 보고 판단한다.
6. 넘긴 뒤 확인:
   - `python3 drill_client.py check --base https://beside-team.duckdns.org --ids out/d1/marked-d1a.txt`(B 는 `marked-d1b.txt`)로 A·B 가 404 가 아닌지 본다.
   - 진행 중이던 작업은 `POST /api/v1/jobs/{id}/retry` 로 다시 보낸다(drill_client 에는 다시 보내기가 없다). Pod 를 켰다면 전환 뒤 1건이 COMPLETED 인지 본다.
   - Tailscale 관리 화면을 본다.
7. SSH: 고정 IP 가 대기 VM 에 있는 동안 `ssh beside-gcp` 는 호스트 키가 달라 실패한다. 그동안은 `ssh -o HostKeyAlias=beside-standby beside-gcp` 를 쓴다.
   - 옛 운영 VM 은 외부 IP 가 없어 Tailscale 이 오프라인이다.
   - 6단계가 옛 운영 VM 에 빌드 표시(그 VM 이 돌리던 커밋)를 남긴다. 그래서 되돌리기 점검을 통과한다.
   - **D-1 과 D-3 사이에는 배포하지 않는다**(`deploy_vm.sh beside-gcp` 도 호스트 키 때문에 실패한다). 대기 VM 의 커밋이 바뀌면 D-3 이 커밋 검사에서 멈춘다. 옛 운영 VM 은 외부 IP 가 없어 `sync_standby.sh` 로 맞출 수도 없다.
8. 되돌리기(D-3): 3~6번처럼 표지 작업을 만들고 재기한 뒤 `bash scripts/failover_vm.sh --planned beside-standby beside-vm`.
   - 끝나면 대기 VM 은 다시 standby 가 되고 외부 IP 가 없다. 데이터·`.env` 는 넘긴 시점 것이다. 그래서 다음 전환 전에 준비 2~5번을 다시 한다.
   - D-3 뒤에는 다시 그냥 `ssh beside-gcp` 를 쓴다.
   - 대기 VM 디스크에 붙은 스냅샷 일정은 남는다. 떼려면 `gcloud compute disks remove-resource-policies beside-standby-boot --zone=asia-northeast3-b --resource-policies=beside-daily-snapshot`.
9. Runpod CPU Pod(그리고 켰다면 GPU Pod)를 지운다.
10. 결과를 `docs/ops/<날짜>_failover-drills.md` 에 REDUNDANCY 1-5 의 DoD 대로 적는다.

### GPU Pod 다시 세우기 (FD16a: 98 s)
1. 멈춘 Pod 를 start 한다. "not enough free GPUs" 면 같은 볼륨 `z4kgw4v5zi`, EU-SE-1, A40, `minVcpuCountPerGpu 4`, `minRamPerGpu 24`, Secret 토큰으로 새 Pod 를 만든다.
2. Pod 에서 `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main origin/main`, 그다음 `nohup bash /workspace/beside-bootstrap.sh > /workspace/beside-logs/start.log 2>&1 &`.
   - 최근 Pod 들은 Pod 시작 명령이 이 두 일을 자동으로 한다. 이 시작 명령은 Runpod 의 Pod 설정에만 있고 리포에는 기록이 없다. 2026-11-25 확인(Runpod `list-pods`): `beside-gpu7`(`3sqqt04ftkf5gi`)·`beside-gpu5`(`03rnszh20mywif`)·`beside-gpu2`(`yq8zyoibhf15l5`)의 시작 명령이 `timeout 90 git … fetch` + `checkout -q -B main origin/main`(결과는 `/workspace/beside-logs/autostart-git.log`)을 하고 `nohup bash /workspace/beside-bootstrap.sh … &` 를 띄운다. fetch 가 실패하면 checkout 은 건너뛰고 볼륨에 있던 커밋으로 띄운다. 나머지 5대(`beside-gpu-qc` 등)는 시작 명령이 없어 이 단계를 손으로 한다. 그래서 위 세 Pod 는 start 하는 순간의 `origin/main` 으로 뜬다. 커밋을 고정하려면(아래 ②) Runpod 에서 시작 명령을 바꾸거나, 자동으로 뜬 서비스를 내린 뒤 다시 띄운다.
   - 털 맵 주의(PR #25, main 병합 2026-11-21): 이 단계로 main 을 받으면 변환기가 `base.glb` 안에 털 맵을 넣고, 이미 깔린 앱도 새 작업마다 셸 20겹 털을 GLB 의 `furLength` 로 그린다(리포의 AnimalLift 출력 1건을 로컬 변환한 값 약 0.070 — 지금까지 기기에서 쓴 `Dog_Fur_Mat` 는 0.025). 기기 확인([GLB_SPEC §4.1](../asset/GLB_SPEC.md) '알려진 점') 전에 피하는 방법은 둘이다. ① 추론 서비스의 털 맵 끄기 `BESIDE_EMBED_HAIR=0` **[구현됨]**(브랜치 Unity 포트, 2026-11-21 — PR #29 로 2026-11-22 main 에 들어갔다, `app.py:82`): 시작 명령 앞에 붙인다, `BESIDE_EMBED_HAIR=0 nohup bash /workspace/beside-bootstrap.sh > /workspace/beside-logs/start.log 2>&1 &`. 시작 스크립트는 볼륨에만 있고 리포에 없지만, 같은 식으로 앞에 붙인 `BESIDE_REQUEST_TIMING=1` 은 서비스의 `/proc/<pid>/environ` 까지 갔다([4차 부하 시험](../perf/load/2026-11-20_vm-4th.md) '추론 서비스 설정'). 서비스는 이 값을 시작할 때 한 번 읽는다. 이 스위치로 Pod 를 켠 적은 아직 없다 [계획] — 켜면 새 작업 폴더에 `hair_maps.json` 사본이 생기지 않는지(꺼지면 털 맵도 사본도 쓰지 않는다) 본다. ② `origin/main` 대신 병합 전 main 커밋에 고정한다: `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main 24bce92`(2026-11-21 기준 병합 전 main, 털 맵도 스위치도 없다). Pod 를 켤 때마다 이 단계에서 다시 고른다. 기기 확인이 끝나면 위 원래 명령으로 돌아간다.
3. 볼륨의 Tailscale 상태로 같은 `100.83.86.62` 에 붙는다. VM `.env` 는 바꾸지 않는다. 같은 볼륨의 Pod 를 둘 켜지 않는다.
   - 이유: Runpod 은 같은 데이터센터의 여러 Pod 가 한 네트워크 볼륨을 함께 붙이는 것을 허용한다. 다만 둘 이상이 동시에 쓰면 데이터가 깨질 수 있다(2026-11-25 Runpod `lookup-concept network-volume` 확인, 출처 docs.runpod.io/storage/network-volumes). 그래서 "둘 켜지 않는다" 는 Runpod 의 제한이 아니라 우리 규칙이다. 실제로 막는 것은 `deploy/runpod/start_pod.sh:49` `TS_DIR="$WS/tailscale"` 와 `:53` `LOG_DIR="$WS/beside-logs"` 가 고정값이라는 점이다. 두 Pod 가 같은 Tailscale 상태(노드 키)를 써 tailnet 에서 서로 밀어내고, 같은 로그 파일에 쓴다. `:146-153` 은 마지막 Pod id 가 다르면 경고만 하고 막지는 않는다.
4. 1차 훈련 때는 정지 직후 보낸 작업이 최대 600 s 동안 슬롯을 잡았다(FD16b). 이제는 내려받기가 60 s 동안 멈추면 끊기고, 새 서비스의 `instanceId` 를 본 뒤에는 그 전 요청을 바로 포기한다(backend [적용] 2026-11-20 15:53, PR #22). 2단계로 main 을 받아 켠 서비스는 `instanceId` 를 낸다. 5차(2026-11-21, 훈련 backend)에서는 같은 Pod 를 다시 켜자 약 50 s 만에 새 `instanceId` 가 보여 끊긴 요청이 52 s 에 끝났다 [실험 결과]. 옛 서비스(`instanceId` 없음)면 60 s 제한만 동작하고 POST 중이던 요청은 `inference.timeout-ms`(120 s, 2026-11-21 #7 — 운영 VM 은 2026-11-21 `fe10683` 배포부터 120 s, 2026-11-25 확인)까지 기다리므로, 급할 때 backend 를 재시작해 대기열을 넘긴다. 이때 PENDING 은 `INTERNAL_ERROR` 가 되므로 앱에서 다시 시도해야 한다.

### 발표 당일 GPU 절차 (2026-11-25 정함, 리허설 1회)

2026-11-25 사용자 결정은 "일찍 켜기 + 녹화 영상" 이다. 같은 볼륨의 예비 Pod 를 시연 시간에 함께 두는 방법([REDUNDANCY.md](REDUNDANCY.md) §6 Phase G 의 G-c)은 쓰지 않는다. 리허설 기록은 [data/2026-11-25_demo-gpu-rehearsal](data/2026-11-25_demo-gpu-rehearsal/README.md) 에 있다.

1. **동결일(12/01 [제안])에 시연 코드를 고정한다** [계획]
   - 그날 main 에 태그 `demo-1204` 를 단다. 태그는 사용자가 정한다.
   - 시연 주간에 만드는 Pod 의 시작 명령은 `checkout -q -B main origin/main` 대신 `checkout -q -B main demo-1204` 를 쓴다. 나머지는 위 2번의 자동 시작 명령과 같다.
   - 위 2번의 세 Pod 처럼 `origin/main` 을 받는 시작 명령으로 켜면, 동결 뒤 병합된 코드가 시연 Pod 에 들어간다.
2. **T-2h: 새 Pod 를 만든다**
   - 멈춘 Pod 의 start 는 같은 호스트 GPU 를 못 받는 일이 잦았다(위 1번, 2~4차). 그래서 처음부터 새 Pod 로 만든다.
   - 설정은 A40 SECURE, EU-SE-1, 볼륨 `z4kgw4v5zi` → `/workspace` 다.
   - GPU 조건은 `gpu.minCudaVersion 12.4`, `minVcpuCountPerGpu 4`, `minRamPerGpu 24` 다.
   - 컨테이너 디스크 20 GB, 포트 없음.
   - env 는 `BESIDE_INFERENCE_TOKEN={{ RUNPOD_SECRET_beside_inference_token }}` 다.
   - 이미지는 `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`, 시작 명령은 1번의 고정본이다.
   - 같은 볼륨의 다른 Pod 는 모두 꺼져 있어야 한다(위 3번).
   - 리허설: 생성부터 VM 이 `LOADING` 을 본 때까지 43.8 s, `UP` 까지 89.6 s [실험 결과, 1회, Pod `j0g1iqr40i0gxk`·코드 `d6e25d1` 고정].
3. **확인**
   - VM backend 로그에 `inference service LOADING -> UP` 이 찍히는지 본다.
   - VM 에서 `curl -s 100.83.86.62:8002/healthz` 로 `instanceId`·`modelLoaded:true` 를 본다.
   - 앱이나 `scripts/e2e_mock.sh https://beside-team.duckdns.org <사진>` 으로 1건을 끝까지 돌린다.
   - 리허설의 첫 작업은 e2e 22.1 s 였다(모델을 올린 뒤 첫 추론이라 느림). 그다음 작업들의 `processingMs` 는 8.6~11.9 s 였다 [실험 결과].
4. **실패하면**
   - 재고 오류("not enough free GPUs" 등)면 같은 설정으로 다시 만든다. 다른 데이터센터는 볼륨이 묶여 있어 바로 쓸 수 없다.
   - 발표 시각까지 `UP` 이 안 되면 **녹화 영상**으로 시연한다. 녹화는 사람이 미리 한다([CHECKLIST](../CHECKLIST.md) 0절 M8).
5. **끝나면** Pod 를 멈추고 지운다. 시연 뒤 남은 작업이 없는지 `/admin` 으로 먼저 본다.
   - 지우면 컨테이너 디스크만 사라진다. 모델·Tailscale 상태는 볼륨에 남는다.
- 시연 중에는 다음을 하지 않는다.
  - 운영 배포
  - 같은 볼륨으로 Pod 하나 더 켜기
  - `.env` 의 `INFERENCE_BASE_URL` 바꾸기

## 남은 일 [계획]
- 2026-11-25 확인으로 이 목록에서 뺀 것(끝난 일):
  - `inference.timeout-ms` 120 s(2026-11-21 안건 #7)는 운영에 들어갔다. 5차 시험 전 main `fe10683`(`api-v1.1`) 배포(2026-11-21 01:06~01:07 UTC)부터 120 s 다. 2026-11-22 에는 `625c2a5`(07:05~07:08 UTC 배포)였고, 지금 VM 은 `a44b3ed`(배포 창 2, 2026-11-26 00:04~00:08 UTC)다. 두 커밋 모두 `application-real.properties:17` 이 `120000` 이다.
  - FD16b·FD4·FD5·FD9 의 고침은 PR #22 로 병합하고 VM 에 배포했다(2026-11-20 15:53 UTC, VM 감시 재설치 포함) [적용]. Pod 를 main 으로 다시 켜기(`instanceId`)와 FD16b·FD5·FD9 다시 재현은 [5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 7절(2026-11-21)에서 했다 [실험 결과]. 단 운영 backend 가 아니라 훈련 backend 였고, 알림(🧱 포함)은 팀 채널이 아니라 VM 안 훈련 수집기로 갔다. FD16b 는 `instanceId` 길만, FD9 는 `docker pause` 로만 봤다.
  - 스냅샷 일정이 만든 스냅샷: READY 5개(`beside-vm-asia-northeast3-a-20261120191411-6ww5dte2` ~ `beside-vm-asia-northeast3-a-20261124191411-l434mv0g`, 매일 19:14 UTC, `gcloud compute snapshots list`). 첫 것은 2026-11-20 19:14 UTC 다.
- 아직 재현하지 않은 것: FD4(디스크 가득 참 → `INTERNAL_ERROR`), FD16b 의 다른 두 길(120 s 시간 초과, GLB 내려받기 60 s 무응답), FD9 진짜 블랙홀(연결 자체가 안 됨), 운영 R4 가 🧱 를 팀 채널로 보내는 것.
- FD9 의 `docker pause` 에서 본 중복('적어도 한 번')을 받아들일지 정한다.
- 5차 때 볼륨에 남은 작업 폴더(`207d1dce`)를 정리한다([5차](../perf/load/2026-11-21_vm-5th.md) 11절 4번).
- FD4 는 계약에 있는 `INTERNAL_ERROR` 를 쓴다(뜻이 "서버 오류"라 맞고, 앱 문구도 "서버에 문제가 생겼어요"). 디스크 부족 전용 코드는 만들지 않았다. 필요하면 계약 회의 안건으로 낸다.
- R3: 알림 채널을 만든 뒤 가동 확인과 알림 정책을 켠다. 부하 시험 때는 `uptime_check.sh pause`.
- 실제 모델이 멈춘 경우의 🐢 와 R4 의 디스크·백업 알림을 재현한다. FD2·FD13·FD17·FD18·R7 은 2026-11-20 2차 확인에서 다시 재현했다([결과](2026-11-20_failure-drills.md) '2차 확인').
