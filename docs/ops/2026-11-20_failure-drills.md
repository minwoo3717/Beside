# 2026-11-20 장애 훈련 — 실제 배포에서 재현한 장애 18가지

- **무엇을**: 운영 VM(GCP e2-standard-2, 서울)과 Runpod A40 Pod 에서 장애를 일부러 일으키고, 감지·복구 시간과 잃은 작업·데이터를 쟀다.
- **계획**: `C:\Users\minwo\.claude\plans\gcp-precious-unicorn.md` 첫 부분. 시나리오 ID 는 FD 를 쓴다. 계약 회의 결정 D1~D11 과 다른 번호다.
- **대응·이중화**: [RUNBOOK.md](RUNBOOK.md)(시나리오별 대응), [REDUNDANCY.md](REDUNDANCY.md)(이중화 조사·결정).
- **표기**: [실험 결과] 원시 데이터가 아래 폴더에 있다. [계산] 그 데이터로 낸 값. [가설] 확인하지 않은 설명. [계획] 하지 않음.

## 요약

- **운영 중단**: 03:11~04:28 UTC(12:11~13:28 KST), 77분.
  - 디스코드로 시작·종료를 공지했다.
  - 운영 H2 해시는 백업 때와 같았다(`8109f8cb…`). 공개 경로 61/61, healthz `real,prod` 로 복구했다.
- **감지가 안 되는 장애가 가장 위험했다** [실험 결과].
  - 아래 장애는 모두 공개 healthz 가 200 이었고, GPU 상태 알림도 없었다.
    - FD13 추론만 멈춤
    - FD17 토큰 불일치
    - FD4 디스크 가득 참
    - FD5 DB 파일 유실: 빈 DB 를 조용히 새로 만들었다
    - FD15 GPU 메모리 부족
  - FD18 모델 로드 실패는 "로딩 중"과 구별되지 않았다.
- **오래 걸린 복구**:
  - FD16b(부하 중 Pod 정지): GPU 는 약 3분 만에 다시 UP 이었다. 그런데 끊긴 Pod 로 보낸 요청 하나가 600 s 시간 초과까지 하나뿐인 슬롯을 잡았다. 그래서 대기 작업 최대 602 s 로, 앱의 600 s 를 넘었다 [실험 결과].
  - FD2(백엔드 멈춤): 189 s 동안 공개 경로가 응답하지 않았다. 아무도 재시작하지 않았고, 걸어 둔 SIGCONT 로만 풀렸다.
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
- `vm/timeline.txt`: 주입·관찰 시각
- `vm/block2-backend.log`: 블록 2 backend 로그. 토큰·웹훅 없음.
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

## 결과에서 나온 대응

자세한 대응은 [RUNBOOK.md](RUNBOOK.md) 에 있다.

- **적용함(2026-11-20, 운영)**:
  - 컨테이너의 메타데이터 서버 접근 차단. 확인: 컨테이너 000, 호스트 200, DNS 정상.
  - 전용 서비스 계정 `beside-backup@` (버킷 만들기 권한만, 키 파일 없음)
  - VM 밖 백업 버킷 `beside-514511-backups`: 매시 20분 타이머. 첫 실행에 DB 백업 7개와 사진·GLB 29개를 올렸다. 버킷에서 받은 백업을 H2 로 열어 작업 15건을 확인했다.
  - 디스크 스냅샷 일정 `beside-daily-snapshot`: 매일 19:00 UTC, 7일 보관.
- **코드가 준비됨** (PR, 병합·배포 전):
  - 추론 감시: 🔑 토큰, 🐢 멈춤, 🧩 모델 로드 실패 알림
  - VM 감시 스크립트: actuator health 3번 실패면 재시작, 디스크 알림
  - Caddy 메모리 상한
  - H2 매시 백업·48개 보관
  - Pod 서비스 감독 루프
- **[계획]**:
  - 외부 가동 감시(R3): 알림 채널을 사용자가 콘솔에서 만든 뒤
  - 끊긴 Pod 로 보낸 요청의 빠른 포기(FD16b)
  - DB 파일이 없을 때 시작 거부(FD5)
  - 디스크 가득 참의 오류 코드(FD4)
  - 웹훅 알림 밀림(FD9)
  - 고친 뒤 FD2·FD13·FD17·FD18 다시 재현

## 한계

- 시나리오마다 한 번씩만 했다. 시간 값은 한 번 잰 값이다.
- 블록 1 의 추론은 스텁이다. 실제 GPU 장애는 블록 2 에서만 쟀다.
- FD13·FD12 는 시간 초과·오프라인 대기를 줄여(60 s·180 s) 돌렸다. 운영 값에서의 시간은 [계산] 이다.
- 시계: VM·Pod·k6 Pod 모두 NTP 이고 서로의 차는 재지 않았다. 같은 기계 안의 시각끼리만 뺐다.
- FD8(TLS·DuckDNS)은 운영 인증서와 Let's Encrypt 한도 때문에 재현하지 않았다. FD19·FD20(F3·F4)은 다음 부하 시험으로 미뤘다. FD21(F5)은 큐 상한 뒤에 한다.

## 비용

[계산]: Runpod 가격 × 켜진 시간, GCP 는 공개 가격표.
- A40: 03:02:47~04:28 약 1.4 h ≈ $0.84
- k6 CPU Pod: 약 1.5 h ≈ $0.24
- 훈련 VM: 약 5분
- 스냅샷: 수동 1개 7.0 GB 저장
- 버킷: 수십 MB
