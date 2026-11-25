# 발표 당일 GPU 절차 리허설 (2026-11-25)

[docs/ops/RUNBOOK.md](../../RUNBOOK.md) '발표 당일 GPU 절차'의 근거 자료다. 같은 창에서 운영 Grafana 의 멘토 지표 5개를 채웠다([deploy/README.md](../../../../deploy/README.md) '모니터링').

## 조건

- 운영 VM: `beside-vm`, main `625c2a5`, real,prod. backend 는 재시작하지 않았다.
- 새 Pod `j0g1iqr40i0gxk`(이름 `beside-demo-rehearsal`). 이 리허설에서 만들고 끝나고 지웠다(`get-pod` 404).
  - A40 SECURE $0.59/h, EU-SE-1(같은 날 조회 재고 LOW).
  - 조건: 볼륨 `z4kgw4v5zi`, `minCudaVersion 12.4`, `minVcpuCountPerGpu 4`, `minRamPerGpu 24`, Secret 토큰.
- 시작 명령은 기존 자동 시작 명령(`beside-gpu7` 등)과 같다. 바꾼 것은 하나다: `checkout -B main origin/main` 대신 **`checkout -B main d6e25d1`**(main 고정).
- 작업: 공개 주소 `https://beside-team.duckdns.org` 로 `scripts/e2e_mock.sh` 를 돌렸다.
  - 사진은 `backend/src/test/resources/photos/dog.jpg` 1장이다.
  - 운영 DB 에 남은 시험 작업은 9건이다.
- 같은 초에 보낸 요청은 스크립트의 `Idempotency-Key: e2e-<초>` 가 같아 한 작업으로 합쳐졌다. 멱등 키가 의도대로 동작한 것이다. 겹친 대기열은 1.2 s 간격으로 보낸 4건으로 만들었다.

## 시간 [실험 결과] (1회)

| 단계 | 시각(UTC) | 생성부터 |
|---|---|---|
| `create-pod` 응답(createdAt) | 05:54:10.345 | 0 |
| VM: DOWN → LOADING | 05:54:54.152 | 43.8 s |
| VM: LOADING → UP (`instanceId` 3926451d004c, `modelVersion` animallift-baseline@5d364cf:latest) | 05:55:39.979 | 89.6 s |
| 첫 작업 COMPLETED (636bc328) | 05:56:24 | — |
| Pod stop·delete | 05:58~05:59 | — |
| VM: UP → DOWN | 05:59:15.518 | — |

- 첫 작업은 클라이언트 e2e 22.1 s 였다.
  - 업로드 749 ms
  - 대기 21.2 s
  - 서버 `queuedMs` 3,170, `processingMs` 17,988, `inferMs` 8,353
- 같은 시험 9건 전체([job-runs.csv](job-runs.csv)):
  - 모두 COMPLETED, `modelVersion` 은 모두 `+rig` 였다.
  - GLB 2,100,924 B, `gpuPeakMB` 5,120
  - `processingMs` 중앙값 11,079 ms. 첫 작업을 빼면 8,574~11,880 ms 다.
  - 겹친 4건의 `totalMs` 는 11,785 → 19,779 → 30,174 → 40,239 ms 였다. 앞 작업이 끝날 때마다 한 칸씩 밀렸다.
- 비용: Pod 가 산 시간은 약 5분이다. A40 $0.59/h 로 약 $0.05 [계산]. 잔액은 조회 시 $181.52 그대로였다(청구 반영 지연).

## Prometheus (06:00 UTC, 지난 10분) [실험 결과]

원본은 [prometheus-0600Z.txt](prometheus-0600Z.txt) 다(처리·대기 시간 세 질의는 같은 시각 기준으로 06:1x UTC 에 덧붙였다).

- 2026-11-25 까지 대시보드의 '생성 시간 평균/p95' 숫자 칸은 METRICS §5.3 과 달리 접수 → 완료(total)를 보였다. 같은 날 이 브랜치에서 처리 시간(processing)으로 고쳤다([beside-ops.json](../../../../deploy/monitoring/grafana/dashboards/beside-ops.json) 패널 4·5).
- `increase()` 는 구간 끝을 외삽하므로 건수가 정수가 아니다.

| 멘토 지표 | 값 |
|---|---|
| 생성 성공률 | 1 (COMPLETED 약 9.4 = 실제 9건) |
| 생성 시간 평균 (`beside_job_processing_seconds`, METRICS §5.3) | 11.3 s (`job-runs.csv` 의 `processingMs` 평균과 같음) |
| 생성 시간 p95 | 28.5 s — 10 s·30 s 버킷 사이를 선형 보간한 값이다. 실제 최대 `processingMs` 는 18.0 s 다 |
| (참고) 접수 → 완료 전체 시간(`beside_job_total_seconds`, 대기 포함) 평균 / p95 | 19.6 s / 53.3 s(보간값, 실제 최대 `totalMs` 40.2 s) |
| (참고) 대기 시간 p95 (`beside_job_queued_seconds`) | 27 s(보간값) |
| 대기열 길이 최대 | 3 (pending 2 + processing 1) |
| 에셋 다운로드 실패율 | 0 (ok 약 13.5 = 실제 13번) |
| API 응답 시간 p95 | `POST /api/v1/jobs` 2.26 s, 조회·에셋·healthz 약 0.024 s |
| (참고) GC 뒤 힙 비율 | 0.065 |

## 파일

| 파일 | 내용 |
|---|---|
| [backend-inference-state.log](backend-inference-state.log) | VM backend 의 추론 서비스 상태 변화 |
| [job-runs.csv](job-runs.csv) | 오늘 `job_runs`(관리 API `runs?format=csv` 에서 2026-11-25 줄만) |
| [prometheus-0600Z.txt](prometheus-0600Z.txt) | 위 PromQL 과 응답 |
