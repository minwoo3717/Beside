# backend 를 두 대 이상 띄우거나 대기 서버로 넘기지 못하게 하는 코드·운영 제약 (2026-11-25 조사)

backend 는 지금 두 대를 동시에 돌릴 수 없습니다. REDUNDANCY.md 1절의 다섯 가지는 코드로 모두 확인했고, 그 밖에 18가지를 더 찾았습니다(표의 6~23번).

- **기준 커밋**: 줄 번호는 main `d6e25d1` 기준입니다. VM 배포 커밋 `625c2a5` 와 backend·deploy·generation 에서 차이가 없습니다(`git diff --stat 625c2a5 d6e25d1` 빈 출력).
- **지금 브랜치와 다른 점**: `fix/review-2026-11-25` 는 조사 중에도 다른 세션이 커밋하고 있었습니다. 그 브랜치의 `InferenceSlots.java`·`RealJobWorker.java`·`Caddyfile` 은 줄 번호가 다를 수 있습니다.
- **조사 방법**: 파일은 하나도 만들거나 고치지 않았습니다. VM 과 GCP 에서는 읽기 명령만 썼습니다.

## 0. 이번에 읽기로 확인한 운영 사실

- **[확인] 정기 스냅샷**: 일정이 만든 스냅샷이 2026-11-20~24 에 매일 19:14 UTC 로 5개 있습니다(`gcloud compute snapshots list`, 저장 위치 asia-northeast3). REDUNDANCY.md:82 에서 [계획] 이던 "첫 스냅샷 확인"이 이제 끝난 셈입니다.
- **[확인] R3 외부 가동 감시는 아직 없습니다**: `gcloud monitoring uptime list-configs` 결과가 비어 있습니다.
- **[확인] 고정 IP**: `beside-ip` 34.64.91.254 는 지역 주소입니다(create_vm.sh:36,70 `--region`). 같은 지역의 다른 zone VM 으로 옮길 수 있습니다.
- **[확인] VM 상태**:
  - `~/Beside` 의 커밋은 `625c2a5` 입니다.
  - backend 메모리는 **1.537 GiB / 3 GiB** 입니다(docker stats).
  - 데이터 볼륨은 모두 합쳐 약 54 MB 입니다: db 94 KB, uploads 3.2 MB, results 38 MB(작업 폴더 25개), preprocessed 12 MB, backup 760 KB(zip 48개), events.jsonl 16 KB.
- **[확인] 자동 시작 설정**: `beside-watchdog.timer`·`beside-backup.timer`·`beside-block-metadata.service`·`tailscaled` 가 모두 enabled 입니다. 그래서 스냅샷으로 만든 VM 은 켜지자마자 감시·백업·Tailscale 이 돕니다.
- **[확인] VM Tailscale**: 노드 상태 폴더 `/var/lib/tailscale` 가 부트 디스크에 있습니다(`beside-vm` 100.98.76.5).
- **[확인] 따로 남은 컨테이너**: compose 밖의 `beside-stub`(이미지 `beside-stub:local`, restart=unless-stopped)가 2026-11-20 04:26 UTC 부터 돌고 있습니다(53 MiB / 512 MiB 사용). 장애 훈련 때 남은 것으로 보입니다 [가설]. 그대로 두었습니다.

## 1. 제약 표

- 1~5번은 REDUNDANCY.md:25~30 의 다섯 가지를 코드로 검증한 것이고, 6번부터가 새로 찾은 것입니다.
- **AA** 는 두 대 동시 운영(active-active), **SB** 는 대기 서버로 넘기기(active-standby)입니다.
- 크기는 모두 [가설] 추정입니다.

| # | 제약 | 근거 (파일:줄) | AA 면 생기는 일 | SB 면 생기는 일 | 풀려면 바꿀 것 | 크기 |
|---|---|---|---|---|---|---|
| 1 | H2 내장 파일 DB, `AUTO_SERVER` 없음 | application.properties:9-13 (URL `jdbc:h2:file:…;DB_CLOSE_ON_EXIT=FALSE`), application-real.properties:50-53, build.gradle.kts:20 (h2 만 있음) | 두 번째 JVM 은 파일 잠금으로 시작하지 못합니다. 코드 주석 근거이고 이번에 실행해 보지는 않았습니다 | DB 를 백업 zip 이나 스냅샷으로 옮겨야 합니다. 복제 기능이 없습니다 | PostgreSQL 로 이전 + 드라이버 + 데이터 옮기기 | 100~200줄, 2~3일 |
| 2 | 파일이 로컬 디스크에 있고, DB 행이 그 **로컬 상대 경로**를 담음 | JobServiceImpl.java:391,396 (`StoredUpload(…, target.toString())`), RealJobWorker.java:183,339 (`resultDir/jobId/base.glb`), JobServiceImpl.java:294-307 (`resolveAsset` 는 `Path.of(path)`), Dockerfile:30 (WORKDIR /app), compose:57 | 다른 노드가 만든 작업은 asset 404 (ASSET_NOT_FOUND) | 볼륨 레이아웃이 같으면 됩니다(같은 이미지면 충족) | 객체 저장소 키로 바꾸고 기존 행 이전, asset 은 GCS 에서 흘려 보내기. 삭제(JobDeletionService.java:94-110)와 전처리(CutoutStage.java:65-70)도 함께 | 300~500줄, 3~5일 |
| 3 | 작업 배분이 JVM 메모리 `@Async` 큐 | MockBackendApplication.java:9, RealJobWorker.java:116-118, JobServiceImpl.java:419-428. `spring.task.execution` 설정이 없어 기본값(8스레드, 무한 메모리 큐)입니다 | 작업을 받은 노드만 그 작업을 돌립니다. 그 노드가 죽으면 이어받는 길이 없습니다 | 큐에만 있던 작업은 사라지고, 다음 시작 때 실패로 닫힙니다(4번) | DB 폴링 + 임대(`FOR UPDATE SKIP LOCKED`, 임대 만료·하트비트) | 400~700줄, 1~2주 |
| 4 | 시작 때 `InterruptedJobRecovery` 가 PENDING·PROCESSING 을 **모두** FAILED(INTERNAL_ERROR)로 닫음 | InterruptedJobRecovery.java:34-35,47-54. 닫을 때 JobFinisher.java:95 의 이벤트가 나가 Discord ❌ 도 보냅니다 | 한 대를 재시작하면 다른 대의 작업까지 실패합니다 | **넘기는 순간에는 오히려 맞는 동작입니다**: 대기 서버가 뜰 때 진행 중이던 작업을 닫고, 앱이 다시 시도합니다 | 임대가 만료된 행만 닫는 정리 작업으로 바꾸기 | 3번에 포함 |
| 5 | 추론 슬롯이 JVM 마다 있음 | InferenceSlots.java:15-24,32 (메모리 줄 + `free`), RealJobWorker.java:101,128, application-real.properties:25 (=1) | 두 대 × 1 = GPU 에 동시 요청 2건 → 15번 | 영향 없음 | 전역 슬롯(DB 임대 행)이나 GPU 마다 슬롯 | G2 참고 |
| 6 | **[새로] 상태 전이 경합을 JVM 안 잠금으로만 막음 + `@Version` 없음** | JobTransitions.java:30-31 ("a lock inside this process is enough"), :54-66 (`start` 가 읽고 나서 저장, synchronized). 저장소 전체에 `@Version`·`@Lock` 이 없습니다. ShareService.java:66-72 (`synchronized create`, 작업당 공유 링크 20개 상한) | 다른 노드에서 들어온 취소(PENDING→CANCELED)를 `start` 저장이 덮어써서 취소한 작업이 GPU 에서 돕니다 [가설: 경합 창은 ms 단위]. 공유 링크 상한도 넘을 수 있습니다 | 영향 없음 | 조건부 UPDATE(`WHERE status=? AND attempt=?`)나 `@Version`. 재시도는 이미 조건부 UPDATE 입니다(JobRepository.java:62-64) | 100~200줄, 1~2일 |
| 7 | **[새로] 대기열 상한의 예약이 JVM 메모리** | AdmissionGate.java:41-42 ("One JVM owns the database file"), :91-96 (`reserved`·`retrying`·`creating`), :216-231 | 동시에 들어오는 요청이 노드 수만큼 상한을 넘을 수 있습니다. 운영에서는 지금 꺼져 있습니다(application.properties:99) | 영향 없음 | DB 행으로 예약하거나, 넘침을 허용하고 문서화 | 50~150줄 |
| 8 | **[새로] 대기 순서와 예상 시간이 줄 하나를 가정** | QueueClock.java:16-22 (JVM 안에서만 단조 증가), QueueEstimator.java:26-27,42 (GPU 하나 산수), :76-77,136 (중앙값 캐시를 그 JVM 의 이벤트로만 버림) | 같은 queuedAt 이 나올 수 있고, `queue.ahead` 가 실제 순서와 어긋납니다 | 영향 없음 | DB 시퀀스·DB 시각, 전역 순서 | 30~80줄 |
| 9 | **[새로] H2 전용 코드** | DatabaseBackup.java:122 (`BACKUP TO`), DatabaseFileGuard.java:29,51-62 (`jdbc:h2:file:` 만 검사), application.properties:20 (`ddl-auto=update`, 이전 도구 없음) | PostgreSQL 로 가면 백업이 실패하고(로그만 남음) FD5 방지 장치가 꺼집니다. 두 노드가 동시에 `ddl update` 를 돌립니다 [가설: 위험] | DatabaseFileGuard 가 **빈 볼륨의 대기 서버는 시작을 거부** → 쓸모 있음 | Flyway 등 스키마 이전 도구, PG 백업(관리형 또는 pg_dump), Guard 일반화 | 1번에 포함 + 50줄 |
| 10 | **[새로] `@Scheduled` 4개가 노드마다 돎** | DatabaseBackup.java:104 (매시 :05), InferenceHealthMonitor.java:180 (15 s), JobQueueGauge.java:43 (5 s), AdmissionGate.java:257 (1분). 그 밖에 WebhookNotifier.java:106,108 과 ImageDecoding.java:51 은 JVM 안 실행기라 무해 | 백업 2배(공유 H2 일 때), `/healthz` 확인 2배, 게이지는 DB 전체 값이라 Prometheus 에서 합하면 2배 | 대기 서버의 backend 를 꺼 두면 없음 | 리더 하나만 돌리기(ShedLock 등), 대시보드는 `max()` | 30~60줄 |
| 11 | **[새로] GPU 알림 상태가 JVM 안 → 두 대면 2번 알림** | InferenceHealthMonitor.java:136,197,215,228,240 → WebhookNotifier.java:110-118,210-227 | 🔴/🟢·🐢🔑🧩 이 노드 수만큼 겹칩니다. 작업 결과 알림은 그 작업을 돌린 노드만 보내서 겹치지 않습니다 | 대기 VM 은 `.env` 의 웹훅이 같아서, 켜면 바로 알림이 납니다(19번) | GPU 알림은 리더만 보내거나 한 노드에만 `NOTIFY_WEBHOOKURL` | 20~50줄 |
| 12 | **[새로] events.jsonl 이 노드마다 로컬 파일** | EventLog.java:31,35 (`synchronized` 는 JVM 안만),71-72. VM 밖 백업 대상이 아닙니다(offsite_backup.sh:4-5) | 측정 로그가 나뉩니다. 공유 디스크에서 쓰면 줄이 섞일 수 있습니다 [가설] | 넘어가면 그 전 줄은 잃습니다. 같은 내용이 `job_runs` 표에 있어 DB 로 되살릴 수 있습니다(EventLog.java:19-21) | 노드 태그를 붙이거나 `job_runs` 만 쓰기 | 10~30줄 |
| 13 | **[새로] 추론 주소가 하나이고, 재시작 감시도 주소 하나를 전제** | compose:27, application-real.properties:3, InferenceClient.java:154-167 (baseUrl 하나), :338-345 (glbUrl 을 같은 주소에서 받음), :243-258 (DELETE /artifacts 도 같은 주소), InferenceHealthMonitor.java:153, RealJobWorker.java:292-301 (`restartWatch` 는 감시 하나의 instanceId 와 비교) | — | — | **주소를 바꿔 가며 확인하면 instanceId 가 달라 보여, 진행 중 요청을 "재시작"으로 보고 포기합니다**. 주소마다 감시를 두고, GLB 받기·DELETE 는 작업을 돌린 주소로 보내야 합니다 | G1 참고 |
| 14 | **[새로] 추론 서비스의 상태가 프로세스 안** | app.py:119 (`MODEL_LOCK`), :122 (`INSTANCE_ID`), :437-438 (`_CURRENT`·`_CANCELLED`), :105,406 (`WORK_DIR/jobId`), :41-42 (DELETE 는 "이 머신"의 실행만 취소) | — | — | Python 은 그대로 둡니다. Spring 이 작업을 돌린 Pod 를 기억하면 됩니다 | G1 참고 |
| 15 | **[새로] backend 두 대가 GPU 하나를 쓰면, Python 잠금 대기가 시간 초과에 들어감** | app.py:780 (`with MODEL_LOCK`), application-real.properties:15-17 (`timeout-ms` 는 "그 작업 자기 요청만" 센다는 전제) | 두 번째 작업이 PROCESSING 인 채 잠금을 기다리고, 120 s 안에 끝나지 않으면 INFERENCE_TIMEOUT 입니다. queuedMs·processingMs 측정도 틀어집니다 | 영향 없음 | 전역 슬롯(5번) | 5번에 포함 |
| 16 | **[새로] 전처리 ONNX 메모리** | compose:66-68 (`mem_limit 3g`), 실측 1.537 GiB. 노드마다 세마포어(CutoutStage.java:46, UploadCheck.java:43) | 8 GB VM 한 대에 backend 둘 + Caddy 는 한도 합이 8 GB 라 못 넣습니다. 노드마다 4 GB 이상 필요 [가설] | 대기 VM 도 같은 크기가 필요합니다(e2-standard-2) | 노드 크기 정하기 | 0줄 |
| 17 | **[새로] 앞단이 하나** | Caddyfile:101 (`reverse_proxy {$BACKEND_UPSTREAM}`, 하나뿐이고 `health_uri`·`lb_policy` 없음), compose:103. Caddy·인증서(`caddy_data`)가 VM 안에 있고, DuckDNS A 레코드 1개 | 다른 VM 의 backend 를 붙여도 Caddy 가 있는 VM 이 여전히 단일 장애점입니다 | 고정 IP 를 옮기면 끝납니다. 인증서는 스냅샷에 들어 있습니다 | 외부 LB(헬스 체크)나 Caddy 를 노드마다 두고 그 앞에 LB | 설정 30줄 + $18/월 (REDUNDANCY:43) |
| 18 | **[새로] Tailscale 노드 키 복제** | VM: `/var/lib/tailscale`(부트 디스크, deploy/README.md:129-131) → 스냅샷에 함께 담깁니다. Pod: start_pod.sh:49 (`TS_DIR="$WS/tailscale"` 고정, 따로 바꿀 수 없음), :146-153 (마지막 Pod id 경고만), RUNBOOK.md:78,89 | Pod 두 대가 한 볼륨을 쓰면 같은 키로 서로 밀어냅니다 | 스냅샷으로 만든 대기 VM 을 운영 VM 이 살아 있을 때 켜면 서로 끊습니다 | 대기 VM 은 `tailscale logout` 뒤 새 키로 로그인(미리 만든 키). Pod 는 `TS_DIR` 을 바꿀 수 있게 하거나 볼륨을 따로 | 스크립트 5~20줄 |
| 19 | **[새로] 스냅샷에 자동 시작 장치가 함께 실림** | 타이머 enabled 확인(0절). 감시는 `.env` 의 웹훅을 씁니다(beside-watchdog.sh:21,30). `beside-stub` restart=unless-stopped | — | 대기 VM 이 켜지는 순간 감시가 재시작과 🔴 알림을 보내고, 백업 타이머가 **같은 버킷 `db/` 에 다른 줄기의 zip** 을 올립니다(offsite_backup.sh:4,164). 그러면 "최신 백업"이 오래된 대기 VM 것이 될 수 있습니다 | 대기 VM 첫 시작 절차: `/etc/beside-maintenance` 만들기, `systemctl stop beside-backup.timer`, stub 정리 | 절차 10줄 |
| 20 | **[새로] VM 밖 백업은 쓰기만 되고, 파일과 DB 의 RPO 가 다름** | offsite_backup.sh:6-8 (서비스 계정이 objectCreator 뿐이라 읽기·목록 불가), :4-5 (DB 는 매시, 사진·GLB 는 **하루 1번**), application-prod.properties:31-36 | — | 대기 서버는 그 계정으로 버킷을 읽지 못합니다. 버킷에서 되살리면 DB 는 최대 약 75분 전, GLB 는 최대 24 h 전이라 **COMPLETED 인데 GLB 가 없는 작업은 asset 404** 가 납니다(JobServiceImpl.java:303-305) | 대기 서버 전용 읽기 계정, 파일 올리기를 매시로 | 셸 5~20줄 |
| 21 | **[새로] Prometheus 대상이 하나** | deploy/monitoring/prometheus.yml:14 (`backend:8081`) | 두 번째 노드가 보이지 않습니다 | 영향 없음 | 대상을 늘리고 `instance` 라벨로 구분 | 5줄 |
| 22 | **[새로] compose 이름과 볼륨 이름 고정** | compose:8 (`name: beside`), :189-191 (`beside-storage`) | 같은 호스트에 두 번째 스택을 띄우면 실서버를 건드립니다. 다른 VM 이면 괜찮습니다 | 다른 VM 이면 괜찮습니다 | — | 0 |
| 23 | **[새로] 배포가 곧 재시작이라 진행 중 작업이 실패** | scripts/deploy_vm.sh:7-8 | 순차 배포가 안 됩니다(4번 때문) | 영향 없음 | 3·4번을 푼 뒤 비우고 배포 | 3번에 포함 |

**표에서 빼도 되는 것.** 아래는 DB 만 함께 쓰면 그대로 맞으므로 제약이 아닙니다.
- 멱등 키: DB PK 로 한 번만 INSERT 합니다(IdempotencyKeyEntry.java:14-17,57-60).
- 재시도: 조건부 UPDATE 입니다(JobRepository.java:62-64).
- 공유 링크·보관함·평가 데이터: `share_links`·`pets`·`job_ratings` 표에 있습니다.

## 2. 이중화 수준별로 최소한 바꿔야 하는 것

### L1 — 다른 zone 대기 VM 으로 빠르게 넘기기 (코드 변경 0)

- **이미 있는 것**:
  - 매일 스냅샷 [확인 5개]과 매시 DB 백업 [적용]이 있습니다.
  - VM 다시 세우기 145 s 를 FD6 에서 쟀습니다 [실험 결과].
  - 고정 IP 는 지역 주소입니다.
  - InterruptedJobRecovery 가 진행 중이던 작업을 안전하게 닫습니다(4번).
- **최소로 바꿀 것** (운영 절차, RUNBOOK 'VM 다시 세우기' 1~4번 보강):
  1. 운영 VM 을 먼저 멈추거나 `tailscale down` 합니다(18번). 살려 둔 채 확인만 하려면 대기 VM 에서 `tailscale logout` 하고 새 키로 붙습니다.
  2. 대기 VM 이 처음 켜지면 점검 표시를 만들고 백업 타이머를 멈춥니다. `beside-stub` 도 정리합니다(19번).
  3. 버킷의 최신 `db/` zip 은 사람 계정으로 받아서 'DB 복구' 명령으로 되살립니다(20번). 스냅샷 뒤에 생긴 작업을 살리기 위해서입니다.
  4. 고정 IP 를 옮기고, `healthz` 와 `deploy/test_caddy_routes.sh` 61개를 확인합니다.
  5. (선택) offsite_backup.sh 의 files/ 조건을 매시로 바꿔 GLB 404 창을 줄입니다(셸 몇 줄).
- **기대치 [가설]**:
  - RTO 는 10~20분, DB RPO 는 1시간 이하(최악 약 75분), 파일 RPO 는 24 h(5번을 하면 1 h)입니다.
  - 진행 중이던 작업은 INTERNAL_ERROR 로 끝나고 앱이 다시 시도합니다.
- **크기**: 코드 0줄, 절차·스크립트 0~30줄, 진짜 전환 연습 포함 반나절~1일 [가설]. RUNBOOK:83 의 "3~4번 [계획]"을 끝내는 일입니다.

### L2 — 대기 서버가 복제를 받다가 반자동으로 넘기기

L2 에는 두 길이 있습니다.

- **(a) 백업을 당겨 오는 따뜻한 대기 서버** (코드 0, 설정과 셸만):
  - 대기 VM 은 켜 두되 backend 는 끈 채로 둡니다. 타이머가 N분마다 버킷에서 DB zip 과 파일을 받아 자기 볼륨에 되살립니다.
  - 필요한 것:
    - 대기 VM 에만 줄 **읽기 전용 서비스 계정**(20번)
    - H2 백업 주기를 줄이기: `storage.backup.cron`·`keep` 은 설정만 바꾸면 됩니다(application-prod.properties:31-36)
    - 파일을 매시 올리기
    - 넘기기 스크립트: ① 운영 VM stop ② backend up ③ IP 이동
  - 계기는 R3 알림을 사람이 보고 실행하는 반자동입니다. Cloud Function 으로 자동화하는 것은 [계획] 이고 일이 큽니다.
  - Tailscale 은 스냅샷 복제가 아니라 새로 로그인한 별도 노드여야 합니다(18번).
  - 크기는 셸 150~250줄과 시험, 2~3일 [가설]입니다. 대기 VM 이 늘 켜져 있으므로 비용이 듭니다(가격 미확인).
- **(b) 지역 영구 디스크** (두 zone 동기, RPO 약 0):
  - `beside-storage` 를 별도 지역 디스크로 옮기고, 장애 때 대기 VM 이 강제로 붙입니다(force-attach). 코드는 0입니다.
  - REDUNDANCY.md:42 에서 "하지 않음"으로 정한 항목이라 다시 정해야 합니다. 서울 가격은 미확인입니다.
- **코드에서 바꿀 필요가 없는 이유**: 대기 서버가 시작할 때 InterruptedJobRecovery(4번)와 DatabaseFileGuard(9번)가 이미 맞게 동작합니다.

### L3 — API 두 대 동시 운영

1~23번 중 AA 열에 영향이 있는 것을 모두 풀어야 합니다. 핵심만 적습니다.

- **DB**: PostgreSQL 로 옮기고(1·9번), 스키마 이전 도구를 씁니다. 상태 전이는 조건부 UPDATE 나 `@Version` 으로 바꿉니다(6번).
- **파일**: GCS 로 옮기고 DB 경로를 객체 키로 이전합니다(2·12번).
- **작업 배분**: DB 임대 큐로 바꾸고, 임대 만료 정리로 InterruptedJobRecovery 를 대신합니다(3·4·23번).
- **GPU 슬롯**: DB 에 전역 슬롯을 둡니다(5·15번). 대기열 상한과 순서도 DB 기준으로 바꿉니다(7·8번).
- **예약 작업·알림**: 리더 한 대만 돌리고 GPU 알림 중복을 막습니다(10·11번).
- **앞단·감시**: LB 와 헬스 체크를 두고(17번) Prometheus 대상을 늘립니다(21번).
- **크기·비용**: 1,500~3,000줄, 2~4주 [가설], 월 $160~290(REDUNDANCY.md:43).

### G1 — 추론 주소 여러 개 + 건강 상태로 넘기기 (GPU 는 한 번에 하나만 씀)

- **backend 에서 바꿀 것**:
  - `inference.base-urls` 를 목록으로 바꾸고, 주소마다 InferenceHealthMonitor 상태를 둡니다(13번).
  - 작업을 시작할 때 UP 인 주소 하나를 골라 그 시도에 고정합니다. GLB 받기·DELETE /artifacts·`restartWatch` 는 모두 그 주소 기준이어야 합니다(13·14번).
  - 슬롯은 1 그대로입니다.
- **Pod 쪽**: 두 번째 Pod 는 자기 Tailscale 노드가 필요합니다. `start_pod.sh` 의 `TS_DIR`·로그·`beside-norestart`·`WORK_DIR` 을 Pod 마다 다르게 하거나, 볼륨을 따로 둡니다(18번).
  - 한 네트워크 볼륨을 두 Pod 에 동시에 붙일 수 있는지는 확인하지 않았습니다 [가설].
  - 볼륨은 데이터센터에 묶여 있습니다.
- **크기**: backend 300~500줄 + 시험, 셸 20~40줄, 2~4일 [가설]. 예비 Pod 를 꺼 둔 채 두면 GPU 를 못 구하는 문제는 그대로입니다. 줄어드는 것은 넘기는 시간뿐입니다.

### G2 — Pod 두 대 동시 처리 (슬롯 풀)

- **G1 위에 더할 것**:
  - InferenceSlots 를 "줄 하나 + 주소마다 빈 슬롯"으로 바꿉니다(5번).
  - `AdmissionGate.defaultStaleAfterMs` 가 쓰는 슬롯 수를 전체 슬롯으로 바꿉니다(AdmissionGate.java:117,137-146).
  - N·R 을 다시 계산합니다(application.properties:95-98).
  - QueueEstimator 를 전체 슬롯으로 나눕니다(QueueEstimator.java:42).
  - 지표에 주소 태그를 붙이고, 처리량(mu)을 부하 시험으로 다시 잽니다.
- **크기·비용**: G1 + 200~400줄, 2~3일 [가설]. A40 2대 $1.18/h (REDUNDANCY.md:48 의 $0.59/h × 2).

## 3. 이미 있어서 재사용할 수 있는 것

| 무엇 | 위치 | 쓰는 곳 |
|---|---|---|
| instanceId 로 서비스 재시작 감지 | RealJobWorker.java:292-301, InferenceHealthMonitor.java:188-193, app.py:120-122 | G1·G2 에서 주소마다 그대로 씁니다 |
| GPU 가 없을 때 PENDING 으로 30분 대기 | RealJobWorker.java:254-282, application-prod.properties:40-41 | Pod 를 바꾸는 동안 작업이 실패하지 않고 버팁니다 |
| DOWN 알림 45 s 유예 | application-prod.properties:46, WebhookNotifier.java:53-57 | 넘기는 중 생기는 잘못된 알림을 줄입니다 |
| 시작 때 진행 중 작업을 닫는 동작 | InterruptedJobRecovery.java:47-54 | L1·L2 에서 넘긴 직후 안전하게 정리됩니다 |
| 빈 DB 로 시작하지 않는 장치 | DatabaseFileGuard.java:51-62, compose:54 | 대기 서버를 잘못 띄우는 것을 막습니다 |
| 멱등 키와 조건부 재시도 | IdempotencyKeyEntry.java:14-17, JobRepository.java:62-64 | L3 에서 DB 로 경합을 막는 본보기입니다 |
| 실행 중 H2 백업 + VM 밖 업로드 + 복구 명령 | DatabaseBackup.java:104-126, offsite_backup.sh, RUNBOOK.md:65 'DB 복구' | L1·L2 의 데이터 옮기기 |
| VM 만들기·스냅샷·버킷·가동 감시 | deploy/gcp/create_vm.sh:15,36 (zone 인자), snapshot_schedule.sh, backup_bucket.sh, uptime_check.sh:8-11 (create·pause·resume·status) | L1 의 대기 VM, L2 의 계기(R3) |
| 배포 스크립트 | scripts/deploy_vm.sh (호스트 인자) | 대기 VM 을 main 에 맞추기 |
| VM 감시 R4 와 점검 표시 | deploy/watchdog/beside-watchdog.sh:14,31 | 대기 VM 첫 시작 때 끄기(19번) |
| 장애 훈련 도구 | scripts/fault/vm_drill.sh:7-13, drill_client.py | 넘기기 연습의 RTO·RPO 측정 |
| 공개 경로 61개 확인 | deploy/test_caddy_routes.sh | 넘긴 뒤 확인 |
| backend 주소를 환경 변수로 바꾸기 | compose:103 `BACKEND_UPSTREAM` | Caddyfile 을 고치지 않고 다른 backend 를 붙이기 |
| Pod 시작·자동 재시작(R7)·Pod 바뀜 경고 | deploy/runpod/start_pod.sh:146-153, supervise_inference.sh | G1·G2 의 두 번째 Pod |
| 큐 게이지가 DB 전체를 셈 | JobQueueGauge.java:47 | 여러 노드에서도 값이 맞습니다(대시보드는 `max()`) |

**결론 [판단]**: 발표(12-04)까지 9일 안에 현실적인 것은 **L1 을 끝까지 연습하는 것**입니다. 진짜 전환을 해 보고 18~20번 절차를 보강하면 됩니다. 시간이 남으면 **G1 을 설계 문서로** 남깁니다. L3 와 G2 는 4절 '나중 단계'에 둡니다.