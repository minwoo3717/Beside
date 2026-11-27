# 서비스 목표 (SLO) [제안값]

- **무엇**: 사용자가 느끼는 품질을 수치로 정한 목표다. 측정식(SLI)과 목표, 목표를 지키지 못했을 때의 알림을 한곳에 둔다.
- **왜**: 2026-11-25 멘토링이 '실제 배포할 제품처럼' 해 보라고 했다.
  - 지금까지는 부하 시험 합격 기준만 있었다([TRAFFIC_MODEL §8](../perf/load/TRAFFIC_MODEL.md) [제안값]).
  - 운영 중 '좋다·나쁘다' 를 가르는 기준은 없었다.
  - 알림 규칙([Grafana 알림](../../deploy/monitoring/grafana/provisioning/alerting/rules.yml))의 임계값도 이 목표에서 나온다.
- **표기**: 모든 목표는 [제안값]이다. 팀 내부·시연 트래픽(T1) 기준이고, 공개 베타(T2) 전에 다시 정한다.
- **측정식**: [METRICS §5.3](../METRICS.md) 의 PromQL 을 쓰고, 운영 지표(`env="prod"`)만 센다.

| SLI | 측정식 (요지) | 목표 [제안값] | 기간 | 알림 (Grafana) |
|---|---|---|---|---|
| 가용성 | 외부 가동 감시(R3)의 공개 `/api/v1/healthz` 성공 비율 | 99 % | 7일 | R3 메일(2분 실패), `backend 지표 수집 실패`(2분) |
| 생성 성공률 | COMPLETED ÷ (끝난 작업 − 취소) | 95 % | 7일 | `작업 실패가 이어짐`(1시간 20 % 초과, 3건 이상) |
| 조회 응답 | `GET /api/v1/jobs/{jobId}` p95 (Spring, 라벨 `uri="/api/v1/jobs/{jobId}"`) | ≤ 300 ms | 1일 | `작업 조회 응답 p95 > 0.5 s`(2분) |
| 앞단 응답 | Caddy 접근 로그의 작업 조회(GET /api/v1/jobs/{id}) `duration` p95 (Loki) — Spring 이 못 보는 backend 앞 지연까지 | ≤ 1 s | 1일 | `앞단(Caddy) 작업 조회 p95 > 1 s`(2분) |
| 업로드 응답 | `POST /api/v1/jobs` p95 (사진 검사·배경 제거 포함) | ≤ 3 s | 1일 | `업로드 응답 p95 > 5 s`(5분) |
| 생성 대기 | `beside_job_queued_seconds` p95 | ≤ 180 s (큐 상한의 목표 최악 대기 W) | 1일 | `대기 시간 p95 > 60 s`(증설 기준, 10분) |
| 생성 시간 | `beside_job_processing_seconds` p95 (실제 모델) | ≤ 30 s | 1일 | 없음(대시보드) |
| 에셋 다운로드 | 실패(끊김·오류) ÷ 전체 | ≤ 1 % | 7일 | `에셋 다운로드 실패율 > 5 %`(1시간) |

**오류 예산**
- 가용성 99 %/7일이면 한 주에 약 100분까지 멈춰도 된다 [계산].
- 그보다 많이 멈춘 주에는 기능 배포를 멈추고 원인부터 고친다.
- 부하 시험·장애 훈련으로 운영을 일부러 멈춘 시간도 예산에서 빠진다. 그래서 2026-11-26 부터 터뜨려 보는 시험은 스테이징 복제본에서 한다([REDUNDANCY §6](REDUNDANCY.md), `deploy/gcp/clone_vm.sh`).

**2026-11-26 스테이징 훈련으로 바꾼 것**([2026-11-26_staging-drills.md](2026-11-26_staging-drills.md)):
- 조회 알림이 라벨 `{id}` 를 보고 있어 한 번도 울릴 수 없었다. `{jobId}` 로 고치고 0.5 s·2분으로 줄였다(옛 1 s·10분은 4분짜리 과부하를 놓쳤다).
- backend 앞 지연은 Spring 지표에 안 보였다(앞단 p95 17 s 동안 Spring 0.024 s). 그래서 앞단 응답 SLI 와 Loki 알림을 더했다. 재시험에서 지연을 넣고 3분 35초 뒤 울렸다 [실험 결과]. 그때 규칙은 Caddy 의 모든 요청을 봤다. 배포 전 검토에서 작업 조회만 보도록 좁혔다. 이유는 Grafana 웹소켓(연결 내내 한 줄)과 GLB 전송처럼 원래 긴 요청이 트래픽이 적을 때 p95 를 끌어올리기 때문이다. 훈련 기록 표의 앞단 p95(s2d 1.35 → 17.0 s)는 처음부터 작업 조회만 잰 값(`s2_probe.py`)이다. 좁힌 규칙이 운영에서 울리는 것은 [계획]으로 확인한다.

**알림 임계값이 목표보다 느슨한 이유**
- 트래픽이 적은 시간에는 몇 건이 백분위를 크게 흔든다. 그래서 알림은 '확실히 나쁜' 선(Spring 조회 0.5 s·앞단 조회 1 s, 업로드 5 s)에 둔다. 조회 알림은 2026-11-26 훈련 뒤 1 s 에서 0.5 s 로 줄였다(평소 0.01~0.02 s).
- 목표 달성 여부는 대시보드에서 기간 단위로 본다.

**관측 도구 자체의 감시 (2026-11-27 더함)** — 규칙 파일 [구현됨], 운영 적용은 배포 창 3 [계획]
- 왜: 로그·트레이스 수집기가 죽으면 로그 알림이 조용해질 뿐 아무도 모른다(지금까지 수집기가 죽어도 알림이 없었다).
- 알림 4개(SLI 가 아니라 도구 감시라 위 표에 넣지 않는다). 규칙은 13개 → 17개. 같은 날 GPU Pod 의 ERROR 로그 알림 1개를 더해 18개(아래 표 마지막 줄).

| 알림 (Grafana) | 질의 | 울리는 조건 |
|---|---|---|
| `Loki 지표 수집 실패 (로그 저장 멈춤)` | `up{job="loki"}` | 1 미만이 5분 |
| `Alloy 지표 수집 실패 (로그 수집 멈춤)` | `up{job="alloy"}` | 1 미만이 5분 |
| `Tempo 지표 수집 실패 (트레이스 저장 멈춤)` | `up{job="tempo"}` | 1 미만이 5분 |
| `Alloy 로그 전송 실패 (Loki 에 못 쓴 줄)` | `sum(increase(loki_write_dropped_entries_total[10m]))` | 0 초과 |
| `추론 서비스(GPU Pod) ERROR 로그` | `sum(count_over_time({service="inference", env="prod", level="ERROR"} [5m]))` (Loki) | 0 초과 |

- `up` 은 Prometheus 가 세 컨테이너의 `/metrics` 를 읽어 만든다([prometheus.yml](../../deploy/monitoring/prometheus.yml) 의 job `loki`·`alloy`·`tempo`).
- 로그가 0줄 들어오는 것을 보는 규칙은 두지 않는다. 조용한 밤에도 울리기 때문이다(VM 감시 `beside-watchdog.sh` 도 `docker exec` 로 backend 컨테이너 안의 Actuator health(관리 포트 8081)를 물어 Caddy 줄을 만들지 않는다).
- 같은 날 로그 알림이 부하 시험에 울리지 않게 고쳤다. backend 로그 알림 2개는 env 라벨이 `loadtest`·`local` 인 줄을 뺀다(env 가 없는 옛 줄은 계속 본다). 앞단 응답 알림은 k6 요청(`X-Request-Id` 가 `lt-` 로 시작)을 뺀다. 이 Caddy 질의를 실제 Caddy 줄로 확인하는 것은 [계획]이다.
- GPU Pod 알림은 Pod 의 `ship_logs.py` 가 tailnet 으로 VM 의 Alloy(3500)에 보낸 줄을 본다. Alloy 가 `service="inference"` 로 고정하고 수준을 줄 맨 앞에서 읽는다(`WARNING`→`WARN`, `CRITICAL`→`ERROR`). env 는 Pod 의 `BESIDE_TELEMETRY_ENV`(기본 `prod`)라 부하 시험 Pod 는 `loadtest` 로 두거나 아예 보내지 않는다. 운영 Pod 에서 줄이 들어오는 것을 보는 것은 [계획](E4).

**아직 하지 않은 것** [계획]
- SLI 를 기간 단위로 보여 주는 대시보드 패널
- R3 외부 가동 감시(가용성 SLI 의 측정원)
- 주간 검토
