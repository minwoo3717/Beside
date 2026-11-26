# 서버 이중화 — 조사와 적용 범위 결정 (2026-11-20)

- **결론**:
  - API·VM 은 2026-11-26 부터 **연습한 대기 VM** 이다. 그 전에는 **cold standby**(매일 스냅샷 + VM 밖 백업 + 연습한 복구 절차, 2026-11-20 결정)였다.
    - 다른 zone 대기 VM 으로 넘기기(D-1)와 되돌리기(D-3)를 운영에서 한 번씩 했다. 바깥에서 잰 끊김은 약 65 s·63 s 다 [실험 결과] ([훈련 기록](2026-11-26_failover-drills.md)).
    - 데이터는 넘길 때 원본에서 복사한다. 원본이 죽은 경우의 비계획 전환(D-2)과 5분 당겨 오기는 Phase 2 다 [계획].
  - GPU 는 **Pod 안 자동 재시작 + 새 Pod 복구 절차**로 한다.
  - 두 대를 동시에 돌리는 active-active 나 hot standby 는 지금 하지 않는다.
  - 2026-11-25: [6절](#6-다음-단계-계획-2026-11-25-계획)에 다음 단계 계획(warm standby 단계안)을 세움.
  - 2026-11-26: 사용자 결정으로 발표 전 Phase 1 까지 실행한다(멘토링 '이중화는 최대한 실제로'). 1-1 5분 백업 **[적용]**(2026-11-25 17:16~17:21 UTC 배포 창 1), 1-2·1-3 대기 VM `beside-standby` 만듦(2026-11-25 17:22~17:40 UTC, Tailscale 로그인 2026-11-26 15:27 UTC 무렵), 1-4 스크립트 [구현됨], 1-5 전환 훈련 D-1·D-3 **[실험 결과]** (2026-11-26 15:48~16:01 UTC. GPU 부분은 [계획]).
- **근거**: [장애 훈련 결과](2026-11-20_failure-drills.md), [런북](RUNBOOK.md).
- **표기**:
  - [적용] 운영에 켜 둠
  - [구현됨] 코드·시험은 있고 배포 전
  - [실험 결과] 훈련에서 잼. [실험 결과 2차] 대응을 배포한 뒤 2차 확인(2026-11-20 05:37~05:53 UTC)에서 잼
  - [계획] 안 함
- **가격**: 출처와 조회 날짜를 붙였다. 서울 가격을 못 찾은 것은 미국 가격이라고 적었다.

## 1. 지금 구조의 단일 장애점

| 구성 | 위치 | 상태를 가진 것 | 하나뿐인가 |
|---|---|---|---|
| VM `beside-vm` | GCP asia-northeast3-a 한 zone, e2-standard-2 | Docker 볼륨(DB·사진·GLB·인증서), `.env`, Tailscale 노드 | 예 |
| backend | 컨테이너 1개 | H2 파일 DB, JVM 안 작업 큐·추론 슬롯·감시 상태 | 예 |
| 사진·GLB | 같은 볼륨 | 작업 결과 전부 | 예 |
| GPU | Runpod A40 Pod 1개(손으로 켬), 볼륨이 EU-SE-1 에 묶임 | 모델·Tailscale 상태(볼륨) | 예 |
| DNS | DuckDNS A 레코드 1개(고정 IP) | — | 예 |

- **backend 를 두 대 띄울 수 없는 이유** (코드 확인):
  - H2 는 내장 파일 DB(`AUTO_SERVER` 없음)라 두 번째 JVM 이 열지 못한다.
  - 파일이 로컬 디스크에 있다.
  - 작업 배분이 JVM 안 `@Async` 큐라, 남의 작업을 이어받는 길이 없다.
  - 시작 때 `InterruptedJobRecovery` 가 PENDING·PROCESSING 을 모두 실패로 닫는다. 그래서 한 대를 재배포하면 다른 대의 작업이 죽는다.
  - 추론 슬롯이 JVM 마다 있다.

## 2. 조사한 방식

| 방식 | 막는 장애 | RTO / RPO | 월 비용(출처, 날짜) | 이 프로젝트에 |
|---|---|---|---|---|
| 컨테이너 재시작 정책 | 프로세스 죽음 | 약 19 s / 진행 중 작업 [실험 결과 FD1] | $0 | 이미 있음 |
| GCE 자동 재시작·실시간 이전 | 호스트 고장·유지보수 | 3~5.5분(문서), VM 재시작 외부 82.8 s [실험 결과 FD3] | $0 | 이미 켜짐 |
| **VM 밖 백업(GCS)** | 디스크·VM 삭제·실수 | 수십 분 / 백업 간격 | 1달러 미만(서울 Standard $0.023/GB, cloud.google.com/storage/pricing, 2026-11-20) | **적용** |
| **스냅샷 일정** | 디스크·VM·zone 유실 | VM 다시 세우기 145 s [실험 결과 FD6] + 전환 / 하루 | 약 $0.35~1(US 표준 $0.05/GiB, n2ws 2026-08; 서울값 미확인) | **적용** |
| 관리형 인스턴스 그룹(VM 1대, 자동 복구) | VM·앱 멈춤 | 수 분 / 0 | 그룹 자체는 무료(cloud.google.com/instance-groups) | [계획]. 상태 디스크·템플릿 준비가 필요. VM 감시 스크립트(R4)로 대신: [적용] 2026-11-20 05:34, 멈춘 backend 를 112 s 뒤 재시작 [실험 결과 2차 FD2] |
| 다른 zone 대기 VM + IP 옮기기 | zone 유실 | 10~30분 / 백업 간격 | 디스크값 몇 달러 | 절차만 문서화. 스냅샷으로 같은 효과(FD6). 2026-11-26: 대기 VM 만듦, 계획 전환 스크립트 [구현됨], 전환 훈련 D-1·D-3 [실험 결과] (끊김 약 65 s·63 s, [기록](2026-11-26_failover-drills.md)) |
| 지역 영구 디스크(2 zone 동기) | zone 장애 | 수 분 / 약 0 | zonal 의 약 2배(제3자 자료, 미확인) | 하지 않음 |
| active-active(VM 2대 + LB + Cloud SQL + GCS) | 거의 전부 | 초 / 약 0 | 약 $160~290. VM 2대 약 $125, 외부 LB 약 $18.25(us-central1, computingforgeeks 2026-04), Cloud SQL $10~128(bytebase·usage.ai, 2026-11) | **하지 않음**: DB·파일·큐를 다시 짜야 한다 |
| DuckDNS 스크립트 전환 | IP 바뀜 | TTL + 단말 캐시 | $0 | 절차만. 고정 IP 라 드묾 |
| Cloudflare 프록시·LB | 원점 전환 | 약 1~2분 | LB $5~(cloudflare.com/plans) + 자체 도메인 | 하지 않음: DuckDNS 하위 도메인은 못 올림 |
| GPU: Pod 안 서비스 자동 재시작 | 서비스 프로세스 죽음 | 모델 로드 약 31~73 s(지난 기록). `kill -9` → UP 41 s(5 s 대기 포함, Pod·VM 시계 섞음) [실험 결과 2차] | $0 | **적용**(R7, main 의 `start_pod.sh` 로 Pod 를 켤 때마다) |
| GPU: 새 Pod 복구 절차 | Pod 정지·재시작 실패 | 98 s [실험 결과 FD16a] | 쓰는 만큼 | **적용**(런북) |
| GPU: 두 번째 Pod 상시 | Pod 장애 | 초 | A40 $0.59/h → 월 약 $431(runpod.io/pricing, 2026-11-20) | 시연 시간에만 고려 |
| GPU: 다른 데이터센터 대기 볼륨 | 데이터센터 재고 없음 | Pod 만들기 + 모델 로드 | 볼륨 $0.07/GB·월 | [계획]. 아래 3절 |
| GPU: Runpod Serverless 예비 | Pod 를 못 구함 | 콜드 스타트 30 s~2분(Runpod 자료) | 48 GB 급 약 $0.00034/s, 쉴 때 0 | [계획]. 핸들러 감싸기 필요 |
| backend 추론 주소 목록 자동 전환 | GPU 하나 장애 | 감지 5~15 s + 모델 로드 | $0 | [계획]. 다중 GPU 풀(B9)과 함께 |

출처 전체: 조사 기록(2026-11-20).
- GCP: compute 자동 복구·인스턴스 그룹·스냅샷 일정·지역 디스크·유지보수·SLA 문서, 가격표
- Runpod: 가격·서버리스·네트워크 볼륨·0-GPU 문제 문서
- DuckDNS FAQ, Cloudflare 요금·TTL 문서, Caddy `reverse_proxy` 문서
- H2 advanced(내장 모드는 클러스터 불가)

## 3. 결정과 이유

| 계층 | 결정 | 이유 |
|---|---|---|
| API·VM | **cold standby 적용** → 2026-11-26 **연습한 대기 VM**. 스냅샷 매일 [적용], VM 밖 백업 [적용], 복구 절차 [실험 결과 FD6], 자동 재시작 [이미], 감시 스크립트 R4 [적용] (2026-11-20 05:34), 외부 가동 감시 R3 [계획: 채널]. 2026-11-26: 대기 VM `beside-standby` 로 계획 전환·되돌리기를 실측했다(끊김 약 65 s·63 s [실험 결과], [기록](2026-11-26_failover-drills.md)) | 훈련에서 가장 위험했던 것은 "죽음" 이 아니라 "감지가 안 되는 것"(FD4·FD5·FD13·FD17)과 "백업이 같은 디스크"였다. 두 대 운영은 H2·로컬 파일·JVM 큐를 다시 짜야 하고 월 $160~290 이다. 팀 내부·시연 트래픽(T1~T2)과 남은 4주에 맞지 않는다 |
| DB·파일 | **RPO: DB 24 h → 1 h, 파일 24 h** [적용] → 2026-11-25 부터 **DB·파일 약 5~8분** [적용](Phase 1-1, 배포 창 1) | 백업 zip 을 매시 20분에 버킷으로 올리고, 사진·GLB 는 하루 1번 올렸다. 2026-11-25 17:16 UTC 부터는 5분마다 백업하고 3분 뒤 새 zip·사진·GLB 를 올린다(첫 업로드 17:23·17:28 UTC). H2 매시 5분 백업은 2026-11-20 05:33 UTC 배포로 켰다(설정 확인. 그 전에는 매일 04:30 KST zip 이 올라갔다). 버킷 백업으로 복구해 작업 15건을 확인했다 |
| 보안 | 전용 서비스 계정 `beside-backup@`(버킷 만들기만, 키 없음), 컨테이너의 메타데이터 차단 [적용] | 2026-11-13 결정(컨테이너가 클라우드 권한을 얻지 않게)을 지키면서 VM 밖 백업을 하려면 |
| GPU | **Pod 안 자동 재시작(R7) [적용] + 새 Pod 복구 절차** | 실제로 가장 자주 난 GPU 장애는 "멈춘 Pod 가 같은 호스트의 GPU 를 못 받음" 이다(2·3·4차·오늘). 새 Pod 로 98 s 에 복구했다. 다른 데이터센터 대기는 2026-11-20 조회에서 A40 재고가 EU-SE-1 Medium, CA-MTL-1 Low 였고 EU-RO-1·EU-CZ-1·US-KS-2 는 없었다. 2026-11-25 조회(Runpod `get-gpu-type NVIDIA A40`, Pod, 배치 조건 GPU 당 vCPU 4·RAM 24 GB)에서는 A40 Pod 재고가 EU-SE-1·CA-MTL-1 두 곳뿐이고 둘 다 LOW 였다. 볼륨이 데이터센터에 묶여 바로 쓸 수 없다 |
| DNS·TLS | DuckDNS 유지, 전환 절차만 | 고정 IP 라 바뀔 일이 드물다. Cloudflare 는 자체 도메인이 필요하다 |

## 4. 나중 단계 (공개 베타 이상) [계획]

순서대로:
1. 사진·GLB 를 GCS 로, DB 를 PostgreSQL(Cloud SQL 또는 VM)로 옮긴다.
2. 작업 배분을 DB 폴링·임대(lease)로 바꿔 `InterruptedJobRecovery` 가 남의 작업을 죽이지 않게 한다.
3. 그다음 VM 2대 + LB. 트래픽 모델(TRAFFIC_MODEL T2·T3)을 보면 API 계층보다 GPU 대수가 먼저 모자란다.
4. 그래서 GPU 쪽(다중 GPU 풀 B9, Serverless 예비)이 VM 이중화보다 먼저다.

## 5. 확인 방법

- RTO 는 장애 훈련으로 잰다(FD1·FD3·FD6·FD16a). 결과는 [2026-11-20_failure-drills.md](2026-11-20_failure-drills.md).
- R4·R7 은 배포한 뒤 2차 확인에서 다시 쟀다: FD2 멈춤 → 재시작 112 s, Pod 서비스 `kill -9` → UP 41 s [실험 결과 2차].
- RPO 는 표지 작업으로 잰다(FD5).
- 스냅샷 일정이 만든 스냅샷은 2026-11-25 확인했다: READY 5개(`beside-vm-asia-northeast3-a-20261120191411-6ww5dte2` ~ `beside-vm-asia-northeast3-a-20261124191411-l434mv0g`, 매일 19:14 UTC, `gcloud compute snapshots list`). 첫 것은 2026-11-20 19:14 UTC 다. 스냅샷으로 VM 을 다시 세우는 것은 FD6(훈련용 수동 스냅샷)에서만 연습했다.
- 외부 가동 감시 알림은 채널을 만든 뒤 켠다 [계획]. 2026-11-25 에도 가동 감시는 0개다.

## 6. 다음 단계 계획 (2026-11-25) [계획]

- **상태**: 2026-11-25 에는 계획만 세웠다(그날 사용자 결정). 2026-11-26 사용자 결정으로 발표 전 Phase 1 까지 실행한다. 진행: 1-1 [적용], 1-2·1-3 대기 VM 만듦(`deploy/gcp/clone_vm.sh create standby now` → `deploy/standby/clone_init.sh standby`, 스냅샷 `beside-vm-standby-20261125-172254`, Tailscale 로그인 2026-11-26 15:27 UTC 무렵), 1-4 `scripts/failover_vm.sh --planned` [구현됨](가짜 gcloud·ssh 시험), 1-5 D-1·D-3 [실험 결과] (아래 2026-11-26 15:48 줄). Phase 2·3 은 발표 뒤. 2026-11-26 13:17~13:37 UTC: `scripts/sync_standby.sh` 로 대기 VM 을 `a44b3ed`·이미지에 맞추고, `failover_vm.sh` DRY_RUN 점검이 커밋·이미지·빌드 표시를 통과했다. 그때는 Tailscale 로그인 전이라 실제 실행이면 멈췄다. 15:27 UTC 무렵 로그인을 승인했고, 15:29 UTC DRY_RUN 은 tailnet 까지 모든 검사를 통과했다. 대기 VM 에서 GPU Pod 로 닿는지는 Pod 가 꺼져 있어 아직 보지 못했다([준비 기록](data/2026-11-26_failover-prep/README.md)). 2026-11-26 15:48~16:01 UTC: D-1(운영 → 대기)과 D-3(되돌리기)을 운영에서 했다 [실험 결과] ([훈련 기록](2026-11-26_failover-drills.md)).
  - 바깥(Runpod CPU Pod, US-CA-2)에서 1초마다 잰 끊김: D-1 65.0~68.1 s, D-3 63.0~64.1 s. 목표 ≤ 3분 [제안값] 안이다.
  - 공개 경로 63/63. 마지막 업로드 뒤에 만든 표지 작업까지 남았다(계획 전환 RPO 0).
  - D-1 원본 DB 와 D-3 직전 대기 VM DB 를 H2 사본으로 비교해 빠진 행이 없었다(D-3 대상 DB 는 비교하지 않았다).
  - D-3 에서 멈춘 backend 는 정상 종료가 아니라 SIGKILL(137)로 끝났다(아래 '알려진 빈틈').
  - GPU 재고가 없어 GPU 를 켠 부분(넘긴 뒤 작업 COMPLETED, 대기 VM → Pod 8002)은 [계획].
- **알려진 빈틈** (2026-11-26):
  - 대기 VM 의 코드와 이미지는 스냅샷 시점 `d5f4a32`(배포 창 1 직후)였고, 운영 VM 은 `a44b3ed`(배포 창 2)다. `failover_vm.sh` 는 0단계에서 두 VM 의 리포 커밋이 다르면 멈춘다. 리포만 당기면 커밋 검사는 통과하지만, 3단계 `compose up` 은 빌드하지 않아 스냅샷의 옛 backend 이미지가 뜨고, 없는 tempo 이미지는 끊긴 동안 받는다. → 새 `scripts/sync_standby.sh` 가 운영 커밋으로 빨리 감고 모델·이미지를 빌드·받은 뒤(시작은 안 함) `~/.beside-built` 를 남긴다. `failover_vm.sh` 0단계는 대상에 compose 이미지가 다 있는지, 빌드 표시가 같은 커밋인지도 본다(표시가 없거나 `pending` 이면 거절. 6단계가 옛 운영 VM 에 표시를 남겨 되돌리기도 통과) [구현됨](가짜 명령 시험). `scripts/deploy_vm.sh` 의 `BESIDE_DEPLOY_STANDBY=1` 은 만들지 않는다(최신 main 을 당기는 스크립트라 운영 커밋에 맞출 수 없다).
  - 2026-11-26 훈련에서 찾은 것([훈련 기록](2026-11-26_failover-drills.md) '찾은 것'):
    - 전환 2단계는 볼륨의 `backup/`(backend 의 5분 H2 zip)을 복사하지 않는다. 그래서 넘긴 직후 대상의 VM 밖 백업 1~2번이 신선도 검사(20분)에 걸려 실패하고, 감시가 '백업 멈춤' 을 알린다. backend 의 첫 5분 백업이 생기면 풀린다. 고칠 길은 가장 새 zip 도 복사하기다 [계획].
    - 넘길 때 PENDING·PROCESSING 이던 작업은 대상에서 `INTERNAL_ERROR` 가 된다. 0단계에서 대기열이 0 이 아니면 경고하기 [계획].
    - Grafana 알림 상태와 Prometheus·Loki·Tempo 기록은 VM 마다 따로다. 넘기면 대상의 옛 상태가 이어지고, 운영 VM 그래프에는 넘겨 있던 시간이 빈다.
    - `compose stop backend` 에서 backend 가 Docker 기본 10 s 안에 끝나지 않고 SIGKILL(종료 코드 137)로 죽었다(D-3 에서 확인). 원인은 아직 모른다 [가설]. 배포·재시작에도 해당할 수 있다.
  - `standby_pull.sh`(Phase 2 W6)는 없다 [계획]. 대기 VM 의 데이터는 스냅샷 시점 그대로이고, 계획 전환 때 `failover_vm.sh` 가 원본에서 복사한다.
  - 서비스 계정: `clone_vm.sh` 는 계정 없이 만든다(`--no-service-account --no-scopes`). 지금 대기 VM 에는 `beside-backup@`(storage-rw·logging-write·monitoring-write)이 붙어 있다(2026-11-26 `gcloud compute instances describe` 확인. 마지막 시작 2026-11-25 17:28:23 UTC 로 보아 만든 직후 멈춰서 붙인 것으로 보인다). 그래서 넘긴 뒤 VM 밖 백업 계정은 있다. 이 붙이기는 스크립트에 없는 손 단계다. 다시 만들 때는 RUNBOOK 'VM 다시 세우기' 1번의 값으로 붙인다(이미 있는 VM 은 멈춤 → `set-service-account` → 시작).
- **근거**: 2026-11-25 읽기 전용 조사. 원문은 [data/2026-11-25_redundancy-design/](data/2026-11-25_redundancy-design/).
  - [00-constraints.md](data/2026-11-25_redundancy-design/00-constraints.md): backend 를 두 대 띄우거나 대기 서버로 넘기지 못하게 하는 제약 23가지
  - [D1-warm-standby.md](data/2026-11-25_redundancy-design/D1-warm-standby.md): 다른 zone 대기 VM + 계획 전환
  - [D2-active-active.md](data/2026-11-25_redundancy-design/D2-active-active.md): VM 2대 + NLB + Cloud SQL + GCS
  - [D3-gpu-first.md](data/2026-11-25_redundancy-design/D3-gpu-first.md): backend GPU 풀과 시연 시간 Pod 두 대
  - [90-synthesis.md](data/2026-11-25_redundancy-design/90-synthesis.md) B절: 점수표와 추천
- **표기**: 시간·비용의 [계산]·[제안값]·[가설]·[미확인]은 조사 원문 그대로다. 목표 RTO·RPO 는 [제안값]이다. 단가 출처는 각 원문 5절에 있다.

### 6.1 세 설계안 점수

기준마다 1~5점, 30점 만점이다(90-synthesis B-1).

| 기준 | D1 warm standby | D2 active-active | D3 GPU 먼저 |
|---|---|---|---|
| 12/04 전 완료 가능성 | 3 | 1 | 2 |
| 훈련에서 드러난 위험을 줄이는 정도 | 3 | 2 | 4 |
| 발표 증거(측정 가능한 전환) | 5 | 2 | 4 |
| 비용(크레딧 안) | 4 | 3 | 4 |
| 코드 위험(테스트·계약·MVP) | 4 | 1 | 2 |
| 되돌리기 | 5 | 2 | 4 |
| **합계 /30** | **24** | **11** | **20** |

- **D1**: 앱 코드 0줄. 전체(W0~W12)는 약 7인일이고, 핵심(대기 VM + 계획 전환)은 약 2.5일이다. 가장 잦은 GPU 장애는 그대로 남는다.
- **D2**: 약 15인일. 작업 배분·상태 전이·DB 를 바꾸고, PostgreSQL 로 옮긴 뒤 생긴 데이터는 H2 로 돌아가지 않는다.
- **D3**: 실제로 가장 잦았던 GPU 장애(FD16a·R7/F2·FD10·FD17·FD18)를 겨눈다. 다만 backend 핵심 경로 500~900줄(2~4일)이고, 리뷰 브랜치(`fix/review-2026-11-25`, 7a8a56c)와 같은 파일을 고친다.

### 6.2 추천과 사용자 결정

**추천**: D1 을 줄여 단계로 나누고, D3 에서 backend 코드 0줄인 GPU 대비(절차·코드 고정)를 접붙이고, D2 는 발표 뒤 설계로만 둔다.
- 12/04 전에는 운영 앱 코드를 건드리지 않고 "실제로 넘어가는 전환"을 재서 보여 주는 쪽이 남은 작업량과 MVP 원칙에 맞다. GPU 쪽도 같은 이유로 코드 대신 절차와 코드 고정으로 대비한다.
- 4절의 장기 순서(GPU 풀 → 저장소·DB → VM 2대)는 Phase 3 에서 그대로 따른다.
- 3절의 "cold standby" 결정을 "연습한 대기 VM" 으로 바꾸는 일이다. 3절은 Phase 0 을 실행할 때 고친다(0-2). → 2026-11-26 전환 훈련 뒤 머리말과 3절을 고쳤다.

**사용자 결정 (2026-11-25)**:
- 계획만 세운다. 실행은 사용자가 나중에 명령한다.
- 백업 주기 단축(DB 5분, 사진·GLB 매 실행)은 리뷰 브랜치를 VM 에 배포하는 창에 함께 넣는다. backend 재시작이 1번으로 끝난다.
- 발표 당일 GPU 는 Pod 1대를 일찍 켜고 녹화 영상을 준비한다. Phase G 의 G-c(같은 볼륨의 예비 Pod B)는 고르지 않았다.

### 6.3 전후 비교 (목표는 [제안값])

| 항목 | 지금 | Phase 1 뒤 | Phase 2 뒤 |
|---|---|---|---|
| VM·zone 유실 RTO | 새 VM 응답까지 145 s(FD6) + 전환은 연습 안 함 → 사람 포함 20~40분 [가설] | 계획 전환 실측(목표 ≤ 3분). 비계획은 절차만. → 2026-11-26 실측: 끊김 약 65 s·63 s [실험 결과] | 비계획 ≤ 10분 |
| DB RPO | 최대 약 75분([deploy/README.md](../../deploy/README.md) '얼마나 잃는가') | 약 8분 [계산] | 같음. 대기 VM 에 미리 복구돼 있음 |
| 사진·GLB RPO | 최대 24 h | 약 8분 [계산] | 같음 |
| VM·Caddy 장애 감지 | 없음(가동 감시 0개) | R3 메일 3~4분 | + 다른 zone 의 대기 감시 약 90 s |

'지금' 칸은 2026-11-25 기준이다. 2026-11-26: 1-1 적용으로 DB·사진·GLB RPO 는 약 5~8분이다(3절). 전환 RTO 는 계획 전환만 2026-11-26 에 쟀다(끊김 약 65 s·63 s [실험 결과]). 비계획 전환과 R3 는 [계획]이다.

### 6.4 단계별 계획

#### Phase 0 — 감지 공백 닫기, 문서 먼저 (11/25~26 제안)

| 조각 | 바꿀 파일·자원 | 시험·확인 | 시간 |
|---|---|---|---|
| 0-1 R3 외부 가동 감시 | 자원: 메일 알림 채널(사람, 콘솔), `deploy/gcp/uptime_check.sh create <channel>` | `gcloud monitoring uptime list-configs` 1개, `uptime_check.sh status` | 25분 |
| 0-2 문서 먼저(D1 W1) | 이 문서 3절 결정을 "연습한 대기 VM" 으로. RUNBOOK 에 '대기 서버로 넘기기·되돌리기'와 금지 사항(복제본에서 `tailscale logout` 금지, 두 VM 동시 활성 금지). RUNBOOK 'DB 복구'·'VM 다시 세우기' 는 리뷰 브랜치(877f63b)가 고치므로 그 병합 뒤에 | 문서 검토 | 0.3일 |
| 0-3 백업 계정에 버킷 읽기(D1 W3) | `deploy/gcp/backup_bucket.sh`(`BESIDE_BACKUP_READ=1`), `scripts/test_gcp_ops.sh`. 지우기·덮어쓰기는 여전히 안 됨 | `DRY_RUN=1`, `get-iam-policy` 에 `roles/storage.objectViewer` | 0.2일 + IAM 승인(사람) |

- **DoD**: 가동 감시 1개가 켜져 있고, IAM 결합이 확인된다.
- **비용**: $0. 가동 감시는 실행 100만 회까지 무료다(D3 0절, Google 가격 예시).

#### Phase 1 — 백업 주기 단축 + 대기 VM + 계획 전환·되돌리기 실측 (11/26~11/30 제안, BE 약 2.5일, 줄인 판 약 1.8일)

| 조각 | 바꿀 파일·자원 | 시험·확인 | 시간 |
|---|---|---|---|
| 1-1 백업 주기(D1 W2). 리뷰 브랜치 배포 창에 묶음 | `deploy/docker-compose.yml` 이 `STORAGE_BACKUP_CRON`·`STORAGE_BACKUP_KEEP` 을 넘김(2026-11-25 계획 때는 compose 에 없었고 지금은 있다 [적용] 배포 창 1. 기본값은 지금 값 `0 5 * * * *`·48 — `application-prod.properties:33-34`). `deploy/.env.example`. VM `.env` 는 `0 */5 * * * *`·288. 사진·GLB 매 실행은 `beside-backup.service` drop-in `Environment=FORCE_FILES=1`(스크립트 0줄 [가설: 시험 안 함] — main `offsite_backup.sh:41` 이 환경 변수로 읽고, :193 이 "그날 첫 실행" 조건). `beside-backup.timer` 는 `*:20:00` → `*:03/5`. 호스트별 접두사(W2-③)는 Phase 2 | `scripts/test_offsite_backup.sh`(WSL), CI compose config. VM 에서 5분 zip 3개·업로드 3번(`journalctl -u beside-backup`), 리뷰 브랜치의 `DB_STALE_MIN` 검사 통과 | 0.5일 |
| 1-2 대기 VM(D1 W4) | `deploy/gcp/clone_vm.sh create\|status\|ip\|delete standby`(스테이징과 같이 씀): 스냅샷(`latest` 가장 새 것, `now` 지금 찍음) → zone b, e2-standard-2, 외부 IP·서비스 계정·API 범위 없이 첫 부팅(`--no-address --no-service-account --no-scopes`), 부팅 디스크 `beside-standby-boot`, 태그 `beside-standby,beside-web`, 라벨 `role=standby`, 삭제 방지. 임시 외부 IP 는 1-3 뒤 `clone_vm.sh ip standby` | `scripts/test_clone_vm.sh` 가짜 gcloud(CI). 할당량 여유 IN_USE_ADDRESSES 1/4, CPUS 2/12(D1 조사) | 0.5일 |
| 1-3 대기 VM 첫 시작(D1 W5) | `deploy/standby/clone_init.sh standby`(외부 IP 를 붙이기 전에 운영 VM 을 거친 SSH 로. 호스트 이름이 `beside-standby` 가 아니면 거부): role 파일·점검 표시 → 감시·백업 타이머 끔·`last.ok` 지움 → tailscaled 정지·끔·상태 파일 삭제(`logout` 안 함) → `.env` 의 `NOTIFY_WEBHOOK_URL`·`GRAFANA_ALERT_WEBHOOK_URL` 비움 → `compose --profile monitoring stop`·`beside-stub` 제거. 그 뒤 임시 IP(`clone_vm.sh ip standby`)와 Tailscale 다시 켜기·로그인은 이 스크립트에 없다 | `scripts/test_clone_vm.sh` 가짜 sudo·systemctl·docker(CI). VM 에서 `tailscale status` 에 beside-vm·beside-standby 가 따로 보이고 "Duplicate node key" 없음 | 0.5일 + Tailscale 로그인 링크·ACL 확인(사람) |
| 1-4 계획 전환 스크립트(D1 W8 줄인 판) | `scripts/failover_vm.sh --planned <FROM> <TO>`(+`DRY_RUN`, 단계별 UTC 타임라인 파일, 개발 PC 에서 실행). 순서: 점검(두 VM RUNNING, 고정 IP 가 FROM 에, TO 에 backend 없음, 같은 리포 커밋, FROM healthz) → 원본 점검 표시·`stop backend` → 개발 PC 를 거친 SSH 로 H2 DB·`uploads`·`results`·`preprocessed`·`events.jsonl` 과 `deploy/.env` 를 대상에 복사(버킷을 거치지 않음) → 대상 `compose --profile monitoring up -d`·healthz → 원본 `delete-access-config --access-config-name=external-nat` → 대상의 임시 IP 떼기 → 대상에 `beside-ip` 붙이기 → 공개 healthz·`scripts/check_public_routes.sh` → 역할·감시·백업 타이머·스냅샷 일정 넘기기. 복사·대상 기동·IP·공개 healthz 가 실패하면 원본에 IP 를 다시 붙이는 되돌림 포함. `scripts/deploy_vm.sh` 의 `BESIDE_DEPLOY_STANDBY=1`(build + `up --no-start`)은 만들지 않았고, 2026-11-26 `scripts/sync_standby.sh`(운영 커밋으로 빨리 감기·모델·build·pull, 시작 안 함, `~/.beside-built`)로 대신했다. 같은 날 0단계에 대상 이미지 검사(compose 이미지가 다 있음, 빌드 표시가 같은 커밋)를 더했다. SSH 는 `HostKeyAlias` 를 따로 | `scripts/test_failover_vm.sh`·`scripts/test_sync_standby.sh`(가짜 gcloud·ssh, CI), DRY_RUN 출력 검토 | 1일(수동 절차 + 타임라인 기록만이면 0.3일) |
| 1-5 훈련 D-1·D-3 | D-1 계획 전환 a→b, D-3 되돌리기 b→a. 결과 문서 `docs/ops/2026-1x-xx_failover-drills.md` 와 데이터. 절차는 RUNBOOK '대기 서버로 넘기기·되돌리기'(2026-11-26), 결과가 나오면 [계획] → [실험 결과]. → 2026-11-26 했다: [2026-11-26_failover-drills.md](2026-11-26_failover-drills.md) [실험 결과] | 아래 DoD | 0.5일(창 1시간, 공지는 사람) |

- **DoD** (D-1·D-3 각각):
  - GCP 밖에서 `scripts/fault/drill_client.py`(1 s healthz + 20 s 마다 작업)로 잰 외부 끊김 초
  - 스크립트 시작부터 공개 첫 200 까지의 시간
  - 공개 경로 `scripts/check_public_routes.sh` 전부 통과(2026-11-26 부터 63개. `deploy/test_caddy_routes.sh` 는 CI 의 로컬 Caddy 시험이다)
  - 표지 작업 A(마지막 백업 전)·B(뒤)가 모두 남음(RPO 0)
  - Tailscale 노드가 둘로 따로 보이고 중복 키 경고가 없음
  - 진행 중 작업이 `INTERNAL_ERROR` → retry 로 성공. Pod 를 켰다면 전환 뒤 1건 COMPLETED
  - 목표 ≤ 3분 [제안값]. 못 맞춰도 잰 값을 [실험 결과]로 적는다.
- **비용** [계산]: 대기 VM 을 훈련 날만 켜면 약 $1~3 + 디스크 약 $0.22/일(e2-standard-2 $0.086/h, pd-balanced $0.13/GB·월 — gcloud-compute.com 제3자, 공식 서울값 [미확인]). 12/04 까지 상시로 켜면 약 $19. 버킷 추가분은 $1 미만.

#### Phase G — 발표 GPU 대비 (11/26~12/04 제안, backend 코드 0줄, D3 에서 접붙임)

| 조각 | 바꿀 파일·자원 | 시험·확인 | 시간·비용 |
|---|---|---|---|
| G-a 당일 절차와 리허설 1회 | Pod 1대를 일찍 켜고, 실패하면 같은 볼륨으로 새 Pod, 대체 녹화 영상 준비(사용자 결정). 당일 절차는 [RUNBOOK '발표 당일 GPU 절차'](RUNBOOK.md) 에 적었다(2026-11-25). 리허설 1회 **[실험 결과]**: 새 Pod 생성 → VM 의 UP 89.6 s, 첫 작업 e2e 22.1 s, 9건 모두 COMPLETED([기록](data/2026-11-25_demo-gpu-rehearsal/README.md)). 실패 때 새 Pod 를 다시 세우는 시간은 재지 않았다 [계획] | 리허설에서 Pod 기동 → healthz UP 시간, 실패 때 새 Pod 를 세우는 시간(FD16a 98 s 와 비교) | 2시간 + 리허설 안. 리허설은 Pod 약 5분, 약 $0.05 [계산] |
| G-b 시연 Pod 코드 고정 | Runpod Pod 시작 명령(리포 밖, RUNBOOK 'GPU Pod 다시 세우기' 2번)의 `checkout -B main origin/main` 을 태그(예: `demo-1204`)로. 리뷰 브랜치 병합(7c52918 이 리깅 도구 확인을 바꿈)부터 Pod 동작이 바뀌기 때문 | 시작 뒤 `/workspace/beside-logs/autostart-git.log` 의 커밋 | 30분(시작 명령 수정은 사람) |
| G-c 같은 볼륨 예비 Pod B | **고르지 않음**(2026-11-25 사용자 결정). 참고: `start_pod.sh:49·53` 의 `TS_DIR`·`LOG_DIR` 을 바꿀 수 있게 하고 시연 시간에만 Pod B 를 켜는 안 | — | 0.5일 + $0.59/h × 4 h ≈ $2.4 [계산] |

- **DoD**: 리허설에서 Pod 기동부터 짧은 작업 1건 COMPLETED 까지 잰 값, 대체 녹화 영상 1개.
- **비용**: 위 표. A40 Pod $0.59/h(2026-11-25 `get-gpu-type`). 재고는 EU-SE-1·CA-MTL-1 모두 LOW 라 리허설에서 실제로 Pod 를 켜 본다.

#### Phase 2 — warm 대기 + 비계획 전환 (12/05~12/12 제안, 발표 뒤)

| 조각 | 바꿀 파일·자원 | 시험·확인 | 시간 |
|---|---|---|---|
| W6 당겨 오기 | 새 `deploy/standby/standby_pull.sh` + 5분 타이머. role 파일이 standby 가 아니거나 backend 가 돌고 있으면 거부(활성 DB 보호) | 가짜 gcloud·docker 로 WSL 시험(거부 조건 포함). VM 에서 3번 돌고 lag 표시 | 1일 |
| W2-③ 호스트별 접두사 | DB 객체를 `db/<hostname>/` 로 나누고 RUNBOOK 'DB 복구' 갱신 | `scripts/test_offsite_backup.sh` | 0.3일 |
| 비계획 전환 | `failover_vm.sh --unplanned`: 원본 `stop --async` + `delete-access-config` 에 시간 제한. 떼지 못하면 DuckDNS 안내를 출력하고 멈춤 | `scripts/test_failover_vm.sh` | 0.5일 |
| (선택) W7 대기 감시 | 새 `deploy/standby/standby_watch.sh` 30 s, Discord 알림 | WSL 시험, 점검 창에서 운영 caddy 60 s 멈춤 | 0.5일 |
| 훈련 D-2·D-3′ | D-2: 운영 VM 에서 `echo o > /proc/sysrq-trigger`. D-3′: 옛 운영 VM 을 대기로 돌려 되돌리기 | 아래 DoD | 0.5일 |

- **DoD**: `last.pull` 기준 lag ≤ 10분이 24시간 유지됨(그림). D-2 에서 첫 감지 시각(R3 메일 / 대기 감시), 장애부터 공개 200 까지 ≤ 10분 [제안값], 잃은 표지는 마지막 업로드 뒤의 것뿐.
- **비용** [계산]: 대기 VM 상시 약 $2.4/일, 7일에 약 $17. 12/21 정리 목록에 `beside-standby` 를 넣는다(2026-11-26: [deploy/README.md](../../deploy/README.md) '비용과 끝내기' 에 넣었다).

#### Phase 3 — 프로젝트가 계속되면 (4절 순서: GPU 먼저)

| 조각 | 바꿀 것 | 시험·확인 | 시간·비용 |
|---|---|---|---|
| G1 추론 주소 목록 | D3 P2 를 "G1만"으로 줄인 것(약 300줄), 리뷰 브랜치(7a8a56c) 병합 위에서. 주소마다 감시와 `restartWatch`, 시도마다 주소 고정(GLB 받기·`DELETE /artifacts` 를 같은 주소로), 진행 중 작업은 "다른 주소가 UP 이고 45 s 넘게 DOWN 이면 포기" | D3 4절 시험(스텁 서버 두 개), `.\gradlew.bat test` | [미확인](D3 는 P2 전체를 2~4일로 봄) |
| 6차 부하 시험 | Pod 두 대로 μ₂ 실측 → `N_eff = 19 × UP 대수` 검증. R 은 대수와 거의 무관하게 165~167 s 이고, μ₂ 를 선형으로 놓은 것은 [가설]. 그다음 G2 슬롯 풀 | D3 P6(주소마다 G0 → G3 풀 과부하) | 3시간, Pod 두 대 약 $3.5 + k6 약 $0.5 [계산, D3 5절] |
| D2-1 | 조건부 상태 전이 + 끝내기 펜싱. 그 뒤 D2-2~6, 인프라 D2-7~10 은 1~2일 시연 창에서만 세웠다 지운다 | 잠금을 나눈 `JobTransitions` 두 개로 start↔cancel 500번 경합, 공유 `jdbc:h2:mem` 두 컨텍스트 시험 | D2-1 1일 [가설], D2 전체 약 15인일 [가설] |
| 그 뒤 | 자동 전환(막기 단계를 확실히 한 뒤), DuckDNS 전환 연습(D-4), 지역 디스크 평가 | — | — |

- **DoD·비용**: 조각을 시작할 때 정한다 [계획].

### 6.5 2·3위 안에서 접붙인 것

- **D3 에서**:
  - Runpod 사실(같은 데이터센터 볼륨에 여러 Pod 연결 허용, 동시 쓰기 위험)과 상태 폴더 분리. RUNBOOK 'GPU Pod 다시 세우기' 3번에 적었다.
  - Serverless 예비 주소를 healthz 로 깨우지 않는다. 깨우면 $1.22/h 가 상시로 나간다.
  - `N_eff` 규칙과 "R 은 GPU 대수와 거의 무관" 을 멘토 '확장 기준' 답으로 쓴다.
  - 45 s 포기 규칙(G1 설계).
- **D2 에서**:
  - 새 Spring 프로파일을 만들지 않는다. healthz 의 `profile` 은 활성 프로파일을 그대로 내므로 대기 VM 도 `real,prod` 로 띄운다.
  - D2-1 시험 설계를 Phase 3 첫 코드로.
  - Caddy `:8090` readiness 헬스 체크, gcsfuse 메타데이터 캐시 TTL 0, Cloud SQL 비공개 IP 는 메모로 남긴다.
- **D1 교정**:
  - 제약 문서의 "복제본에서 `tailscale logout`" 제안을 철회한다. 같은 노드 키를 만료시켜 운영 VM 까지 끊을 수 있다 [가설].
  - 사진·GLB 매 실행은 스크립트 수정 대신 systemd drop-in 으로 한다(1-1).

### 6.6 하지 않을 것

| 하지 않을 것 | 이유 |
|---|---|
| D2 전체(active-active, Cloud SQL, gcsfuse, NLB)를 12/04 전에 | 약 15인일. 오류 문구가 바뀌고, PostgreSQL 로 옮긴 데이터는 되돌릴 수 없다. FD1~3·FD6 은 지금 장치(재시작 정책·R4·GCE 자동 재시작·스냅샷 복구)로 17.8~145 s 에 회복했다 [실험 결과] |
| D3 P2(backend GPU 풀 코드)를 12/04 전에 | 시연 9일 전 핵심 경로 500~900줄이고, 리뷰 브랜치(7a8a56c)와 같은 파일이다 |
| 자동 전환 | 막기 단계 없이 넘어가면 두 VM 이 함께 활성일 수 있다. R3 는 DNS·TLS 장애에도 울리는데, 그건 전환으로 풀리지 않는다(D1 7절) |
| 지역 영구 디스크, Async PD | 부팅 디스크를 옮기는 동안 중단이 생긴다. Async PD 는 다른 지역이라 고정 IP 를 옮길 수 없다(`create_vm.sh:36·70`) |
| 대기 VM 에서 backend 상시 실행 | 예약 작업 중복, GPU 알림 2번, 백업 줄기 섞임([제약](data/2026-11-25_redundancy-design/00-constraints.md) 10·11·19) |
| 복제본에서 `tailscale logout` | 위 D1 교정 |
| Serverless 예비 + healthz 감시 | 일꾼이 계속 깨어 있어 $1.22/h 로 Pod 보다 비싸다(D3 `get-gpu-type`) |
| 같은 Pod 안 슬롯 2개 | 처리량 1.19배뿐이고([2026-11-20_queue-cap.md](../perf/load/2026-11-20_queue-cap.md)) 느린 연결 문제가 있다 |
| backend 두 대가 GPU 하나 공유 | Python `MODEL_LOCK` 대기가 120 s 시간 초과 안에 들어간다(제약 15) |
| e2-small 대기 VM | 전환 때 크기를 바꾸려면 정지·시작이 필요해 RTO 가 약 90 s 늘어난다. 크레딧은 충분하다 |
| 큐 상한을 운영에서 켜기 | `QUEUE_FULL` 을 아는 Unity 새 빌드가 기기에 깔리기 전이다(docs/api/CHANGELOG.md v1.1) |
| 12/02 이후 운영을 건드리는 훈련 | 발표 직전 운영 서버 위험 |
