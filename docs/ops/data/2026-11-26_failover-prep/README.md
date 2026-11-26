# 계획 전환 준비 (2026-11-26) — 대기 VM 맞추기·DRY_RUN

REDUNDANCY 6절 1-5(전환 훈련 D-1·D-3) 전의 준비 기록이다. 운영 VM 은 읽기만 했고 바꾼 것은 대기 VM 뿐이다. 절차는 [RUNBOOK](../../RUNBOOK.md) '대기 서버로 넘기기·되돌리기'.

## 왜

진행 점검(2026-11-26)에서 찾은 문제:
- 대기 VM `beside-standby` 의 리포와 이미지는 스냅샷 시점 `d5f4a32` 이고, 운영은 `a44b3ed` 다. `scripts/failover_vm.sh` 는 커밋이 다르면 0단계에서 멈춘다.
- 리포만 당기면 커밋 검사는 통과한다. 그런데 3단계 `compose up` 은 빌드하지 않는다. 그래서 스냅샷의 옛 backend 이미지가 뜬다. 대기 VM 에 없는 tempo 이미지는 끊긴 동안 받는다.

그래서 `scripts/sync_standby.sh` 를 새로 만들었다. `failover_vm.sh` 0단계에는 대상 이미지 검사를 더했다.

## 시작 전 대기 VM (2026-11-26 13:1x UTC 무렵, 읽기만)

| 항목 | 값 |
|---|---|
| `gcloud` | RUNNING, zone b, 서비스 계정 `beside-backup@`(devstorage.read_write·logging.write·monitoring.write), 임시 외부 IP 있음, 삭제 방지, 마지막 시작 2026-11-25 17:28:23 UTC. 디스크 `beside-standby-boot` 에 스냅샷 일정 없음 |
| 역할·점검 | `/etc/beside-role` = standby, 점검 표시 켬 |
| 리포 | `d5f4a32`, 바뀐 파일 0 |
| 단위 | tailscaled 켬·실행(로그아웃 상태), beside-watchdog.timer·beside-backup.timer 끔, beside-block-metadata 켬 |
| `.env` | `NOTIFY_WEBHOOK_URL`·`GRAFANA_ALERT_WEBHOOK_URL` 비어 있음(값은 보지 않았다) |
| 컨테이너 | 6개 모두 Exited(20시간 전), `beside-backend:local` 20시간 전 빌드, tempo 이미지 없음 |
| 디스크 | 48G 중 18G 사용 |
| 나가는 길 | github.com 200 |

## 한 것

| 시각 (UTC) | 한 것 | 결과 |
|---|---|---|
| 13:16 | `DRY_RUN=1 bash scripts/sync_standby.sh` | 점검 통과, 바꿀 명령 출력 |
| 13:17:10~13:20:21 | `bash scripts/sync_standby.sh` 첫 판([sync-1.log](sync-1.log)) | `d5f4a32` → `a44b3ed` 빨리 감기, 모델 2개 ok, backend 빌드, 이미지 6개 받음(tempo 처음), 시작한 것 없음. 약 3분 11초 |
| 13:21 | `DRY_RUN=1 bash scripts/failover_vm.sh --planned beside-vm beside-standby` | 같은 커밋, 빌드 표시 확인 |
| — | 독립 검토 2갈래(bash 안전성, 문서 대 스크립트) | 운영을 바꾸거나 D-1 을 막는 문제는 없었다. 고친 것은 아래 |
| 13:35:25~13:35:38 | 고친 `sync_standby.sh` 로 다시([sync-2.log](sync-2.log)) | 이미 `a44b3ed`, 빌드 캐시로 13 s, 빌드 표시 다시 씀 |
| 13:37 | 고친 `failover_vm.sh` DRY_RUN([failover-dry-run.log](failover-dry-run.log)) | 커밋·이미지·빌드 표시 통과, **tailnet 로그아웃이라 실제 실행이면 멈춤** |

로그에서 대기 VM 의 임시 외부 IP 는 가렸다. 고정 IP·내부 IP 는 그대로 두었다.

## 검토로 고친 것

- 원격 이미지 목록에 `set -o pipefail` 과 '빈 목록이면 오류' 를 넣었다. 그 전에는 `compose config` 가 실패해도 '빠진 이미지 없음' 으로 읽혔다.
- 빌드 표시를 꼭 있게 했다. 없거나 `pending` 이면 `failover_vm.sh` 가 거절한다.
  - `sync_standby.sh` 는 빨리 감기 전에 표시를 `pending` 으로 쓰고, 확인을 마친 뒤에야 커밋으로 바꾼다.
  - 전환 6단계는 옛 운영 VM 에 그 VM 이 돌리던 커밋을 표시로 남긴다. 그래서 되돌리기(D-3)도 이 검사를 지난다.
- 대상이 tailnet 에 로그인하지 않았으면 멈춘다(`BESIDE_ALLOW_NO_TAILNET=1` 로만 넘어간다). DRY_RUN 은 'would STOP' 을 찍는다.
- 1단계(운영 backend 멈춤)가 실패해도 되돌린다. 그 전에는 점검 표시가 남고 backend 가 멈춘 채 끝날 수 있었다.
- 모델 폴더는 운영 `.env` 에서 읽는다. 전환 때 운영 `.env` 를 복사하기 때문이다.
- `scripts/check_public_routes.sh` 에 제품 이벤트 2개를 더했다(GET 401, 잘못된 묶음 POST 400 — 저장 안 함). 운영 주소에서 63/63 이다(13:1x UTC).
- RUNBOOK 의 표지 작업 B 를 넘기기 **전에** 만들도록 고쳤다. 넘긴 뒤에 만들면 RPO 0 을 보여 주지 못한다.

## 시험

- `scripts/test_sync_standby.sh`(새로), `scripts/test_failover_vm.sh`(넓힘): WSL 에서 통과했고 CI 에 넣었다.
- 일부러 망가뜨린 사본 9개(되돌림 없음, 표시 비교 없음, pipefail 없음, tailnet 무시, 옛 운영에 표시 안 남김, build·pull 안 엮음, 대기 명령을 운영 IP 로, pending 없음, 모델을 대기 `.env` 에서)를 시험이 모두 잡았다.

## 남은 것 [계획]

- 사람: 대기 VM Tailscale 로그인 승인(첫 동기화가 도는 동안 `sudo tailscale up --hostname=beside-standby --accept-dns=false --timeout=20s` 로 승인 주소를 만들었다), 훈련 창 날짜와 공지, Grafana 캡처.
- 훈련 당일: Runpod CPU Pod(drill_client), GPU Pod 를 켤지 결정, D-1·D-3, 결과 문서.
