# 멘토링 피드백 대응 (2026-11-25)

작성 2026-11-25, 브랜치 `docs/final-report-2026-11-25`(기준 main `d6e25d1`).

2026-10-20 멘토링 피드백과 교수님 피드백에 무엇으로 답할 수 있는지 한 문서에 모았다. 새로 잰 값은 없다. 모든 수치는 원문 문서에서 옮겼고, 줄마다 원문 링크와 표기를 붙였다.

표기(루트 CLAUDE.md):
- **[구현됨]** 코드와 테스트가 리포에 있다.
- **[실험 결과]** 측정값과 조건이 `docs/` 에 있다.
- **[계산]** 측정값으로 계산한 값. **[제안값]** 정하지 않은 기준. **[가정]** 우리가 정한 비율. **[가설]** 확인하지 않은 추정.
- **[적용]** 운영에 켜 둠([failure-drills](../ops/2026-11-20_failure-drills.md) 와 같은 뜻).
- **[파생]** [가정]과 공식으로 계산한 값([TRAFFIC_MODEL](../perf/load/TRAFFIC_MODEL.md) 과 같은 뜻).
- **[계획]** 아직 하지 않았다.

## 1. 요구와 답이 되는 기록

요구 원문은 팀 멘토링 보고서(리포 밖)에 있다. 리포에는 [TRAFFIC_MODEL.md](../perf/load/TRAFFIC_MODEL.md) 머리말, [METRICS.md](../METRICS.md) §5 머리말, [CHECKLIST.md](../CHECKLIST.md) '부하 시험' 절이 같은 요구를 옮겨 두었다.

| # | 요구 | 답이 되는 기록 | 빈 곳 (2026-11-25) |
|---|---|---|---|
| R1 | 지표 5개(생성 성공률, 생성 시간 평균/p95, 대기열 길이, API 응답 시간, 에셋 다운로드 실패율)를 모으고 대시보드로 운영 | 서버 지표와 PromQL **[구현됨]**: [METRICS §5.1~5.3](../METRICS.md), backend 테스트(§5.3 끝 '확인 범위'). 대시보드 정의 'Beside 운영' **[구현됨]**: [beside-ops.json](../../deploy/monitoring/grafana/dashboards/beside-ops.json). 부하 시험마다 VM Prometheus 구간 CSV 18~19개 **[실험 결과]**(예: [g3cap19/prometheus/](../perf/load/data/2026-11-21_1126-g3cap19/prometheus/)). 운영 `/admin` 의 대기열·GPU·디스크 카드와 모델 버전별 성공률·시간 p50/p95 **[구현됨]** | Grafana 는 2026-11-25 운영 VM 에 띄웠다(4절) [적용]. 화면 확인·발표용 캡처는 [계획]. `/admin` 에는 API 응답 시간·에셋 다운로드 실패율이 없다(Prometheus 로만 본다). 앱 `metrics.csv` 측정 0건 [계획] |
| R2 | 쏘카 수준 목표 트래픽을 정하고 k6/Locust 로 단계형 부하 시험 | 목표 T1~T3 **[가정·파생]**: [TRAFFIC_MODEL 1~3절](../perf/load/TRAFFIC_MODEL.md). k6 스크립트 **[구현됨]**: [scripts/load/](../../scripts/load/). 1차 A0~A4 **[실험 결과]**([vm-1st](../perf/load/2026-11-18_vm-1st.md) 요약): A1 25 → 9,000명(185 요청/s) 5xx·연결 오류 0, A2 폴링 300 요청/s 통과·900 유지·1,200 붕괴, A3 0.8 MB 20/s 까지 통과, A4 GLB 10/s 통과 | A1 은 drop 12 로 통과 단계가 없다. 그래서 "API 계층이 T3 를 통과했다"고 쓰지 않는다([TRAFFIC_MODEL 10절](../perf/load/TRAFFIC_MODEL.md)). A1 재측정·한국 리전 부하 발생기·A3 20 MB·A5 스파이크·A6 소크 [계획] |
| R3 | 큐 처리량과 워커 확장 기준을 수치로 | 2절 | 증설 기준은 [제안값]이고 검증하지 않았다. 실제 증설 0회 |
| R4 | 장애 시나리오 5개 | 3절 | F3·F4·F5 [계획]. FD4 다시 재현·FD8 재현 없음 |
| R5 | (교수님) 부하·장애 시험도 배포 환경에서 | 1~5차 부하 시험은 모두 GCP 배포 VM(e2-standard-2, 서울)에서 했다. 운영 backend 를 내리고 같은 VM 에 부하 backend(`real,prod` 또는 `mock,prod`, 시험 볼륨)를 띄웠다. 2~5차는 실제 Runpod A40 Pod 를 불렀다 **[실험 결과]**. 장애 훈련 18가지도 같은 VM 위의 훈련 backend·실제 Pod 와 VM 자체(FD3 재시작, FD6 다른 zone 으로 스냅샷 복구)에서 했다 **[실험 결과]**([failure-drills](../ops/2026-11-20_failure-drills.md)) | 운영 backend·운영 데이터 자체에 건 부하는 없다. 고정 IP 를 옮기는 진짜 VM 전환은 연습하지 않았다. 외부 가동 감시(R3)는 0개다(2026-11-25 확인). 시연용 녹화는 없다 |

## 2. 큐 처리량과 확장 기준

조건: A40 1대, 서울 VM ↔ 스웨덴 Pod(Tailscale), 같은 강아지 사진 1장 반복, 실행마다 1회다.

| 항목 | 값 | 표기 | 원문 |
|---|---|---|---|
| A40 1대·슬롯 1개(c=1) 처리량, 4차 `g3c1` | S_busy 8.67 s → **415건/h** | [실험 결과·계산] | [vm-4th 4절](../perf/load/2026-11-20_vm-4th.md) |
| 같은 값, 5차 `g3cap0`(털 맵이 든 GLB) | S_busy 8.81 s → **409건/h** | [실험 결과·계산] | [vm-5th 3절](../perf/load/2026-11-21_vm-5th.md) |
| 같은 Pod 의 슬롯 2개(c=2), 4차 `g2c2t` | 센 하한 **495건/h**(c=1 의 1.19배 이상). 넘치지 않아 위쪽은 못 봤다(495~689건/h) | [계산] (원문 4절 '처리 용량 [계산]') | [vm-4th 4절](../perf/load/2026-11-20_vm-4th.md) |
| 큐 상한(안건 #21) | N 19, `Retry-After` 165 s. 규칙: 목표 최악 대기 W=180 s, N = ⌊W/S⌋ − 1 | 2026-11-21 결정, `api-v1.1` | [queue-cap 9절](../perf/load/2026-11-20_queue-cap.md) |
| 5차 다시 계산 | N 19 그대로, R 167.4 → 167 s. 사용자가 165 s 유지로 정함 | [계산] | [vm-5th 4절](../perf/load/2026-11-21_vm-5th.md) |
| 상한 19 실행 `g3cap19`(부하 backend, 40분) | 생성 요청 337건 중 51건(15.1 %) 503 `QUEUE_FULL`. 받아들인 286건 모두 COMPLETED, 최대 대기 176.3 s. 합격 기준 1~5 통과. 두 실행의 S_busy 비 0.9999(상한이 처리량을 깎지 않음) | [실험 결과·계산] | [vm-5th 5절](../perf/load/2026-11-21_vm-5th.md) |
| 상한 없는 실행 `g3cap0`(같은 요청 λ) | 383건 중 132건(34.5 %)이 600 s 를 기다리다 끝남. 사용자가 떠난 뒤 서버가 끝낸 작업 100건 | [실험 결과·계산] | [vm-5th 6절](../perf/load/2026-11-21_vm-5th.md) |
| 등급별 필요 GPU(ρ ≤ 0.7), S 8.67 / 8.81 s | T1 1대(ρ 0.024), T2 1대(ρ 0.29), **T3 13대**(ρ 0.67~0.68), T3 스파이크 38대 | [계산] | [TRAFFIC_MODEL 5.1절](../perf/load/TRAFFIC_MODEL.md) |
| 슬롯 2개일 때 T3 | 11대(센 하한 495건/h 기준) | [계산] | [vm-4th 9절](../perf/load/2026-11-20_vm-4th.md) |

**증설·감축 기준** [제안값] ([TRAFFIC_MODEL 4절](../perf/load/TRAFFIC_MODEL.md)):
- 늘린다: 추정 이용률 λ̂·T̂/c > 0.7 이 10분 이어지거나, p95 `queuedMs` > 60 s.
- 줄인다: 0.4 미만이 30분 이어질 때.
- λ̂ 는 최근 10분의 생성 요청률, T̂ 는 최근 `processingMs` 중앙값이다. p95 `queuedMs` 는 METRICS §5.3 의 `beside_job_queued_seconds_bucket` 으로 본다.
- c=1 이면 ρ̂ 0.7 은 약 286건/h 다 [계산: 0.7 × 3,600 / 8.81].
- 주의 [계산]: T̂ 로 `processingMs` 중앙값(5차 8.26 s)을 쓰면 슬롯 넘김(약 0.56 s)이 빠져 S_busy(8.81 s)보다 약 6 % 짧다. 그래서 ρ̂ 를 조금 낮게 본다([vm-5th 3절](../perf/load/2026-11-21_vm-5th.md)).
- 이 기준으로 알림을 보내거나 GPU 를 늘리는 코드는 없다(backend 와 `deploy/monitoring/prometheus.yml` 에 알림 규칙 없음, 2026-11-25 확인). 기준을 부하 시험으로 검증하지도 않았다.

**실제 증설은 0회다.** backend 는 추론 주소 하나(`inference.base-url`)만 받는다. 1~5차와 장애 훈련에서 동시에 쓴 GPU 는 언제나 A40 1대였다(3·4차의 c=2 는 같은 A40 의 슬롯 2개). GPU 를 여러 대 둘 때 큐 상한을 `N_eff = 19 × UP 대수` 로 늘리는 규칙은 [가설]이다. Pod 두 대로 재는 6차 부하 시험은 [계획]이다([REDUNDANCY.md](../ops/REDUNDANCY.md)).

발표 문구: "A40 1대는 시간당 약 410건을 처리한다(측정). T3 에는 13대가 필요하다(계산). 우리는 1대라서 넘치는 요청을 큐 상한으로 바로 거절하고, 5차 부하 시험에서 그 동작을 확인했다(측정). 운영 서버에서는 상한을 꺼 두었다." 운영에서 켜는 것은 `QUEUE_FULL` 을 아는 Unity 새 빌드를 기기에 깐 뒤다 [계획].

## 3. 장애 시나리오 5개와 실제로 한 시험

번호: F1~F5 는 [TRAFFIC_MODEL 9절](../perf/load/TRAFFIC_MODEL.md)의 멘토 5종이다. FD 는 [장애 훈련](../ops/2026-11-20_failure-drills.md) 번호다(FD1~FD18 중 FD8 은 재현하지 않았고, FD16 은 a·b 두 가지). F3·F4·F5 는 훈련에서 FD19·FD20·FD21 로 미뤘다. 알림 기호는 [RUNBOOK.md](../ops/RUNBOOK.md)를 따른다.

| 멘토 시나리오 | 실제로 한 시험 | 감지 | 자동 복구 | 잰 시간·결과 | 표기·원문 |
|---|---|---|---|---|---|
| **F1 생성 실패·타임아웃** | F1a: 실제 Pod 를 스텁으로, 실패율 0.2(3차) | 작업 `INFERENCE_FAILED` | 없음. 사용자(k6)가 retry | 47시도 중 8번 실패, 40건 중 39건 마지막에 COMPLETED, 5xx 0 → 합격 | [실험 결과] [vm-3rd 7절](../perf/load/2026-11-19_vm-3rd.md) |
| | F1b-real: 실제 모델, 시간 초과 τ 5 s(4차) | `INFERENCE_TIMEOUT` | 다음 작업 계속. Pod 가 74번 모두 취소·폴더 정리 | 74시도 모두 τ 에 시간 초과, 5xx 0, 남은 폴더 0. 버려진 추론이 GPU 약 270 s 를 씀 [계산] | [실험 결과] [vm-4th 6절](../perf/load/2026-11-20_vm-4th.md) |
| | F1b: 운영 값 120 s, 스텁 180 s(5차, 훈련 backend) | 🐢 실패와 같은 순간(훈련 수집기로만) | 다음 작업 J2 COMPLETED | J1 처리 120.0 s 에 시간 초과, 연쇄 실패 없음 | [실험 결과] [vm-5th 7절](../perf/load/2026-11-21_vm-5th.md) |
| | FD13: healthz 는 UP, 추론만 멈춤(훈련 스텁) | 1차 없음 → 2차 🐢 2분 14초 | 없음(시간 초과로 실패) | 운영 값 600 s 였을 때 1건 실패에 10분 [계산] → 지금 120 s | [실험 결과] [failure-drills](../ops/2026-11-20_failure-drills.md) |
| **F2 생성 서버 장애** | F2-real: Pod 추론 서비스 `kill -9`, 주입 스크립트(`f2_kill.sh 360 90`)가 90 s 뒤 재시작(3차, ρ 0.5. 감독 루프 R7 전이라 자동 재시작 없음) | DOWN 1.3 s | 오프라인 대기(PENDING 유지) → UP 뒤 이어서 처리 | UP 146.7 s, 첫 완료 160.6 s, 대기열 회복 222 s, 잃은 작업 0, 5xx 0, 최악 대기 175 s | [실험 결과] [vm-3rd](../perf/load/2026-11-19_vm-3rd.md) |
| | R7 감독 루프: 실제 Pod `kill -9`(2026-11-20 2차) | 🟡·🟢(DOWN 이 유예 45 s 보다 짧음) | 감독 루프가 다시 띄움 | kill → UP 41 s, 사람 손 없음 | [실험 결과] [failure-drills '2차 확인'](../ops/2026-11-20_failure-drills.md) |
| | FD16b: 부하 중 Pod 정지 → 같은 Pod 다시 켜기 | 5차: 정지 요청 21.2 s 뒤 DOWN | 새 `instanceId` 를 보고 그 전 요청 포기(PR #22) | 1차: 한 작업이 슬롯을 600 s 잡아 최대 대기 602 s → 5차: 52 s 만에 `INFERENCE_UNAVAILABLE`, 기다린 5건 최대 74.3 s. 다시 켠 컨테이너의 서비스는 손으로 띄움 | [실험 결과] 훈련 backend·실제 Pod, 알림은 훈련 수집기로. [vm-5th 7절](../perf/load/2026-11-21_vm-5th.md) |
| | FD10 tailnet 120 s 차단, FD14 추론 SIGSTOP 120 s, FD12 응답 없음(오프라인 대기를 180 s 로 줄임) | DOWN 14.3 s / 2.0 s / 12.2 s | 회복 뒤 자동 재개 | FD10 11건·FD14 12건 모두 완료. FD12 는 180 s 를 넘긴 6건 실패, 14건 완료 | [실험 결과] [failure-drills](../ops/2026-11-20_failure-drills.md) |
| | FD16a 멈춘 Pod 재고 없음, FD17 토큰 불일치, FD18 모델 로드 실패, FD15 GPU 메모리 부족 | FD17 🔑 9.8 s, FD18 🧩(2차). FD15 는 작업 실패 알림뿐 | FD16a 는 사람이 새 Pod | FD16a 새 Pod 98 s 뒤 GPU UP. FD18 바르게 다시 띄워 40 s | [실험 결과] [failure-drills](../ops/2026-11-20_failure-drills.md) |
| **F3 대용량·잘못된 업로드** | F3(잘못된 업로드 9종 + 느린 업로드)은 하지 않았다. 스크립트는 **[구현됨]**(`f3_uploads.js`·`f3_throttled.py`) | 검사 코드: 413 `PAYLOAD_TOO_LARGE`(장당 5 MB·전체 20 MB), 400 `TOO_MANY_PHOTOS`·`UNSUPPORTED_IMAGE_TYPE`·사진 검사 코드 **[구현됨]**([ERROR_CODES.md](../api/ERROR_CODES.md), `GlobalExceptionHandlerTest`·`JobV1ApiTest`) | — | A3 0.8 MB 20/s 통과(1차) [실험 결과]. A3 20 MB 는 [계획] | F3 [계획] |
| | FD4 디스크 가득 참(1차) | 없음(healthz 200) | 공간을 비우자 회복 | GLB 저장 실패가 `CONVERSION_FAILED` 7건, 업로드 500 5건 → PR #22 로 `INTERNAL_ERROR` [구현됨]. 다시 재현 안 함 | [실험 결과] [failure-drills](../ops/2026-11-20_failure-drills.md) |
| **F4 다운로드 중 끊김** | F4(1,000회 중간 끊기)는 하지 않았다. 스크립트 `f4_abort.py` 는 **[구현됨]** | `beside_asset_downloads_total{outcome="client_abort"}` **[구현됨]**(`AssetDownloadMetricsFilterTest` 가 RST 로 확인) | — | VM ↔ Pod GLB 내려받기 60 s 무응답 끊기 [구현됨: PR #22], 재현 안 함([vm-5th 11절](../perf/load/2026-11-21_vm-5th.md)) | F4 [계획] |
| **F5 동시 요청 폭주** | F5(A5 스파이크 + 한 IP 50/s)는 하지 않았다. 클라이언트별 요청 제한(#22)은 429 `RATE_LIMITED` 이름만 예약했다 | — | — | API 쪽: A1·A2·A3(위 R2) [실험 결과]. GPU 앞 폭주: 2절의 `g3cap0` 대 `g3cap19` [실험 결과·계산] | F5 [계획]. [REVIEW_CHECKLIST #22](../api/REVIEW_CHECKLIST.md) |
| (멘토 5종 밖) API 서버·VM·알림 | FD1 backend `kill -9`, FD2 SIGSTOP, FD3 VM 재시작, FD5 DB 파일 유실, FD6 VM 유실, FD7 Caddy `kill -9`, FD9 웹훅 실패 | FD2 감시 3번 실패. FD5 1차 없음 → 5차 시작 거부·🧱 44.6 s(훈련 수집기로만) | FD1·FD7 Docker 재시작, FD2 VM 감시(R4) 재시작, FD5·FD6 사람이 백업·스냅샷으로 | FD1 끊김 17.8 s, FD7 2.0 s, FD2 1차 189 s 무응답 → 2차 112 s 에 재시작, FD3 82.8 s, FD5 5차 복구 뒤 healthz 14.8 s(RPO = 백업 간격), FD6 서비스 응답 89 s·운영 설정 145 s, FD9 5차 GPU 메시지 손실 0 | [실험 결과] [failure-drills](../ops/2026-11-20_failure-drills.md), [vm-5th 7절](../perf/load/2026-11-21_vm-5th.md) |

- 모든 시나리오는 1회씩 했다. 훈련의 시간 값은 한 번 잰 값이다.
- 5차 재현(FD16b·F1b·FD9·FD5)은 훈련 backend 에서 했고, 알림은 운영 웹훅이 아니라 VM 안 훈련 수집기로 갔다.
- 남은 것 [계획]: F3·F4·F5, FD4 다시 재현, FD16b 의 다른 두 길(120 s 시간 초과, GLB 60 s 무응답), FD9 진짜 블랙홀, 실제 모델이 멈춘 경우의 🐢, 외부 가동 감시(R3).

## 4. 지표 5개 — 어디서 모으고 보는가

| 지표 | 서버 지표 ([METRICS §5.1·5.2](../METRICS.md)) | PromQL | `/admin` | 부하 시험 CSV (`collect.py`) |
|---|---|---|---|---|
| 생성 성공률 | `beside_jobs_finished_total{result,code,passthrough}` | §5.3 1행(취소는 분모에서 뺌, 실제 모델일 때만 맞음) | '모델 버전별 통계' 성공률(`job_runs`) | `jobs_finished_rate.csv` |
| 생성 시간 평균/p95 | `beside_job_processing_seconds_*`(대기만은 `beside_job_queued_seconds_*`) | §5.3 2·3행 | 같은 표의 대기·생성·전체·추론 시간 | 실행별 값은 `vm/events.jsonl` 의 `processingMs` |
| 대기열 길이 | `beside_jobs_queue{state}` | §5.3 4행 | '대기열' 카드 | `jobs_queue.csv` |
| API 응답 시간 | `http_server_requests_seconds_*` | §5.3 5행(5xx 비율은 의도한 503 `QUEUE_FULL` 을 뺀 식) | 없음 | `http_p95_by_uri.csv`·`http_p99_by_uri.csv`·`http_5xx_ratio.csv` |
| 에셋 다운로드 실패율 | `beside_asset_downloads_total{outcome}` | §5.3 6행(`client_abort`·`error` ÷ 전체) | 없음 | `asset_downloads_rate.csv` |

- 이름·태그·버킷과 관리 포트 분리는 backend 테스트로 확인했다 **[구현됨]**. 모든 시계열에 `env`(`prod`·`loadtest`·`local`) 태그가 붙어 부하 시험 값이 운영 값과 섞이지 않는다(METRICS §5).
- 부하 시험 1~5차에서는 실행마다 위 CSV 를 VM 루프백 Prometheus 에서 받아 실행 폴더에 남겼다 **[실험 결과]**([TRAFFIC_MODEL 12절](../perf/load/TRAFFIC_MODEL.md)).
- 대시보드는 Grafana 'Beside' 폴더의 'Beside 운영'이다. 원본은 [beside-ops.json](../../deploy/monitoring/grafana/dashboards/beside-ops.json), 띄우는 법은 [deploy/README.md](../../deploy/README.md) '모니터링' 이다.

- **Grafana 운영 적용 [적용]**: 2026-11-25 05:52 UTC 에 운영 VM 에 Prometheus·Grafana 를 띄웠다. backend·caddy 는 건드리지 않았다(컨테이너 ID·재시작 0 그대로, 공개 경로 61/61).
- 같은 날 발표 GPU 리허설에서 실제 A40 작업 9건을 공개 주소로 보냈다. Prometheus 의 06:00 UTC 지난 10분 값은 다음과 같다 **[실험 결과]**([리허설 기록](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), 원본 `prometheus-0600Z.txt`).

| 지표 | 값 | 참고 |
|---|---|---|
| 생성 성공률 | 1 | COMPLETED 9건 |
| 생성 시간 평균 (`beside_job_processing_seconds`, 위 표와 같은 지표) | 11.3 s | 같은 9건의 `processingMs` 평균과 같음(`job-runs.csv`) |
| 생성 시간 p95 | 28.5 s | 히스토그램 버킷(10 s·30 s) 보간값. 실제 최대 `processingMs` 는 18.0 s |
| (참고) 접수 → 완료 전체 시간(`beside_job_total_seconds`, 대기 포함) 평균 / p95 | 19.6 s / 53.3 s | p95 는 보간값, 실제 최대 `totalMs` 40.2 s |
| 대기열 길이 최대 | 3 | pending 2 + processing 1 |
| API 응답 시간 p95 | `POST /api/v1/jobs` 2.26 s, 조회·에셋 약 0.024 s | 업로드는 사진 검사 포함 |
| 에셋 다운로드 실패율 | 0 | 13번 모두 ok |

- 대시보드의 '생성 시간 평균/p95' 숫자 칸은 2026-11-25 까지 접수 → 완료(total)를 보였다. 이 브랜치에서 METRICS §5.3 과 같은 처리 시간으로 고쳤다(`beside-ops.json` 패널 4·5, VM 에는 병합 뒤 `git pull`).
- 표본은 9건 1회뿐이다. 운영 지표로 인용하려면 시연·팀 사용 기간의 값을 다시 읽는다 [계획].
- Grafana 화면 확인과 발표용 캡처는 사람이 로그인한 뒤에 한다 [계획].

## 5. 확인한 것 (2026-11-25)

- 태그 `api-v1.0`(`3ae3ca1`)·`api-v1.1`(`fe10683`)·`api-v1.2`(`f6dfa66`): `git tag -l`, `git log -1 <태그>`.
- PR #19·#26·#27·#28·#29·#30·#31 병합, 열린 PR 0: `gh pr view <n> --json state,mergedAt`, `gh pr list --state open`.
- 2절 표의 등급별 GPU 대수: `python scripts/load/model.py --t 8.67 --t 8.81` 과 `python scripts/load/cap_model.py formula …` 를 다시 돌린 출력([TRAFFIC_MODEL 5.1절](../perf/load/TRAFFIC_MODEL.md)).
- `/admin` 의 카드·표 이름: `backend/src/main/resources/static/admin/index.html`.
- 운영 VM·GCP 상태(VM main `625c2a5`·`inference.timeout-ms` 120000, 가동 감시 0개)는 같은 날 메인 작업이 확인한 값을 옮겼다. 이 문서를 쓰며 VM·GCP 에 직접 접속하지는 않았다.
- 나머지 수치는 위 원문 문서에서 옮겼고, 이 문서에서 다시 재지 않았다.
