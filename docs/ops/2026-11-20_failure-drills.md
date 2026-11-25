# 2026-11-20 장애 훈련 — 실제 배포에서 재현한 장애 18가지

- **무엇을**: 운영 VM(GCP e2-standard-2, 서울)과 Runpod A40 Pod 에서 장애를 일부러 일으키고, 감지·복구 시간과 잃은 작업·데이터를 쟀다.
- **계획**: `C:\Users\minwo\.claude\plans\gcp-precious-unicorn.md` 첫 부분. 시나리오 ID 는 FD 를 쓴다. 계약 회의 결정 D1~D11 과 다른 번호다.
- **대응·이중화**: [RUNBOOK.md](RUNBOOK.md)(시나리오별 대응), [REDUNDANCY.md](REDUNDANCY.md)(이중화 조사·결정).
- **표기**: [실험 결과] 원시 데이터가 아래 폴더에 있다. 예외는 블록 1 의 서버 쪽 시각과 2차 확인의 Pod 쪽 값(kill 시각, pid, `supervisor.log` 줄)으로, 세션에서 읽은 값이라 원시 파일이 없다(아래 '데이터'). [계산] 그 데이터로 낸 값. [가설] 확인하지 않은 설명. [구현됨] 코드·시험이 리포에 있다(RUNBOOK 과 같은 뜻). [적용] 운영에 켜 둠. [계획] 하지 않음.

## 요약

- **운영 중단**:
  - 1차: 03:11~04:28 UTC(12:11~13:28 KST), 77분. 운영 H2 해시는 백업 때와 같았다(`8109f8cb…`).
  - 2차: 05:37:39~05:53:08 UTC(14:37~14:53 KST), 15.5분. 운영 H2 해시가 창 전 백업과 같았다(`4a18b3ba…`).
  - 두 번 모두 디스코드로 시작·종료를 공지했고, 공개 경로 61/61, healthz `real,prod` 로 복구했다.
- **2차 확인** [실험 결과]: 대응을 배포한 뒤(main `ce1a685`, 05:32~05:34 UTC) FD17·FD13·FD2·FD18 과 Pod 감독 루프(R7)를 다시 재현했다. 대응 전에는 장애를 알리는 알림(FD17 은 작업마다 실패 웹훅뿐)이나 자동 복구가 없던 다섯 가지 모두에서 알림이 가거나 사람 손 없이 회복했다. 아래 '2차 확인'.
- **5차 부하 시험 때 다시 재현** [실험 결과]: 2026-11-21 03:13~03:51 UTC, main `fe10683`. 2차 뒤에 고친 셋(FD16b·FD5·FD9)과 느린 추론(F1b, 시간 초과 120 s)을 다시 재현했고, 고친 셋 모두 뜻한 대로 동작했다. 결과는 [2026-11-21_vm-5th.md](../perf/load/2026-11-21_vm-5th.md) 7절, 원시 데이터는 [data/2026-11-21_drills/](data/2026-11-21_drills/).
- **감지가 안 되는 장애가 가장 위험했다** [실험 결과, 1차].
  - 아래 장애는 모두 공개 healthz 가 200 이었고, GPU 상태 알림도 없었다.
    - FD13 추론만 멈춤
    - FD17 토큰 불일치
    - FD4 디스크 가득 참
    - FD5 DB 파일 유실: 빈 DB 를 조용히 새로 만들었다
    - FD15 GPU 메모리 부족
  - FD18 모델 로드 실패는 "로딩 중"과 구별되지 않았다.
  - 2차: FD17 은 🔑, FD13 은 🐢, FD18 은 🧩 알림이 갔다. FD5 는 아직 감지 장치가 없고, FD15 는 작업 실패 알림뿐이다. FD4 는 VM 감시의 디스크 80 % 알림을 켰지만 재현하지 않았다.
  - 2차 뒤 고침 [구현됨: PR #22] [적용: 2026-11-20 15:53 UTC 배포]: FD5 는 prod 가 DB 파일 없이 시작하지 않고(R4 가 🧱 "시작하자마자 계속 꺼져요" 로 알림, 같은 시각 VM 감시 다시 설치), FD4 는 이 서버 디스크 때문에 GLB 를 저장하지 못하면 `INTERNAL_ERROR` 로 나온다. 2026-11-25 확인: FD5 는 [5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 7절(2026-11-21)에서 훈련 backend 로 다시 재현했다. 시작을 거부했고(재시작 10번, 빈 DB 없음) 🧱 가 44.6 s 뒤 왔다 [실험 결과]. 그 🧱 는 따로 띄운 감시 인스턴스가 팀 채널이 아니라 VM 안 훈련 수집기로 보냈다. FD4 는 다시 재현하지 않았다 [계획].
- **오래 걸린 복구**:
  - FD16b(부하 중 Pod 정지): GPU 는 약 3분 만에 다시 UP 이었다. 그런데 끊긴 Pod 로 보낸 요청 하나가 600 s 시간 초과까지 하나뿐인 슬롯을 잡았다. 그래서 대기 작업 최대 602 s 로, 앱의 600 s 를 넘었다 [실험 결과]. 2차 뒤 고침 [구현됨: PR #22] [적용: backend 2026-11-20 15:53 UTC, `instanceId` 를 내는 Pod 쪽은 다시 켤 때]: GLB 내려받기가 60 s 동안 데이터를 받지 못하면 끊고, 서비스가 다시 시작되면(healthz `instanceId`) 그 전 요청을 바로 포기한다. 이 경우라면 슬롯은 정지 뒤 약 1분에 풀린다 [계산]. 2026-11-25 확인: 5차(2026-11-21, 훈련 backend·실제 Pod, 시간 초과 120 s)에서 다시 재현했다. 같은 Pod 가 바로 GPU 를 다시 받아 `instanceId` 길로 끝났다: 끊긴 요청 1건이 52 s 에 `INFERENCE_UNAVAILABLE`, 기다린 5건 모두 완료·최대 74.3 s [실험 결과]. 120 s 시간 초과 길과 GLB 내려받기 60 s 길은 재현하지 않았다 [계획].
  - FD2(백엔드 멈춤): 189 s 동안 공개 경로가 응답하지 않았다. 아무도 재시작하지 않았고, 걸어 둔 SIGCONT 로만 풀렸다. 2차에서는 VM 감시(R4)가 멈춘 지 112 s 뒤 재시작했다.
- **잘 버틴 것** [실험 결과]: 잃은 작업 없이 회복했다.
  - FD10 tailnet 120 s 차단: 진행 중이던 작업도 TCP 재전송으로 살아났다.
  - FD11 지연·손실
  - FD14 추론 서비스 전체 멈춤
  - FD12 추론 꺼짐: 오프라인 대기가 버텼다.
  - FD3 VM 재시작: 외부에서 82.8 s, Tailscale·GPU 자동 복귀.
  - FD6 VM 통째 유실 → 스냅샷 복구: 서비스 응답까지 89 s, 운영 설정까지 145 s, DB 해시가 백업과 같았다.

## 조건

| 항목 | 값 |
|---|---|
| VM | `beside-vm` e2-standard-2, asia-northeast3-a, 리포 HEAD·이미지 `aec06d6`(2026-11-20 00:00 UTC 빌드) |
| 블록 1 backend | 부하 덮어쓰기 + 훈련 덮어쓰기([docker-compose.drill.yml](../../deploy/loadtest/docker-compose.drill.yml)), `real,prod`, 시험 볼륨, 사진 검사·크롭 끔, 웹훅 블랙홀(`http://192.0.2.1/`) |
| 블록 1 추론 | 훈련 스텁 `beside-stub-drill`. 운영 `beside-stub` 과 같은 코드, 훈련 전용 토큰, 패스스루 지연 4 s(FD1 은 12 s) |
| 블록 2 backend | 같은 덮어쓰기. Pod 주소·운영 토큰·운영 값(시간 초과 600 s, 오프라인 대기 1,800 s), 사진 검사·크롭 켬 |
| GPU | Runpod A40 `beside-gpu5`(`03rnszh20mywif`, EU-SE-1, 볼륨 `z4kgw4v5zi`), `start_pod.sh` 기본값(keep-alive 75 s, threads 8) |
| 외부 탐침·작업 | Runpod CPU Pod(US-CA-2)의 [drill_client.py](../../scripts/fault/drill_client.py). 1초마다 공개 healthz(시간 제한 5 s), 블록 1 은 15 s·블록 2 는 20 s 마다 CC0 개 사진 작업 하나 |
| 주입 | [vm_drill.sh](../../scripts/fault/vm_drill.sh). 주입마다 자동 되돌림(systemd-run)을 먼저 걸었다 |

데이터: [data/2026-11-20_failure-drills/](data/2026-11-20_failure-drills/)
- `client/b1|b2/{probe,flows}.jsonl`: 탐침과 작업 흐름
- `client/fd3/probe.jsonl`: FD3 탐침
- `vm/timeline.txt`: 주입·관찰 시각(1차·2차)
- `vm/block2-backend.log`: 블록 2 backend 로그. 토큰·웹훅 없음.
- `window2/`: 2차 확인. 아래 '2차 확인'.
- 블록 1 backend 로그는 남지 않았다. 설정을 바꿀 때마다 컨테이너를 다시 만들어서다. 블록 1 의 서버 쪽 시각은 이 문서의 표에만 있다(세션에서 읽은 로그 줄).

## 시나리오별 결과

표의 시각은 UTC 다. 외부 끊김과 작업 결과는 [analyze_drills.py](data/2026-11-20_failure-drills/analyze_drills.py) 출력([analyze_drills-output.txt](data/2026-11-20_failure-drills/analyze_drills-output.txt))이다 [계산].

| ID | 장애 | 감지 | 복구 | 외부 끊김 | 작업 결과 |
|---|---|---|---|---|---|
| FD1 | backend `kill -9`(호스트에서) | — | 0.36 s 뒤 컨테이너 재시작, 19.1 s 뒤 healthz 200 | 17.8 s(502) | 업로드 1건 502. 재시작은 PENDING·PROCESSING 을 `INTERNAL_ERROR` 로 닫는다(FD13 창의 11건) |
| FD2 | backend SIGSTOP 180 s | Docker unhealthy 101 s 뒤. **재시작 없음** | SIGCONT 21 s 뒤 healthy | **189 s**(응답 없음) | 업로드 2건 응답 없음 |
| FD7 | Caddy `kill -9` | — | 1.4 s | 2.0 s | 업로드 1건 연결 끊김 |
| FD17 | 추론 토큰 불일치 | **GPU 상태는 UP 그대로** | 설정을 고칠 때까지 없음 | 없음 | 8건 모두 바로 `INFERENCE_UNAVAILABLE`(HTTP 401). 작업마다 실패 웹훅뿐 |
| FD13 | healthz 는 UP, 추론만 멈춤(스텁 600 s, backend 시간 초과 60 s) | **없음**(UP 그대로, healthz 25 ms) | 없음 | 없음 | 60 s 마다 1건씩 `INFERENCE_TIMEOUT`. 대기열이 쌓였다. 운영 값 600 s 면 1건 실패에 10분이다 [계산] |
| FD12 | 추론 서비스 응답 없음(pause 260 s, 오프라인 대기 180 s) | 12.2 s 뒤 DOWN | unpause 뒤 UP, 기다리던 작업 재개 | 없음 | 180 s 를 넘긴 6건 `INFERENCE_UNAVAILABLE`, 14건 완료(최대 179 s) |
| FD9 | 디스코드 웹훅 실패(블록 1 내내) | — | — | — | 작업은 막히지 않았다. 대신 알림이 5 s 시간 초과로 하나씩 밀렸다(FD12 8분에 32건) |
| FD5 | DB 파일 유실 → 백업 복구(2분 백업) | **없음**: H2 가 빈 DB 를 새로 만들고 healthz 200 | 복구 시작 표시 → H2 `Restore` → healthz 200 까지 20.2 s | 20 s(정지·재시작) | 백업 전 표지 A 3건 복구, 백업 뒤 표지 B 3건 유실 → RPO = 백업 간격 |
| FD4 | 디스크 가득 참(300 MB 루프 장치) | **없음**(healthz 200) | 공간을 비우자 재시작 없이 회복 | 없음 | 남은 2 MB: GLB 저장 실패가 **`CONVERSION_FAILED`** 로 나옴(7건). 0 B: 업로드 500(5건). H2 는 깨지지 않았다 |
| FD16a | 멈춘 Pod 다시 켜기 | — | 같은 호스트 GPU 없음(400) → 배치 조건으로 새 Pod → 98 s 뒤 GPU UP | — | — |
| FD10 | VM→Pod tailnet 120 s 차단(iptables FORWARD) | 14.3 s 뒤 DOWN | 해제 8 s 뒤 UP | 없음 | 11건 모두 완료(최대 122 s). 진행 중이던 작업도 완료 |
| FD11 | `tailscale0` 지연 300 ms·손실 5 %, 180 s | 없음(UP) | 해제 뒤 약 1¾분에 대기열 정리 | 없음 | 14건 모두 완료(최대 112 s). 슬롯 간격이 약 9 s → 35~45 s |
| FD14 | uvicorn SIGSTOP 120 s | 2.0 s 뒤 DOWN | SIGCONT 1.5 s 뒤 UP | 없음 | 12건 모두 완료(최대 123 s) |
| FD18 | 모델 로드 실패(체크포인트 경로 틀림) | DOWN → **LOADING 에 머묾**: healthz 가 `status ok, modelLoaded false` 라 로딩 중과 구별 안 됨 | 바르게 다시 시작 52.6 s 뒤 UP | 없음 | 14건 모두 완료(최대 247 s). 그대로 두면 30분 뒤 실패 [계산] |
| FD15 | GPU 메모리 부족(다른 프로세스가 점유) | 없음(UP) | 메모리를 풀자 서비스 재시작 없이 회복 | 없음 | 점유 중 5건 `INFERENCE_FAILED`. 8.6 GB 남았을 때는 실패 없음 |
| FD16b | 부하 중 Pod 정지 → 같은 Pod 다시 켜기 | 30.6 s 뒤 DOWN | 컨테이너 93 s, GPU UP 3분. 그러나 **정지 직후 보낸 작업이 600 s 시간 초과까지 슬롯을 잡음** | 없음 | 그 1건 `CONVERSION_FAILED`(GLB 내려받기 미완료). 46건 완료, 최대 **602 s** |
| FD3 | VM stop → 서비스 계정 교체 → start | — | 정지 54 s. start 요청에서 컨테이너 시작 22 s, backend 준비·GPU UP 48 s. Tailscale 자동 복귀 | **82.8 s**(연결 거부 26 → 502 20) | — |
| FD6 | VM 통째 유실 → 스냅샷으로 다른 zone 에 복구 | — | 만들기 요청 → 서비스 응답 89 s, 운영 설정으로 145 s(수동 한 단계) | — | DB 15건, 해시가 시작 때 백업과 같음 |

- **FD6 조건**:
  - asia-northeast3-b 에 만들었다. 외부 IP·서비스 계정 없음, 태그 한정 이그레스 거부, Cloud NAT 없음.
  - 접속은 운영 VM 을 거친 ssh 점프(키는 개발 PC 에만 있다)로 했다. 인터넷이 없어 Tailscale·ACME·웹훅은 일어나지 않았다.
  - 스냅샷은 훈련 도중(04:11)에 찍혀서 훈련 backend 설정으로 떴다. 운영 설정으로 다시 띄우는 단계가 필요했다.
  - 진짜 전환(고정 IP 옮기기·DuckDNS·운영 Tailscale 끄기)은 하지 않았다 [계획]. 훈련 VM·디스크·방화벽은 같은 시각에 지우고, 지워진 것을 읽기로 확인했다.
- **FD16a**:
  - 03:02:28 시작 요청이 바로 거절됐다(호스트에 남은 GPU 없음, 2~4차와 같음).
  - 03:02:48 배치 조건(GPU 당 vCPU 4·RAM 24 GB)으로 새 Pod 를 첫 시도에 만들었다.
  - 03:03:51 LOADING → 03:04:06 운영 backend 가 UP 을 봤다(모델 로드 41.6 s).
- **FD16b**: 정지한 Pod 를 곧바로 다시 켜자 이번에는 성공했다. 방금 놓은 GPU 라서로 보인다 [가설].

## 2차 확인 — 고친 것을 운영에서 다시 재현

- **배포** [적용]: main `ce1a685`(PR #18·#20)를 `scripts/deploy_vm.sh` 로 05:32:32~05:33:49 UTC 에 올렸다.
  - backend 를 다시 빌드하고 caddy 를 다시 만들었다(`Memory` 2147483648, `GOMEMLIMIT` 1638MiB).
  - prod H2 백업 `0 5 * * * *`·48개, `management.health.diskspace.enabled=false`, actuator health UP. 컨테이너를 다시 만든 뒤 메타데이터 차단 check ok, 공개 경로 61/61.
  - 05:34 VM 감시(R4) 설치(30 s 타이머, 정상이면 조용함). VM 밖 백업·메타데이터 차단 스크립트도 리포 판으로 다시 install 했다(백업 타이머 마지막 실행 05:20 성공).
  - R7 은 설치할 것이 없다. main 의 `start_pod.sh` 가 Pod 를 켤 때마다 감독 루프를 띄운다.
- **창**: 05:37:39~05:53:08 UTC, 15.5분. 디스코드로 공지했다.
- **알림 받는 곳**: 훈련 backend 의 알림은 팀 채널 대신 수집 서버로 보냈다. `beside_default` 망의 `drill-hook` 컨테이너가 받은 본문을 시각과 함께 출력한다. VM 감시는 운영 웹훅을 써서 2건이 팀 채널로 갔다: 🔁 재시작(05:46:29, 시작 공지에 미리 적음)과 ✅ "backend health 정상 … 재시작 186초 뒤 확인"(05:49:10). 186 s 는 회복 시간이 아니다. 감시는 컨테이너가 시작한 뒤 90 s(`START_GRACE_S`) 동안 검사하지 않는데, 훈련이 backend 를 05:46:30 쯤과 05:47:16 쯤 다시 만들어 첫 검사가 05:49:09 였고, 재시작 시각은 3/3 검사를 시작한 05:46:03 으로 적힌다.
- **추론**: FD17·FD13·FD2 는 훈련 스텁. 05:47:32 부터는 A40 Pod `03rnszh20mywif` 를 썼다. 05:37:26 같은 호스트에서 켜졌고(이번에는 GPU 부족 없음), 리포를 `ce1a685` 로 맞춰 05:47:02 bootstrap. 감독 루프 pid 210, 서비스 pid 218(부모 210).
- **복구 확인**: 운영 H2 해시가 창 전 백업(`4a18b3ba…`)과 같았고, 조회한 뒤에도 같았다. 운영 DB 에 부하·훈련 작업 0건, healthz `real,prod`, 공개 경로 61/61.

| ID | 장애 | 1차 (대응 전) | 2차 (대응 배포 뒤) [실험 결과] |
|---|---|---|---|
| FD17 | 추론 토큰 불일치 | GPU 상태 UP 그대로. 작업마다 실패 웹훅뿐 | 05:38:34.8 스텁을 다른 토큰으로 다시 만듦 → 🔑 05:38:44.6(9.8 s, 스텁 재시작 포함). 05:39:25.3 되돌림 → 🔑 풀림 05:39:29.6(4.3 s) |
| FD13 | healthz 는 UP, 추론만 멈춤(스텁 600 s, backend 시간 초과 300 s) | 알림 없음 | 05:40:41.0 작업 1건 → 🐢 05:42:55.6("2분 14초째 끝나지 않아요 (기준 2분 0초)"). 05:43:31.0 스텁 재시작 → 그 작업 `INFERENCE_UNAVAILABLE`(⚠️ 05:43:31.2), 🟢 05:43:40.6 |
| FD2 | backend SIGSTOP(감시 켬, 점검 표시 없음) | 재시작 없음. 189 s 응답 없음, 걸어 둔 SIGCONT 로만 풀림 | 05:44:36.5 멈춤 → 감시 실패 1/3 05:45:06, 2/3 05:45:37, 3/3 05:46:08 → `docker restart` → 컨테이너 다시 시작 05:46:28.9(`StartedAt`), 🔁 05:46:29. 멈춤 → 재시작 **112 s**. 05:46:30 쯤 훈련 스크립트의 `up stub`(FD13b 의 시간 초과 300 s → 120 s)을 돌렸다. 그 출력은 남기지 않았지만 환경 값이 바뀌어 compose 가 backend 를 다시 만드므로, VM 안 healthz 200(05:46:45.0)은 그 새 컨테이너다. 감시가 다시 시작한 컨테이너가 정상이 되기까지는 재지 못했다. → 정상 약 2분 [계산: 112 s + 2차의 다시 만들기·부팅 14~15 s] |
| R7 | 실제 Pod 에서 추론 서비스 `kill -9` | 감독 루프 없음. 누가 `start_pod.sh` 를 다시 실행할 때까지 꺼짐(F2-real) | 05:48:25.2 kill → `supervisor.log` "exited (status 137, signal 9) after 79 s; restart 1 in 5 s", 05:48:30 새 pid 315 → backend DOWN 05:48:35, LOADING 05:48:51, UP 05:49:06.3. kill 에서 **41 s**, 사람 손 없음. DOWN 이 45 s 유예보다 짧아 DOWN 알림은 없고 🟡·🟢 만 갔다 |
| FD18 | 모델 로드 실패(체크포인트 경로 틀림, 실제 Pod) | LOADING 에 머묾. 로딩 중과 구별 안 됨 | 05:49:53.2 표시 파일 + kill → 감독 루프 "norestart exists - not restarting". 틀린 `BESIDE_ANIMALLIFT_CKPT` 로 띄우자 healthz `modelLoaded:false`, `loadError: "FileNotFoundError: missing checkpoint …"` → 🧩 05:50:24.7("모델을 불러오지 못했어요 (FileNotFoundError)", 🟡 와 함께). 05:51:03.2 바르게 다시 띄움 → 🧩 풀림 05:51:27.9 → UP 05:51:43.1(**40 s**, 1차 52.6 s) |

- **데이터**: 알림 시각은 `window2/window2-hook.log`(수집 서버가 받은 시각), 감시는 `window2/window2-watchdog.log`(저널), Pod 부분 backend 는 `window2/window2-backend.log`, 주입은 `vm/timeline.txt`. Pod 쪽 시각(Pod 시작, kill, `supervisor.log` 줄)은 세션에서 읽은 값이라 원시 파일이 없다.
- **FD2 의 112 s** [계산]: 멈춘 뒤 첫 검사까지 약 25 s, 검사 세 번(30 s 간격, 각 5 s 응답 제한), 3/3 실패에서 컨테이너 다시 시작까지 21 s(`docker restart -t 20` 의 정지 대기). 1차의 189 s 는 외부 탐침이 잰 응답 없는 시간이다. 2차에서 정상까지의 시간은 재지 못했다: VM 안 healthz 200(05:46:45.0)은 훈련 스크립트가 다시 만든 컨테이너의 것이다(위 표).

## 결과에서 나온 대응

자세한 대응은 [RUNBOOK.md](RUNBOOK.md) 에 있다.

- **[적용] (2026-11-20, 운영)**:
  - 컨테이너의 메타데이터 서버 접근 차단. 확인: 컨테이너 000, 호스트 200, DNS 정상. 05:33 배포로 컨테이너를 다시 만든 뒤에도 check ok.
  - 전용 서비스 계정 `beside-backup@` (버킷 만들기 권한만, 키 파일 없음)
  - VM 밖 백업 버킷 `beside-514511-backups`: 매시 20분 타이머. 첫 실행에 DB 백업 7개와 사진·GLB 29개를 올렸다. 버킷에서 받은 백업을 H2 로 열어 작업 15건을 확인했다.
  - 디스크 스냅샷 일정 `beside-daily-snapshot`: 매일 19:00 UTC, 7일 보관.
  - 05:33 배포(`ce1a685`): H2 매시 5분 백업·48개(설정 확인. 이 기록 뒤 같은 날 13:05·14:05·15:05 UTC zip 과 매시 20분 업로드를 확인 — [deploy/README.md](../../deploy/README.md) 적용 기록 1번), Caddy 메모리 상한, 추론 감시(🔑·🐢·🧩, 2차에서 받음).
  - 05:34 VM 감시(R4): health 3번 실패면 재시작(2차 FD2 112 s), 디스크 80 % 알림(재현 안 함).
  - Pod 서비스 감독 루프(R7): main 의 `start_pod.sh` 로 켜면 들어간다(2차 kill → UP 41 s).
  - 15:53 배포(`7230dea`, PR #22): FD16b 끊긴 추론 요청 포기(backend 쪽), FD5 DB 파일 없으면 prod 시작 거부(컨테이너 `STORAGE_DB_CREATEIFMISSING=false`), FD4 서버 디스크 오류는 `INTERNAL_ERROR`, FD9 웹훅 큐. 같은 시각 VM 감시를 🧱 가 든 판으로 다시 설치. 공개 경로 61/61.
- **2026-11-25 확인으로 [계획] 에서 뺀 것**:
  - FD16b·FD5·FD9 고침의 다시 재현: [5차 부하 시험](../perf/load/2026-11-21_vm-5th.md) 7절(2026-11-21)에서 했다 [실험 결과]. Pod 는 main `fe10683` 으로 켜 healthz 가 `instanceId` 를 냈다. 운영 backend 가 아니라 훈련 backend 였고, 알림(🧱 포함)은 팀 채널이 아니라 VM 안 훈련 수집기(`drill-hook`)로 갔다.
    - FD16b: 끊긴 요청이 52 s 에 `INFERENCE_UNAVAILABLE`(`instanceId` 길). 기다린 5건 최대 74.3 s(1차 602 s).
    - FD5: 시작 거부(재시작 10번, 빈 DB 없음), 🧱 44.6 s 뒤. 백업 복구 뒤 healthz 200 까지 14.8 s.
    - FD9: 수집기 `docker pause` 240 s 동안 재시도 간격 5 → 160 s, GPU 메시지 손실 0, 작업 55건 모두 완료.
  - 스냅샷 일정이 만든 스냅샷: READY 5개(`beside-vm-asia-northeast3-a-20261120191411-6ww5dte2` ~ `beside-vm-asia-northeast3-a-20261124191411-l434mv0g`, 매일 19:14 UTC, `gcloud compute snapshots list`). 첫 것은 2026-11-20 19:14 UTC 다.
- **[계획]**:
  - 외부 가동 감시(R3): 알림 채널을 사용자가 콘솔에서 만든 뒤 `deploy/gcp/uptime_check.sh create <channel>`. 2026-11-25 에도 가동 감시는 0개다.
  - FD4 고침의 다시 재현(디스크 가득 참 → `INTERNAL_ERROR`)
  - 5차에서 보지 못한 것: FD16b 의 다른 두 길(120 s 시간 초과, GLB 내려받기 60 s 무응답), FD9 진짜 블랙홀(연결 자체가 안 됨), 운영 VM 감시(R4)가 🧱 를 팀 채널로 보내는 것([RUNBOOK.md](RUNBOOK.md) '남은 일')

## 한계

- 시나리오마다 한 번씩만 했다(2차·5차 다시 재현도 한 번). 시간 값은 한 번 잰 값이다.
- 블록 1 의 추론은 스텁이다. 실제 GPU 장애는 블록 2 와 2차의 R7·FD18 에서만 쟀다. 2차의 🔑·🐢 는 스텁으로 확인했고, 실제 모델이 멈춘 경우의 🐢 와 VM 감시의 디스크·백업 알림은 재현하지 않았다.
- FD13·FD12 는 시간 초과·오프라인 대기를 줄여(60 s·180 s) 돌렸다. 운영 값에서의 시간은 [계산] 이다.
- 시계: VM·Pod·k6 Pod 모두 NTP 이고 서로의 차는 재지 않았다. 같은 기계 안의 시각끼리만 뺐다. 예외로 2차의 R7 41 s·FD18 40 s 는 Pod 의 kill·재시작 시각과 VM backend 의 UP 시각을 뺀 값이라 두 시계의 차가 들어 있다.
- FD8(TLS·DuckDNS)은 운영 인증서와 Let's Encrypt 한도 때문에 재현하지 않았다. FD19·FD20(F3·F4)은 다음 부하 시험으로 미뤘다. FD21(F5)은 큐 상한 뒤에 한다.

## 비용

[계산]: Runpod 가격 × 켜진 시간, GCP 는 공개 가격표.
- A40 1차: 03:02:47~04:28 약 1.4 h ≈ $0.84
- A40 2차: 05:37:26~05:52(정지) 약 15분 ≈ $0.15($0.59/h)
- k6 CPU Pod: 약 1.5 h ≈ $0.24
- 훈련 VM: 약 5분
- 스냅샷: 수동 1개 7.0 GB 저장
- 버킷: 수십 MB
