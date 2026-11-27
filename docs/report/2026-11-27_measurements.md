# 발표 수치와 측정표 (2026-11-27)

작성 2026-11-27, 브랜치 `wave/remaining-2026-11-27`(기준 `241ab5e`). [CHECKLIST](../CHECKLIST.md) 0절 M14 와 3절 5단계 '측정표 3종 + 환경'([PLAN](../PLAN.md) 5단계)의 산출물이다.

- **목적**: 발표 수치 한 장과 측정표 3종(서버·추론·앱)을 한 문서에 모은다. 표마다 조건(사진, 실행 횟수, 동시성, 환경, 날짜)을 적는다.
- **모든 수치는 출처 문서의 값을 옮긴 것이다. 새로 잰 것은 없다.** 값은 원문에 적힌 그대로 옮겼다. 반올림·단위 변환을 하지 않았다. 그래서 Prometheus 값(§5)도 원문대로 초로 두었다. METRICS 는 보고서 표에서 ms 로 바꾸라고 한다([docs/METRICS.md:179](../METRICS.md)).
- **출처**: 리포 기준 `파일:줄`(2026-11-27 리포 기준). 링크는 그 문서로 간다.
- **이름**: 지표 이름은 [METRICS.md](../METRICS.md) 의 이름을 쓴다. METRICS 에 없는 값(k6 `load_waitMs`, 모델 로드 시간, Caddy 메모리, `msPerPet` 등)은 'METRICS 밖' 이라고 적는다.

표기(루트 CLAUDE.md):
- **[구현됨]** 코드와 테스트가 리포에 있다.
- **[실험 결과]** 측정값과 조건이 `docs/` 또는 `generation/experiments/` 에 있다.
- **[계산]** 측정값으로 계산한 값. **[제안값]** 확정하지 않은 기준. **[가설]** 확인하지 않은 설명.
- **[적용]** 운영에 켜 둔 설정. **[미확인]** 원문이 확인하지 않았다고 적은 것.
- **[계획]** 아직 하지 않았다.
- `unity/experiments/` 의 털 측정은 [실험 결과] 정의에 넣을지 사용자 결정 전이다(CHECKLIST S11). 그래서 '[실험 결과] — S11 결정 전' 으로 적는다.

조건 줄임말:
- **사진 1장 반복**: `backend/src/test/resources/photos/dog.jpg`(169,451 B, CC0, 1마리)를 작업마다 1장 보낸다. VM 을 거친 작업은 VM 이 크롭·배경 제거한 PNG 가 Pod 로 간다([docs/perf/load/2026-11-18_vm-2nd.md:66](../perf/load/2026-11-18_vm-2nd.md)).
- **A40**: Runpod A40 Pod 1대(Secure, EU-SE-1, 스웨덴). Pod 는 실행마다 다르다(환경 절).
- **VM**: GCP e2-standard-2(2 vCPU, 8 GB), 서울.
- **c**: 추론 슬롯 수. 한 번에 GPU 로 보내는 작업 수다.
- **1회**: 그 실행·훈련을 한 번만 했다. 반복 측정은 없다.

## 발표 수치 한 장

줄 앞에 발표 흐름(문제 → 구현 → 실험 → 결과 → 개선)의 어느 자리인지 적었다. 조건은 한 줄로 줄였다. 자세한 조건은 아래 표와 출처에 있다. 앱 `metrics.csv` 실기기 수치는 0건이라 이 장에 없다(측정표 3).

1. 결과 — **사진 한 장 추론 `inferMs` 중앙값 3,761 ms**(평균 3,989, p95 4,326, 최대 15,411). A40 Pod `yq8zyoibhf15l5`, c=1, 사진 1장 반복 539건, 배치 1, 리깅 없음, 2026-11-18. 2차 부하 시험 다섯 실행을 합친 값이다(G0r 31건은 대기열 없음, G1r·G3r 508건은 대기열 있음). [실험 결과] [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:8·59·63·64·67·72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-18_vm-2nd.md:13·61](../perf/load/2026-11-18_vm-2nd.md)
2. 문제 → 개선 — **한 건 처리 `processingMs` 중앙값 8.09 s 중 전송 등이 3.64 s**(`inferMs` 3.64 s, `convertMs` 0.82 s, 모두 중앙값). 전송 등은 작업마다 `processingMs` − `inferMs` − `convertMs` 로 낸 값이다. 4차 `g0d4`, 사진 1장 반복, 30초마다 1건(대기열 없음), 15분, c=1, 31건, Pod `nsz73mtl802mvw`(keep-alive 75 s·스레드 8, backend JDK 유휴 60 s), 서울 VM ↔ 스웨덴 A40(Tailscale 직접), 2026-11-19 UTC. [실험 결과·계산] [docs/perf/load/2026-11-20_vm-4th.md:12·38·104·106·107·112·113·119-131](../perf/load/2026-11-20_vm-4th.md). 그 전 개선: 3차에서 Pod keep-alive 를 5 s → 75 s 로 늘려 처리 중앙값이 9.09 → 8.22 s(−9.6 %)로 줄었다 [계산]. 조건은 3차 `g0ka5` → `g0ka75`, 같은 Pod `4vh9x6broleb2z`, 30초마다 1건, 깨끗한 작업 26·28건, 2026-11-18 UTC 다. 4차의 8.09 s 는 다른 Pod 에서 keep-alive 75 s·스레드 8·JDK 60 s 로 잰 값이라 이 개선의 '뒤' 값이 아니다. [docs/perf/load/2026-11-19_vm-3rd.md:11·46·110·124·131](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:139](../perf/load/2026-11-20_vm-4th.md)
3. 결과 — **A40 1대 처리 용량 S_busy 8.81 s → 409건/h**(S_busy 는 METRICS 밖, c=1). 5차 `g3cap0`, 사진 1장 반복, 요청 517건/h(잰 도착 575건/h), 과부하 40분, 바쁜 쌍 344/345, 털 맵 GLB, 2026-11-21 UTC. [실험 결과·계산] [docs/perf/load/2026-11-21_vm-5th.md:6·12·29·34·84·146](../perf/load/2026-11-21_vm-5th.md). 2차 과부하 재실행에서는 GPU 자체가 한계가 아니었다: GPU 사용률 평균 22.7 %(METRICS 밖, nvidia-smi 5 s). 2차 `g3r165`, 161건/20분, c=1, Pod `yq8zyoibhf15l5`, 털 맵 없는 GLB(1,326,748 B), 2026-11-18 UTC. [실험 결과] [docs/perf/load/2026-11-18_vm-2nd.md:21·26·36·61-62·156-158](../perf/load/2026-11-18_vm-2nd.md), [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md). 5차 문서는 GPU 사용률 값을 적지 않았다. 원시 `nvidia-smi` 5 s 표본은 있다([docs/perf/load/2026-11-21_vm-5th.md:88](../perf/load/2026-11-21_vm-5th.md), [docs/perf/load/data/2026-11-21_1033-g3cap0/gpu/gpu.csv](../perf/load/data/2026-11-21_1033-g3cap0/gpu/gpu.csv)). 4차 `g3c1`(c=1 과부하, 157건/20분, 요청 517건/h(잰 471건/h), Pod `nsz73mtl802mvw`, 정적 GLB(1,326,748 B), 2026-11-19 UTC)의 GPU 사용률 평균은 24.3 %(METRICS 밖)였다. [실험 결과] [docs/perf/load/2026-11-20_vm-4th.md:12·39·106·107·206·214](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:43·116](../perf/load/2026-11-21_vm-5th.md), [g3c1 events.jsonl `glbBytes` 189건](../perf/load/data/2026-11-20_0256-g3c1/attachments/events.jsonl)
4. 결과 — **API 계층(mock 워커) 폴링 900 요청/s 를 지연 기준 안에서 유지, 1,200 요청/s 에서 무너짐**. 다만 900 요청/s 단계도 drop 509건(0.19 %)이 있었고, VM CPU 평균 85.8 % 로 §8 기준(< 85 %)을 넘었다. 먼저 닿는 한계는 VM CPU(2 vCPU)이고 그 CPU 를 가장 많이 쓴 것은 Caddy(TLS·프록시)다. A2, 단계마다 5분, 폴링 대상 COMPLETED 1,000건·PENDING 1,000건, k6 US-CA-2(왕복 약 153 ms), 1회, 2026-11-18. [실험 결과], Caddy 를 병목으로 보는 해석은 [가설] [docs/perf/load/2026-11-18_vm-1st.md:14·18·166-168·177](../perf/load/2026-11-18_vm-1st.md). 규칙상 마지막 통과는 300 요청/s 다(600 요청/s 의 drop 때문, [docs/perf/load/2026-11-18_vm-1st.md:175-176](../perf/load/2026-11-18_vm-1st.md))
5. 개선 — **큐 상한 N 19**: 상한 없이 과부하면 만든 383건 중 132건(34.5 %)이 k6 흐름 시간 초과(600 s, 클라이언트 쪽)로 끝났다. 그중 100건은 사용자가 떠난 뒤 서버가 COMPLETED 로 끝냈다. 상한 19 면 생성 요청 337건 중 51건(15.1 %)을 바로 503 `QUEUE_FULL` 로 돌려보내고, 받아들인 286건은 모두 COMPLETED, `totalMs`(생성 → COMPLETED) 최대 176.3 s 다(`queuedMs` 최대 168.1 s). GPU 처리량은 그대로다(S_busy 비 0.9999). c=1, 사진 1장 반복, 요청 λ 517건/h, 40분씩, 같은 A40, `Retry-After` 167 s 로 시험, 2026-11-21 UTC. [실험 결과·계산] [docs/perf/load/2026-11-21_vm-5th.md:29·34·35·50·148·192·194·214](../perf/load/2026-11-21_vm-5th.md). 두 실행의 도착률이 575 대 505.5건/h 로 달라 같은 조건의 짝은 아니다([docs/perf/load/2026-11-21_vm-5th.md:364](../perf/load/2026-11-21_vm-5th.md)). 운영 상한 켜기는 [계획] ([docs/METRICS.md:194](../METRICS.md))
6. 개선 — **장애 감지·자동 복구**: 실제 배포에서 장애 18가지를 재현했다. backend 멈춤(FD2): 대응 전에는 외부 응답 없음 189 s, 재시작 없음이었다(SIGSTOP 180 s 뒤 걸어 둔 SIGCONT 로만 풀림). VM 감시 배포 뒤에는 멈춘 지 112 s 에 컨테이너가 다시 시작됐다. 정상까지의 시간은 재지 못했다(약 2분 [계산]). 추론 토큰 불일치(FD17): 대응 전에는 GPU 상태 UP 그대로, 작업마다 실패 웹훅만 갔다. 대응 뒤에는 🔑 알림이 9.8 s 에 왔다(스텁 재시작 포함). 운영 VM 의 훈련 backend, 추론은 훈련 스텁, 각 1회, 2026-11-20 UTC. 189 s 와 112 s 는 원문에서 [계산]이다. [실험 결과·계산] [docs/ops/2026-11-20_failure-drills.md:1·37·68·70-89·111·116·118·123](../ops/2026-11-20_failure-drills.md)
7. 개선 — **대기 VM 으로 계획 전환 끊김 65.0~68.1 s, 되돌리기 63.0~64.1 s, 표지 작업 RPO 0**. 범위는 여러 번 잰 값이 아니다. 1회 실행을 요청 보낸 시각과 응답 받은 시각 두 기준으로 잰 차이다. 바깥 탐침 1 s healthz(US-CA-2), GPU 없이 healthz 만, 방향마다 1회, 2026-11-26 UTC. 목표 ≤ 3분 [제안값]. [실험 결과] [docs/ops/2026-11-26_failover-drills.md:7·13-14·17·21-23·33·36·45-48·85](../ops/2026-11-26_failover-drills.md)
8. 결과 — **운영 GLB 3종 gltf-validator 오류 0·경고 0**. 종류(정적·털 맵·리깅)마다 운영 VM 의 파일 1개를 검사했다. 리깅 + 털 맵 + 클립 5개 GLB(`35b357d7`)는 `glbBytes` 2,100,924 B, `outputTriangles` 7,774 다(GLB_SPEC 상한 10 MB·20,000 은 [제안값]). 이 파일의 입력 사진은 `dog.jpg` 1장이고, 정적·털 맵 파일의 입력 사진은 [미확인]이다. gltf-validator 2.0.0-dev.3.10, 2026-11-27 KST(2026-11-26 UTC). [실험 결과] [generation/experiments/2026-11-27_glb-validator.md:5·9·21-25·33·36·43·46](../../generation/experiments/2026-11-27_glb-validator.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:19·44](../ops/data/2026-11-26_pod-log-check/README.md)

## 측정표 1 — 서버(API·대기열)

### 1.1 API 계층 (1차 A 시리즈, mock 워커)

공통 조건: VM `b6c0e6c` `mock,prod`(mock PROCESSING 60 s, @Async 풀 400, 사진 검사·크롭 끔), 앞단 Caddy 2.11.7(TLS, 메모리 상한 없음), 사진 합성 0.8 MB(`photo-400k.png` × 2), GLB 합성 1,500,000 B. 부하 발생기 Runpod CPU Pod `cpu3g` 8 vCPU(US-CA-2), k6 v2.3.0, RTT_base 153.6 ms. 실행마다 1회, 2026-11-18. 출처 [docs/perf/load/2026-11-18_vm-1st.md:33-41](../perf/load/2026-11-18_vm-1st.md). 클라이언트 지연에는 왕복 약 153 ms 가 들어 있다.

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| k6 poll 지연 p95 / p99 (METRICS 밖, 클라이언트, 왕복 약 153 ms 포함) | 157 / 158 ms | A1 3단계 1,800명(T3 평상시), 37.1 요청/s, 8분 유지 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:100](../perf/load/2026-11-18_vm-1st.md) |
| k6 poll 지연 p95 / p99 (METRICS 밖, 클라이언트, 왕복 약 153 ms 포함) | 160 / 205 ms | A1 7단계 9,000명, 185.1 요청/s, 8분 유지 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:104](../perf/load/2026-11-18_vm-1st.md) |
| 생성(`POST`, 0.8 MB) p95 (METRICS 밖, k6) | 1,070 ms | A1 9,000명 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:104](../perf/load/2026-11-18_vm-1st.md) |
| 에셋(`GET`, 1.5 MB) p95 / p99 (METRICS 밖, k6) | 7,013 / 7,579 ms | A1 9,000명. 중앙값은 모든 단계에서 1,076~1,081 ms, 느린 것은 6~7.6 s 에 몰린다. 늘어난 몫은 본문 수신이고 어디서 늦는지는 가리지 못했다 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:104·147-148](../perf/load/2026-11-18_vm-1st.md) |
| 오류 (A1 전체, k6·서버. k6 값은 METRICS 밖) | 요청 311,255건, 체크 실패 0, 연결 오류 0, 서버 5xx 0, 600 s 넘긴 흐름 0, backend 재시작 0 | A1 8단계 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:111](../perf/load/2026-11-18_vm-1st.md) |
| 규칙상 판정 (A1, METRICS 밖, k6) | `dropped_iterations` 12 → 통과 단계 없음(`breaking point: invalid`) | A1. 그래서 '최대 지속 처리량 185 RPS, 8분 유지, 5xx 0' 으로만 쓴다 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:84·144](../perf/load/2026-11-18_vm-1st.md) |
| `system_cpu_usage` 평균 / 최대 | 51.0 % / 68.8 % | A1 9,000명 유지 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:127](../perf/load/2026-11-18_vm-1st.md) |
| Caddy 메모리 최대 (METRICS 밖, `docker stats`) | 유지 구간 2,170 MiB, 부하 직후 드레인 중 2,246 MiB | A1 9,000명. 원인은 [가설] | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:21](../perf/load/2026-11-18_vm-1st.md) |
| `uploadMs`(서버) p95 | 유지 구간마다 856~946 ms | A1, attempt 1, 0.8 MB. 기준 ≤ 300 ms 를 넘었지만 153 ms 경로 몫으로 보여 판정하지 않음 [가설] | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:90](../perf/load/2026-11-18_vm-1st.md) |
| k6 poll 지연 p95 / p99 (METRICS 밖, 클라이언트, 왕복 약 153 ms 포함) | 159 / 243 ms. 오류 0, drop 0, VM CPU 54.0 % / 92.4 % | A2 300 요청/s, 5분 유지. 규칙상 마지막 통과 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:175·185](../perf/load/2026-11-18_vm-1st.md) |
| k6 poll 지연 p95 / p99 (METRICS 밖, 클라이언트, 왕복 약 153 ms 포함) | 201 / 322 ms. 오류 0, drop 509, VM CPU 85.8 % / 88.8 % | A2 900 요청/s(k6 가 보낸 898/s) | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:187](../perf/load/2026-11-18_vm-1st.md) |
| k6 poll 지연 p95 / p99 (METRICS 밖, 클라이언트, 왕복 약 153 ms 포함) | 6,314 / 8,258 ms. 오류 1.3 %, k6 가 보낸 651/s | A2 1,200 요청/s 목표. 무너짐 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:188](../perf/load/2026-11-18_vm-1st.md) |
| API 응답 시간 poll p95 (`http_server_requests`, Spring, Caddy 뒤) | 1분 창마다 poll 의 95 % 이상을 25 ms 이하로 처리(1,200 요청/s 의 1분 창 3개만 25~50 ms) | A2 모든 단계 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:20](../perf/load/2026-11-18_vm-1st.md) |
| CPU 몫 (METRICS 밖, `docker stats`, 코어 하나 = 100 %) | Caddy 85~90 %, backend 63~66 %(요청당 코어 시간 약 1.0 ms 대 0.74 ms) | A2 900 요청/s | [실험 결과]. Caddy 병목 해석은 [가설] | [docs/perf/load/2026-11-18_vm-1st.md:18·204](../perf/load/2026-11-18_vm-1st.md) |
| 생성(`POST`, 0.8 MB) p95 / p99 (METRICS 밖, k6) | 362 / 366 ms. VM CPU 39.0 % / 43.5 % | A3 업로드만 20 업로드/s, 유지 구간. 6단계 모두 통과 | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:15·230](../perf/load/2026-11-18_vm-1st.md) |
| 에셋(`GET`, 1.5 MB) 중앙값 / p95 / p99 (METRICS 밖, k6) | 1,078 / 2,185 / 3,496 ms. VM CPU 17.5 % / 19.7 % | A4 10 /s(120 Mbps), 마지막 통과. 15 /s 실패는 부하 발생기 경로 상한(약 210 Mbps) 탓으로 본다 [가설] | [실험 결과] | [docs/perf/load/2026-11-18_vm-1st.md:16·255·264](../perf/load/2026-11-18_vm-1st.md) |

### 1.2 생성 대기열 (실제 GPU, 2~5차)

공통 조건: VM `real,prod`, 사진 검사·크롭 켬, A40 1대, 사진 1장 반복, VM ↔ Pod Tailscale 직접 연결(ping 263~269 ms, 실행별 값은 환경 절), 부하 발생기 Runpod CPU Pod `cpu3g` 4 vCPU(US-CA-2) k6 v2.3.0, 실행마다 1회. Pod·backend 커밋은 환경 절.

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| 오류 | 539건 모두 COMPLETED. 재시도·포기·5xx 0 | 2차 다섯 실행(G0r·G1r ×2·G3r ×2), c=1, 2026-11-18 | [실험 결과] | [docs/perf/load/2026-11-18_vm-2nd.md:13](../perf/load/2026-11-18_vm-2nd.md) |
| `uploadMs`(서버) 중앙값 / p95 | 77 / 94 ms | 2차 G0r, 30초마다 1건, 31건, 사진 169,451 B | [실험 결과] | [docs/perf/load/2026-11-18_vm-2nd.md:191](../perf/load/2026-11-18_vm-2nd.md) |
| k6 `load_waitMs` 중앙값 / p95 (METRICS 밖: `POST` 응답 → COMPLETED 관측. 앱 `waitMs` 아님) | 13.0 / 17.3 s | 2차 G0r, 31건, 동시 작업 최대 1, 2초 폴링 | [실험 결과] | [docs/perf/load/2026-11-18_vm-2nd.md:106](../perf/load/2026-11-18_vm-2nd.md) |
| `queuedMs` 평균 / 중앙값 / 최대 | 60.8 / 37.7 / 204.8 s | 4차 `g3c1` 과부하, c=1, 요청 517건/h(잰 471건/h), 157건, 20분, 2026-11-19 UTC | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:39·206·210](../perf/load/2026-11-20_vm-4th.md) |
| `queuedMs` 평균 / 중앙값 / 최대 | 7.27 / 3.27 / 53.1 s | 4차 `g2c2t`, c=2, 잰 519건/h, 173건, 같은 Pod | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:206·210](../perf/load/2026-11-20_vm-4th.md) |
| 대기열 길이(PENDING) 최대 | 111 | 5차 `g3cap0`, 상한 0, 요청 517건/h(잰 575건/h), 383건, 40분, 2026-11-21 UTC | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:34](../perf/load/2026-11-21_vm-5th.md) |
| 대기열 길이(PENDING) 최대 | 19.0(174 표본) | 5차 `g3cap19`, 상한 N 19 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:191](../perf/load/2026-11-21_vm-5th.md) |
| `beside_jobs_refused_total` | create +51, retry +0(= 503 `QUEUE_FULL` 51건, 생성 요청 337건의 15.1 %). 다른 5xx 0 | 5차 `g3cap19`, `Retry-After` 167 s, 40분 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:35·190·193](../perf/load/2026-11-21_vm-5th.md) |
| `totalMs`(생성 → COMPLETED) 최대 / p95 / 평균, 받아들인 작업 | 176.3 / 173.1 / 138.6 s(286건) | 5차 `g3cap19`. 한계 (N+1)·S_busy + 10 s = 186.2 s | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:192](../perf/load/2026-11-21_vm-5th.md) |
| `totalMs`(생성 → COMPLETED) 최대 | 887.8 s(351건, 실행 뒤 끝난 12건 포함) | 5차 `g3cap0`. 재시작이 32건을 닫아 실제 최대보다 작게 잡혔다 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:213·365](../perf/load/2026-11-21_vm-5th.md) |
| k6 흐름 시간 중앙값 / p95 / 최대 (METRICS 밖: `POST` → GLB 받음) | 상한 0: 449.5 / 603.5 / 604.0 s. 상한 19: 156.0 / 177.2 / 182.9 s | 5차 두 실행. 도착률이 575 대 505.5건/h 로 달라 같은 조건의 짝이 아니다 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:212·364](../perf/load/2026-11-21_vm-5th.md) |
| k6 흐름 600 s 시간 초과 (METRICS 밖, 클라이언트) | 상한 0: 132건(34.5 %). 상한 19: 0 | 5차 두 실행 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:211](../perf/load/2026-11-21_vm-5th.md) |
| `system_cpu_usage` 평균 / 최대 | 상한 0: 23.9 % / 73.1 %. 상한 19: 19.8 % / 59.9 % | 5차 두 실행 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:150](../perf/load/2026-11-21_vm-5th.md) |
| VM 처리 상한 (METRICS 밖, 작업당 CPU 로) | 작업당 3.6~4.5 코어·초 → 2 vCPU 70 % 상한 약 1,300~1,400건/h | 2차 실행들, 사진 검사·크롭 켬. 대부분이 크롭이라는 것은 [가설] | [계산] | [docs/perf/load/2026-11-18_vm-2nd.md:44](../perf/load/2026-11-18_vm-2nd.md) |

### 1.3 한 건 (운영 VM, 스크립트 클라이언트)

`scripts/e2e_mock.sh` 를 공개 주소로 돌린 값이다. 앱 `e2eMs`(METRICS §3)가 아니다.

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| e2e (METRICS 밖, 스크립트) | 20,394 ms(업로드 1,403, 대기 18,797, 내려받기 194) | 2026-11-26, 개발 PC → 공개 주소, 운영 VM main `a44b3ed` real,prod, 사진 1장, 모델 올린 뒤 첫 추론, 리깅 GLB, 1건 | [실험 결과] | [docs/ops/data/2026-11-26_pod-log-check/README.md:12·19·41·45](../ops/data/2026-11-26_pod-log-check/README.md) |
| `queuedMs` / `processingMs` / `totalMs` | 4,251 / 15,072 / 19,323 ms | 같은 1건. `queuedMs` 의 대부분은 크롭·배경 제거 3.6 s(`preprocessMs`) | [실험 결과] | [docs/ops/data/2026-11-26_pod-log-check/README.md:42-43](../ops/data/2026-11-26_pod-log-check/README.md) |
| 트레이스 `http post /api/v1/jobs` (METRICS 밖, Tempo span) | 1,221.8 ms(status 202) | 같은 1건 | [실험 결과] | [docs/ops/data/2026-11-26_pod-log-check/README.md:53](../ops/data/2026-11-26_pod-log-check/README.md) |
| e2e (METRICS 밖, 스크립트) | 22.1 s(업로드 749 ms, 대기 21.2 s) | 2026-11-25 리허설 첫 작업, 공개 주소로(클라이언트 위치 기록 없음), 운영 VM main `625c2a5`, 사진 1장, 모델 올린 뒤 첫 추론(`inferMs` 8,353), 리깅, 1건 | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:7·12·28-31](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |
| `totalMs` (겹친 4건) | 11,785 → 19,779 → 30,174 → 40,239 ms | 리허설, 1.2 s 간격으로 보낸 4건, 앞 작업이 끝날 때마다 한 칸씩 밀림. 설정 파일의 추론 슬롯 1개(`inference.max-concurrency=1`, `625c2a5` 판도 같음) | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:15·36](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [backend/src/main/resources/application-real.properties:25](../../backend/src/main/resources/application-real.properties) |

### 1.4 멘토 지표 5종 (운영 Prometheus, METRICS §5.3)

조건: 2026-11-25 06:00 UTC 지난 10분, 운영 VM main `625c2a5`, 리허설 9건(모두 리깅), 사진 1장. 질의는 §5.3 식과 창·조건이 다르다(`increase` 10분, `worker=~".*"`, `passthrough="false"` 거르기 없음, [docs/METRICS.md:268](../METRICS.md), [docs/ops/data/2026-11-25_demo-gpu-rehearsal/prometheus-0600Z.txt:2·32](../ops/data/2026-11-25_demo-gpu-rehearsal/prometheus-0600Z.txt)). 처리·대기 시간 세 질의는 06:00 을 기준 시각으로 06:1x UTC 에 덧붙였다([docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:41](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md)). 백분위는 버킷 보간 근사값이다([docs/METRICS.md:180](../METRICS.md)). Grafana 화면에서 이 식을 확인하는 일은 [계획] ([docs/METRICS.md:268](../METRICS.md)).

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| 생성 성공률 | 1(COMPLETED 약 9.4 = 실제 9건) | `increase()` 외삽이라 건수가 정수가 아니다 | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:48](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |
| 생성 시간 평균 (`beside_job_processing_seconds`) | 11.3 s | `job-runs.csv` 의 `processingMs` 평균과 같다 | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:49](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |
| 생성 시간 p95 | 28.5 s | 10 s·30 s 버킷 사이 보간. 실제 최대 `processingMs` 는 18.0 s | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:50](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |
| 대기열 길이(PENDING) 최대 | 2(참고: pending + processing 합 3) | 상태별 10분 최대 | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:53](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/data/2026-11-25_demo-gpu-rehearsal/prometheus-0600Z.txt:14-18](../ops/data/2026-11-25_demo-gpu-rehearsal/prometheus-0600Z.txt) |
| 에셋 다운로드 실패율 | 0(ok 약 13.5 = 실제 13번) | | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:54](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |
| API 응답 시간 p95 | `POST /api/v1/jobs` 2.26 s, 조회·에셋·healthz 약 0.024 s | | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:55](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md) |

## 측정표 2 — 추론(GPU·GLB)

공통 조건: A40 48GB(드라이버 595.112.07), torch 2.4.0+cu124, AnimalLift `5d364cf` `latest.pt`, 배치 1, 작업당 사진 1장([generation/experiments/2026-11-17_exp-01_baseline-runpod.md:7-9](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)). 2~5차는 사진 1장 반복이다.

### 2.1 모델 수치 (METRICS §2)

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| `inferMs` | 3850 ms | exp-01, Pod 안에서 직접, 원본 JPG `dog.jpg`, 리깅 끔, 1건, 2026-11-17 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:35](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `inferMs` | 5728 / 4081 / 3982 ms | exp-01, VM 경유 사진 3장(`dog.jpg` 첫 추론 / `dog-single-black.jpg` / `dog-single-edge.jpg`), 각 1건, DERP 중계, 2026-11-17 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:32-34](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `inferMs` 평균 (중앙값, p95, 최소~최대) | 4,071 (4,008, 4,538, 3,699~4,670) ms | 2차 G0, 대기열 없음, 31건, 리깅 끔, 2026-11-18 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:8·59·63·71](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `inferMs` 평균 (중앙값, p95, 최소~최대) | 3,989 (3,761, 4,326, 3,377~15,411) ms | 2차 전체 539건(G0 31 + G1·G3 508), 모두 COMPLETED | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:67·72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `inferMs` 중앙값 | 3.64 s | 4차 `g0d4`, 31건, c=1, 정적 GLB(1,326,748 B), 2026-11-19 UTC | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:12·106·127·129](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:116](../perf/load/2026-11-21_vm-5th.md) |
| `inferMs` 중앙값 | 3.671 s | 5차 `g0d5`, 20건, c=1, 털 맵 GLB, 2026-11-21 UTC | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:118](../perf/load/2026-11-21_vm-5th.md) |
| `inferMs` (모델 올린 뒤 첫 작업) | 6.86 s / 6.75 s / 8,353 ms / 7030 ms | 4차 확인 작업 / 5차 확인 작업 / 2026-11-25 리허설(리깅) / 2026-11-26 로그 확인(리깅), 각 1건. 데움으로 보는 것은 [가설] | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:123](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:111](../perf/load/2026-11-21_vm-5th.md), [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:31](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:44](../ops/data/2026-11-26_pod-log-check/README.md) |
| `convertMs` 평균 (중앙값, p95, 최소~최대) | 900 (816, 987, 678~5,979) ms | 2차 전체 539건, 리깅 없음 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:8·63·72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `convertMs` 중앙값 | 0.82 s | 4차 `g0d4`, 31건 | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:127·130](../perf/load/2026-11-20_vm-4th.md) |
| `convertMs` (리깅 포함) | 1065 ms | 2026-11-26 1건, 리깅 36 joints·클립 5개 | [실험 결과] | [docs/ops/data/2026-11-26_pod-log-check/README.md:44](../ops/data/2026-11-26_pod-log-check/README.md) |
| `convertMs` (리깅 포함) | 963~1,209 ms | 2026-11-25 리허설 9건, 모두 `+rig`, 사진 1장. 원값은 `job-runs.csv` 에만 있다 | [실험 결과], 정리한 실험 기록은 [계획] | [docs/METRICS.md:9](../METRICS.md) |
| 느린 작업 (METRICS 밖, 건수) | 539건 중 14건: `inferMs` 8.0~15.4 s 또는 `convertMs` 4.3~6.0 s | 2차. 원인(Pod CPU 경합·볼륨 지연)은 [가설] | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:75](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `gpuPeakMB` | 5,120~5,128 MB | 2차 전체 539건, torch 할당 최대 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| GPU 메모리 최대 (METRICS 밖, nvidia-smi 프로세스 전체) | 9,463 MiB(대기 중 1,281~1,291 MiB) | 2차. 16 GB GPU 를 볼 때는 이 값을 기준으로 한다(원문) | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:76](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| GPU 메모리 최대 (METRICS 밖, nvidia-smi) | 9,453~9,467 MiB | 4차 모든 실행, `memory.total` 46,068 MiB | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:107·385](../perf/load/2026-11-20_vm-4th.md) |
| `modelParams` | 153,833,642 | 체크포인트 `latest.pt` 2,317,900,684 B. exp-01·2차 539건·2026-11-25 리허설 9건에서 같음. 3~5차·2026-11-26 기록에는 값이 없다 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:7·35·72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/ops/data/2026-11-25_demo-gpu-rehearsal/job-runs.csv:2-10](../ops/data/2026-11-25_demo-gpu-rehearsal/job-runs.csv) |
| `outputVertices` / `outputTriangles` | 4,310 / 7,774 | 2차 539건 모두 같음(같은 사진). 운영 GLB 3종도 같음 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [generation/experiments/2026-11-27_glb-validator.md:36](../../generation/experiments/2026-11-27_glb-validator.md) |
| 3D 품질 6항목(`faceShape`·`ears`·`bodyProportion`·`furColor`·`pattern`·`textureArtifacts`) | 0건 | 평가자 2명 이상 채점 전 | [계획] | [docs/METRICS.md:9](../METRICS.md) |

### 2.2 GLB

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| `glbBytes` (정적) | 1,326,748 B | 2차 539건 모두 | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:72](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| `glbBytes` (털 맵) | 1,923,996 B | 5차 모든 작업, 리깅 없음 | [실험 결과] | [docs/perf/load/2026-11-21_vm-5th.md:84·116](../perf/load/2026-11-21_vm-5th.md), [docs/METRICS.md:9](../METRICS.md) |
| `glbBytes` (리깅 + 털 맵 + 클립 5개) | 2,100,924 B | 2026-11-25 리허설 9건, 2026-11-26 1건 | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:34](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:44](../ops/data/2026-11-26_pod-log-check/README.md) |
| `glbBytes` (검사한 3개, 파일 크기) | 정적 1,170,480 B / 털 맵 1,760,968 B / 리깅 2,100,924 B | 운영 VM 결과 `d798b743`(11-19)·`dd7af771`(11-21)·`35b357d7`(11-26), 종류마다 1개. 정적·털 맵 파일의 입력 사진은 기록에 없다 [미확인]. 리깅 `35b357d7` 의 입력은 `dog.jpg` 다 | [실험 결과] | [generation/experiments/2026-11-27_glb-validator.md:9·23-25](../../generation/experiments/2026-11-27_glb-validator.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:19](../ops/data/2026-11-26_pod-log-check/README.md) |
| gltf-validator 오류 / 경고 (METRICS 밖) | 0 / 0(세 파일 모두) | gltf-validator 2.0.0-dev.3.10, 2026-11-27 KST(2026-11-26 UTC) | [실험 결과] | [generation/experiments/2026-11-27_glb-validator.md:5·8·33](../../generation/experiments/2026-11-27_glb-validator.md) |
| GLB_SPEC 검사 (METRICS 밖) | 세 파일 모두 통과(파일 ≤ 10 MB, 삼각형 ≤ 20,000, 텍스처 ≤ 2048² 등) | 상한은 [제안값] | [실험 결과] | [generation/experiments/2026-11-27_glb-validator.md:41-48](../../generation/experiments/2026-11-27_glb-validator.md) |
| 텍스처 / 애니메이션 (METRICS 밖) | 몸 1024²(셋 모두. 리깅본은 `uv` 1024²), 털 맵 2장 512²(털 맵·리깅본만) / 리깅본만 5개(Idle·Walk·TailWag·HeadTilt·Sit)와 스킨 | 검사한 3개 | [실험 결과] | [generation/experiments/2026-11-27_glb-validator.md:37-38](../../generation/experiments/2026-11-27_glb-validator.md) |

Unity glTFast 로드는 이 검사에 없다 [계획] ([generation/experiments/2026-11-27_glb-validator.md:55](../../generation/experiments/2026-11-27_glb-validator.md)).

### 2.3 한 건의 시간 나누기와 처리 용량

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| `processingMs` 중앙값 (평균) | 8.09 (8.05) s | 4차 `g0d4`, 30초마다 1건, 15분, 31건, c=1, 정적 GLB(1,326,748 B), ping 269 ms, 2026-11-19 UTC | [실험 결과] | [docs/perf/load/2026-11-20_vm-4th.md:12·106·112·121·127-128](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:116](../perf/load/2026-11-21_vm-5th.md) |
| 전송 등 = `processingMs` − `inferMs` − `convertMs`, 중앙값 (평균) | 3.64 (3.64) s | 같은 31건 | [실험 결과·계산] | [docs/perf/load/2026-11-20_vm-4th.md:131](../perf/load/2026-11-20_vm-4th.md) |
| Pod 요청별 중앙값 (METRICS 밖): 업로드 받기 / Pod 일 / GLB 구간 | 1.355 / 4.542 / 1.746 s | 같은 31건, Pod 요청 시간 줄. GLB 구간은 VM·Pod 시계가 섞였다(시계 차는 재지 않음) | [실험 결과·계산] | [docs/perf/load/2026-11-20_vm-4th.md:171·172·176](../perf/load/2026-11-20_vm-4th.md) |
| `processingMs` 중앙값, keep-alive 5 s → 75 s | 9.09 → 8.22 s(−0.88 s, −9.6 %) | 3차 `g0ka5` 대 `g0ka75`, 같은 Pod `4vh9x6broleb2z`, 30초마다 1건, 깨끗한 작업 26·28건, 2026-11-18 UTC | [계산] | [docs/perf/load/2026-11-19_vm-3rd.md:11·46·110·124·131](../perf/load/2026-11-19_vm-3rd.md) |
| 트레이스 span 길이 (METRICS 밖, Tempo): `POST /infer/upload` / `GET .../base.glb` / `beside.job.cutout` | 12,878.8 / 2,164.3 / 3,616.8 ms | 2026-11-26 1건, 리깅, 첫 추론. Pod 가 GLB 를 넘기는 데는 23.7 ms, 나머지 약 2.1 s 는 경로 몫 | [실험 결과] | [docs/ops/data/2026-11-26_pod-log-check/README.md:60·63·64·72](../ops/data/2026-11-26_pod-log-check/README.md) |
| S_busy → 처리 용량 (METRICS 밖, c=1) | 8.81 s(연속 처리 간격 157번) → 409건/h. 9.06 s(138번) → 397건/h | 2차 `g3r165` 과부하(ρ 1.19) / `g3r13`(과부하 안 됨, ρ 0.91), keep-alive 5 s, 2026-11-18 | [실험 결과·계산] | [docs/perf/load/2026-11-18_vm-2nd.md:42·152-154·156-158·162-163](../perf/load/2026-11-18_vm-2nd.md) |
| S_busy → 처리 용량 (METRICS 밖, c=1) | 8.67 s → 415건/h | 4차 `g3c1`, 157건, 정적 GLB(1,326,748 B) | [실험 결과·계산] | [docs/perf/load/2026-11-20_vm-4th.md:206·223](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:43·116](../perf/load/2026-11-21_vm-5th.md), [g3c1 events.jsonl `glbBytes` 189건](../perf/load/data/2026-11-20_0256-g3c1/attachments/events.jsonl) |
| S_busy → 처리 용량 (METRICS 밖, c=1) | 8.81 s (8.8083, 344/345) → 409건/h | 5차 `g3cap0`, 털 맵 GLB, 과부하 40분 | [실험 결과·계산] | [docs/perf/load/2026-11-21_vm-5th.md:6·84·139·146](../perf/load/2026-11-21_vm-5th.md) |
| 처리 용량 (METRICS 밖, c=2) 센 하한 | 495건/h(173건 ÷ 1,258 s), c=1 의 1.19배 이상 | 4차 `g2c2t`, 같은 Pod. 넘치지 않아 위쪽은 못 봤다 | [계산] | [docs/perf/load/2026-11-20_vm-4th.md:63·227](../perf/load/2026-11-20_vm-4th.md) |
| GPU 사용률 평균 (METRICS 밖, nvidia-smi 5 s) | 22.7 % | 2차 과부하 재실행 `g3r165`, c=1 | [실험 결과] | [docs/perf/load/2026-11-18_vm-2nd.md:36·156-158](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-20_vm-4th.md:204·214](../perf/load/2026-11-20_vm-4th.md) |
| `MODEL_LOCK` 점유 / GPU 사용률 평균 (METRICS 밖) | 41.8 % / 24.3 % | 4차 `g3c1`. 41.8 % 는 가장 긴 포화 구간 583 s 안의 값이다. k6 구간 전체로는 40.5 % [계산] | [실험 결과·계산] | [docs/perf/load/2026-11-20_vm-4th.md:213-214·217](../perf/load/2026-11-20_vm-4th.md) |
| T3(3,600건/h) 에 필요한 A40 (METRICS 밖) | 슬롯 1개 13대, 슬롯 2개(센 하한) 11대 | ρ ≤ 0.7 규칙, 서울 VM ↔ 스웨덴 Pod, 이 사진 | [계산] | [docs/perf/load/2026-11-20_vm-4th.md:395·397](../perf/load/2026-11-20_vm-4th.md) |

### 2.4 Pod 기동

| 지표(METRICS 이름) | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| 모델 로드 시간 (METRICS 밖, Pod 로그) | 31.3 s / 73.2 s / 56.1 s / 70.5 s / 44.3 s | exp-01 첫 Pod / 2차 새 Pod / 3차 / 4차 / 2026-11-26 로그 확인, 각 1회. exp-01 은 설치 직후 같은 호스트의 첫 실행이다(SD VAE·DINOv2 를 받음). 2차 73.2 s 는 새 호스트라 볼륨 파일 읽기가 캐시되지 않아 느렸다고 본다 [가설] | [실험 결과] | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:38·64](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-18_vm-2nd.md:63](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-20_vm-4th.md:109](../perf/load/2026-11-20_vm-4th.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:30](../ops/data/2026-11-26_pod-log-check/README.md) |
| Pod 생성 → VM 이 UP 으로 봄 (METRICS 밖) | 89.6 s / 68.1 s | 2026-11-25 리허설 / 2026-11-26 로그 확인, 각 1회. 표본 둘이라 차이를 해석하지 않는다(원문) | [실험 결과] | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:23](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:31·36](../ops/data/2026-11-26_pod-log-check/README.md) |

### 2.5 참고: 모델 비교 (`inferMs`·`gpuPeakMB` 와 정의가 다름)

RTX 4060 Ti 8GB(WSL2 Ubuntu), 사진 10장 × 5 = 50장, 1024 px, 배치 1, 3라운드, 2026-11-04. 시간은 (N장 실행 − 1장 실행) ÷ (N − 1)이고 모델 로딩을 뺀다. VRAM 은 nvidia-smi 최대 − 실행 직전 값이라 PyTorch 캐시가 들어 있다. 측정 스크립트와 원본 결과는 리포 밖에 있다. 출처 [generation/experiments/2026-11-04_model-comparison.md:22-26·54](../../generation/experiments/2026-11-04_model-comparison.md).

| 모델 | 1장당 생성 시간 (중앙값) | 최대 VRAM 점유 | 삼각형 수 | 상태 | 출처 |
|---|---|---|---|---|---|
| AnimalLift | 5.31 s | 7,593 MB | 7,774(고정) | [실험 결과] | [generation/experiments/2026-11-04_model-comparison.md:32](../../generation/experiments/2026-11-04_model-comparison.md) |
| SF3D | 0.67 s | 7,313 MB | 20,042(중앙값) | [실험 결과] | [generation/experiments/2026-11-04_model-comparison.md:33](../../generation/experiments/2026-11-04_model-comparison.md) |
| TripoSR | 1.63 s | 5,699 MB | 103,542(중앙값) | [실험 결과] | [generation/experiments/2026-11-04_model-comparison.md:34](../../generation/experiments/2026-11-04_model-comparison.md) |

## 측정표 3 — 앱(기기)

**METRICS §3 앱 실기기 측정값(`metrics.csv`)은 0건이다.** 털 렌더링 FurLab 의 실기기 측정은 3.1 에 따로 있다(S11 결정 전). 앱 `metrics.csv` 기록 코드는 [구현됨]이지만 실기기에서 모은 줄이 리포에 없다([docs/METRICS.md:96](../METRICS.md)). 이유는 셋이다.

- main 을 Unity 6 에디터에서 컴파일·Play 하고 APK 를 만드는 일(M6)이 아직이다. 모든 앱 측정의 선행 조건이다([docs/CHECKLIST.md:29](../CHECKLIST.md)).
- 기기 확인(M7~M9, C9)은 Android 기기가 있어야 하는 일이다([docs/CHECKLIST.md:30-32·68](../CHECKLIST.md)). 2026-11-27 현재 작업자 주변에 Android 기기가 없다(사용자 확인).
- 에디터 FPS 는 기기와 다르므로 측정표는 기기 값으로 만든다([unity/README.md:295](../../unity/README.md)). 그래서 에디터 값으로 이 표를 채우지 않는다.

| 지표(METRICS 이름) | 값 | 조건(정의) | 상태 | 출처 |
|---|---|---|---|---|
| `uploadMs` (앱) | 미측정 [계획] | `POST` 요청 시작 → 202 수신. 서버 `uploadMs` 와 다른 값이다(e2e.csv 의 서버 것은 `serverUploadMs`) | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:104·130](../METRICS.md) |
| `waitMs` | 미측정 [계획] | 202 수신 → COMPLETED/FAILED 를 처음 봄 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:105](../METRICS.md) |
| `downloadMs` | 미측정 [계획] | asset `GET` 시작 → 저장 완료. 지금 코드는 캐시 적중 시 0 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:106·115](../METRICS.md) |
| `loadMs` | 미측정 [계획] | glTFast `Load()` 시작 → 씬 인스턴스화 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:107](../METRICS.md) |
| `e2eMs` | 미측정 [계획] | 업로드 시작 → 모델이 보임. 지금 코드는 첫 AR 배치 때 끝나 평면 찾기·탭 대기가 들어간다 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:108·116](../METRICS.md) |
| `avgFps`, `minFps` | 미측정 [계획]. 목표 `avgFps` ≥ 30 은 [제안값] | 배치 후 30초. 30초 전에 앱이 끝나면 줄이 남지 않는다 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:109·117](../METRICS.md), [docs/PLAN.md:96](../PLAN.md) |
| `memMB` | 미측정 [계획] | 배치 후 `Profiler.GetTotalAllocatedMemoryLong()` 을 MB 로 | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:110](../METRICS.md) |
| `device` | 미측정 [계획]. 기기 2종 이상 요구 | `SystemInfo.deviceModel` | 기록 코드 [구현됨], 측정 0건 | [docs/METRICS.md:111](../METRICS.md), [docs/CHECKLIST.md:141](../CHECKLIST.md) |
| e2e 결합표 (`GET /api/v1/admin/e2e?format=csv`) | 실제 폰 파일로 돌린 적 없음 [계획] | 앱 한 행 + 그 jobId 의 마지막 COMPLETED `job_runs` | [구현됨] (`ClientMetricsApiTest`) | [docs/METRICS.md:7·120·130](../METRICS.md) |
| 제품 이벤트 (§6, `model_ready` 의 `loadMs`·`waitMs` 등) | 에디터·기기에서 보낸 실제 이벤트 0건 [계획] | 서버 수집은 운영 배포됨. Unity 는 Roslyn 컴파일만 했다 | [구현됨], 기기 확인 [계획] | [docs/METRICS.md:328](../METRICS.md), [docs/api/CHANGELOG.md:97](../api/CHANGELOG.md) |
| 실기기 전체 흐름 영상·logcat | 0개 [계획] | VM + Pod | — | [docs/CHECKLIST.md:31](../CHECKLIST.md) |

### 3.1 참고: 털 렌더링 측정 (FurLab, S11 결정 전)

이 절은 METRICS §3 의 앱 측정이 아니다. 읽기 전에 볼 것:
- `unity/experiments` 는 루트 CLAUDE.md 의 [실험 결과] 정의에 아직 없다. 넣을지는 사용자 결정 전이다(CHECKLIST S11, [docs/CHECKLIST.md:55](../CHECKLIST.md)).
- 연구용 프로젝트·브랜치 `Unity` 4e6f190 코드로 쟀다. main 에 합친 코드로는 다시 재지 않았다([unity/experiments/2026-11-03_fur-benchmark.md:8](../../unity/experiments/2026-11-03_fur-benchmark.md)).
- 기기는 플래그십 1대뿐이다([unity/experiments/2026-11-03_fur-benchmark.md:121](../../unity/experiments/2026-11-03_fur-benchmark.md)).
- `msPerPet` 은 FurLab 전용 지표다(METRICS 밖). FurLab 의 FPS 는 30 FPS 고정을 풀고 펫을 K배 겹쳐 그린 상태라 METRICS `avgFps` 로 쓸 수 없다.

공통 조건: Samsung SM-S947N(Xclipse 960), OpenGLES3(ANGLE on Vulkan), 1440×3120 @ 60 Hz, Unity 6000.6.0f1, URP MSAA 4x(조건별 변경), AnimalLift 결과 1개(정점 4,310 / 삼각형 7,774), Idle, 거치대 고정, 조건당 3라운드(두 묶음 공통 조건은 3~6회) 중앙값, 2026-11-03(1~3차 시험 측정은 2026-11-02)([unity/experiments/2026-11-03_fur-benchmark.md:5·9·28-35](../../unity/experiments/2026-11-03_fur-benchmark.md)).

| 지표 | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| `msPerPet`, 앱 기본값(쉘 20겹 + 자동 조절) | 가까이 13.54 ms / 멀리 4.15 ms | 그 조건의 화면 비율 가까이 87 %, 멀리 22 % | [실험 결과] — S11 결정 전 | [unity/experiments/2026-11-03_fur-benchmark.md:64·82](../../unity/experiments/2026-11-03_fur-benchmark.md) |
| 쉘 1겹당 `msPerPet` | 가까이 1.335 ms(절편 1.76 ms) / 멀리 0.175 ms(절편 0.42 ms) | 쉘 8·16·24겹 중앙값으로 직선 맞춤 | [계산] | [unity/experiments/2026-11-03_fur-benchmark/data/unit_costs.md:6·16](../../unity/experiments/2026-11-03_fur-benchmark/data/unit_costs.md), [scripts/analyze_fur_bench.py:223-225](../../scripts/analyze_fur_bench.py) |
| 30 FPS 예산 안 최대 쉘 수 | 가까이 약 18겹 / 멀리 약 150겹 | 예산 26.7 ms(= 1000/30 × 0.8) | [계산] | [unity/experiments/2026-11-03_fur-benchmark/data/unit_costs.md:3·6·16](../../unity/experiments/2026-11-03_fur-benchmark/data/unit_costs.md) |
| 최적화 효과(가까이, 같은 라운드 비율 중앙값) | 쉘 셰이더 v1→v2 −16 %, 쉘 수 자동 조절 −45 %, MSAA 4x→2x −32 % | 쉘16 또는 쉘20, 3라운드 | [실험 결과] — S11 결정 전 | [unity/experiments/2026-11-03_fur-benchmark.md:91-93](../../unity/experiments/2026-11-03_fur-benchmark.md) |
| 라운드 간 흔들림 | 절대값 편차 평균 ±19 %, 1라운드가 20~30 % 빠름(발열 전) | 그래서 결론은 비율로 낸다 | [실험 결과] — S11 결정 전 | [unity/experiments/2026-11-03_fur-benchmark.md:47](../../unity/experiments/2026-11-03_fur-benchmark.md) |

## 운영·신뢰성 (참고)

장애 훈련은 운영 VM 위의 훈련 backend 와 훈련 스텁 또는 실제 A40 Pod 로 했다. 외부 끊김은 Runpod CPU Pod(US-CA-2)에서 1초마다 healthz 를 보낸 탐침으로 쟀다. 모두 1회다.

| 항목 | 값 | 조건 | 상태 | 출처 |
|---|---|---|---|---|
| FD1 backend `kill -9` | 0.36 s 뒤 컨테이너 재시작, 19.1 s 뒤 healthz 200, 외부 끊김 17.8 s(502) | 2026-11-20 1차. 외부 끊김은 [계산]. 서버 쪽 시각(0.36 s·19.1 s)은 `vm/timeline.txt` 의 kill·started·healthz 줄에서 나온다 | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:68·72](../ops/2026-11-20_failure-drills.md), [docs/ops/data/2026-11-20_failure-drills/vm/timeline.txt:6-8](../ops/data/2026-11-20_failure-drills/vm/timeline.txt) |
| FD2 backend 멈춤(SIGSTOP) | 대응 전 외부 응답 없음 189 s·재시작 없음(SIGSTOP 180 s 뒤 걸어 둔 SIGCONT 로만 풀림) → 대응 뒤 멈춤에서 컨테이너 재시작까지 112 s(VM 감시). 정상까지는 재지 못함(약 2분 [계산]) | 1차 / 2차(main `ce1a685` 배포 뒤), 추론은 훈련 스텁. 189 s·112 s 는 [계산] | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:37·68·73·111·118·123](../ops/2026-11-20_failure-drills.md) |
| FD7 Caddy `kill -9` | 복구 1.4 s, 외부 끊김 2.0 s | 1차. 외부 끊김은 [계산] | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:68·74](../ops/2026-11-20_failure-drills.md) |
| FD17 추론 토큰 불일치 | 대응 전 GPU 상태 UP 그대로·작업마다 실패 웹훅뿐(8건 모두 `INFERENCE_UNAVAILABLE`) → 대응 뒤 9.8 s 에 🔑 알림(스텁 재시작 포함) | 1차 / 2차, 훈련 스텁. 1차 웹훅은 블랙홀 주소. 작업 결과(8건)는 [계산] | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:51·68·75·116](../ops/2026-11-20_failure-drills.md) |
| FD13 healthz 는 UP, 추론만 멈춤 | 대응 전 감지 없음 → 대응 뒤 느린 추론 알림 '2분 14초째 끝나지 않아요 (기준 2분 0초)' | 1차 / 2차, 훈련 스텁 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:76·117](../ops/2026-11-20_failure-drills.md) |
| FD12 추론 서비스 응답 없음 | 12.2 s 뒤 DOWN | 1차, 훈련 스텁(pause 260 s), 오프라인 대기를 180 s 로 줄임 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:52·77](../ops/2026-11-20_failure-drills.md) |
| FD10 VM→Pod tailnet 120 s 차단 | 14.3 s 뒤 DOWN, 해제 8 s 뒤 UP, 11건 모두 완료 | 1차, 실제 A40. 작업 결과(11건)는 [계산] | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:68·82](../ops/2026-11-20_failure-drills.md) |
| FD16a 멈춘 Pod 다시 켜기 실패 | 새 Pod 로 98 s 뒤 GPU UP | 1차 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:81](../ops/2026-11-20_failure-drills.md) |
| FD16b 부하 중 Pod 정지 | 1차 대기 최대 602 s → 고침 뒤 5차 74.3 s(끊긴 1건은 52 s 에 `INFERENCE_UNAVAILABLE`) | 1차 2026-11-20, 운영 값(시간 초과 600 s), 46건 완료(작업 결과는 [계산]) / 5차 2026-11-21, 훈련 backend·시간 초과 120 s, 기다린 5건, `instanceId` 길만 봄. 둘 다 실제 A40 | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:36·53·68·87](../ops/2026-11-20_failure-drills.md), [docs/perf/load/2026-11-21_vm-5th.md:36·263](../perf/load/2026-11-21_vm-5th.md) |
| R7 Pod 추론 서비스 `kill -9` | 41 s 에 UP, 사람 손 없음 | 2차, 실제 A40. Pod·VM 두 시계가 섞인 값 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:119·154](../ops/2026-11-20_failure-drills.md) |
| FD18 모델 로드 실패 → 바르게 다시 띄움 | UP 까지 52.6 s(1차) → 40 s(2차) | 실제 A40. 2차 값은 두 시계가 섞였다 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:85·120·154](../ops/2026-11-20_failure-drills.md) |
| F2-real 추론 서비스 `kill -9`(부하 중) | kill 뒤 UP 146.7 s(그중 90 s 는 일부러 둔 대기), 52건 모두 마지막 시도 COMPLETED | 3차, 2026-11-18 UTC, 실제 A40, ρ0.5. kill 90 s 뒤 스크립트로 재시작(감독 루프 없음), 모델 로드 44.1 s | [실험 결과] | [docs/perf/load/2026-11-19_vm-3rd.md:37·245·253-256](../perf/load/2026-11-19_vm-3rd.md), [docs/ops/2026-11-20_failure-drills.md:119](../ops/2026-11-20_failure-drills.md) |
| FD3 VM stop → 서비스 계정 교체 → start | 외부 끊김 82.8 s | 2026-11-20, 정지 54 s 포함, 1회. 외부 끊김은 [계산] | [실험 결과·계산] | [docs/ops/2026-11-20_failure-drills.md:68·88](../ops/2026-11-20_failure-drills.md) |
| FD6 VM 통째 유실 → 스냅샷으로 다른 zone | 서비스 응답 89 s, 운영 설정으로 145 s(수동 한 단계) | 2026-11-20, 훈련 VM | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:89](../ops/2026-11-20_failure-drills.md) |
| FD5 DB 파일 유실 | 1차 감지 없음, 복구 20.2 s, RPO = 백업 간격 → 5차 시작 거부, 44.6 s 뒤 알림, 복구 14.8 s | 1차 2026-11-20 / 5차 2026-11-21, 훈련 backend, 2분 백업. 5차는 Pod 없이 훈련 스텁, 알림은 따로 띄운 감시 인스턴스가 훈련 수집기로 보냄 | [실험 결과] | [docs/ops/2026-11-20_failure-drills.md:79·141](../ops/2026-11-20_failure-drills.md), [docs/perf/load/2026-11-21_vm-5th.md:39·233·311](../perf/load/2026-11-21_vm-5th.md) |
| S1 과부하 첫 감지 | 1분 42초. 큐 상한 19 를 켜 같은 18,000명에서 폴링 p95 1,769 → 206 ms | 2026-11-25 UTC 스테이징 복제본, `mock,prod`, A1 18,000명 | [실험 결과] | [docs/ops/2026-11-26_staging-drills.md:27-28](../ops/2026-11-26_staging-drills.md) |
| S2 backend 앞 지연 감지 | 옛 규칙 감지 없음 → 새 앞단 응답 p95 알림 3분 35초 | 2026-11-25 UTC 스테이징, A1 9,000명 + 지연 500 ±100 ms | [실험 결과] | [docs/ops/2026-11-26_staging-drills.md:27·72](../ops/2026-11-26_staging-drills.md) |
| D-1 / D-3 계획 전환 외부 끊김 | 65.0~68.1 s / 63.0~64.1 s, 공개 경로 63/63 | 2026-11-26 UTC, 운영 ↔ 대기 VM, GPU 없이 healthz 만, 방향마다 1회. 범위는 같은 1회를 보낸 시각·받은 시각 두 기준으로 잰 차이다 | [실험 결과] | [docs/ops/2026-11-26_failover-drills.md:7·9·17·21-23·33·37·45-48](../ops/2026-11-26_failover-drills.md) |
| 계획 전환 RPO | 0(표지 작업 A·B·A2·B2 모두 남음) | 같음 | [실험 결과] | [docs/ops/2026-11-26_failover-drills.md:85](../ops/2026-11-26_failover-drills.md) |
| 운영 RPO (DB·파일) | 약 5~8분 | 2026-11-25 17:16 UTC 부터 5분 백업 + 3분 뒤 버킷 업로드. 백업 주기에서 나온 값이다. 이 운영 설정으로 표지 작업을 써서 잰 비계획 복구는 없다(FD5 는 훈련 backend·2분 백업) | [적용] | [docs/ops/REDUNDANCY.md:68](../ops/REDUNDANCY.md), [docs/ops/2026-11-20_failure-drills.md:79](../ops/2026-11-20_failure-drills.md) |
| 외부 가동 감시(R3) | 0개 | 2026-11-25 확인. 설치하지 않았다 | [계획] (미설치) | [docs/ops/2026-11-20_failure-drills.md:144-145](../ops/2026-11-20_failure-drills.md) |

## 환경

| 항목 | 값 | 출처 |
|---|---|---|
| 서버 VM | GCP e2-standard-2(2 vCPU, 8 GB + 스왑 2 GB, 디스크 48 GB), asia-northeast3-a(서울). 1~5차 같음 | [docs/perf/load/2026-11-18_vm-1st.md:33](../perf/load/2026-11-18_vm-1st.md), [docs/perf/load/2026-11-21_vm-5th.md:78](../perf/load/2026-11-21_vm-5th.md) |
| 서버 코드 (부하 시험) | 1~3차 VM 리포 `b6c0e6c`, 4차 backend 이미지 `3b12c7b`, 5차 main `fe10683`(`api-v1.1`) | [docs/perf/load/2026-11-18_vm-1st.md:34](../perf/load/2026-11-18_vm-1st.md), [docs/perf/load/2026-11-18_vm-2nd.md:60](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-19_vm-3rd.md:108](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:105](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:79](../perf/load/2026-11-21_vm-5th.md) |
| 앞단 | Caddy 2.11.7(TLS) → backend | [docs/perf/load/2026-11-18_vm-1st.md:36](../perf/load/2026-11-18_vm-1st.md) |
| GPU | Runpod A40 48GB, Secure, EU-SE-1, 드라이버 595.112.07(호스트 CUDA 13.2), 이미지 `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`, torch 2.4.0+cu124, 배치 1, `/workspace` 는 네트워크 볼륨 | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:8](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| 모델 | AnimalLift `5d364cf`, 체크포인트 `latest.pt`(2,317,900,684 B), `modelVersion` `animallift-baseline@5d364cf:latest`(리깅이 성공한 실행은 끝에 `+rig`) | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:7](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-18_vm-2nd.md:63](../perf/load/2026-11-18_vm-2nd.md), [docs/METRICS.md:9](../METRICS.md) |
| Pod (실행별) | exp-01 `pzcls7d82ffens`, 2차 `yq8zyoibhf15l5`, 3차 `4vh9x6broleb2z`, 4차 `nsz73mtl802mvw`, 장애 훈련 1차·2차 `03rnszh20mywif`(`beside-gpu5`), 5차 `jvytgcpcgahd1t`, 리허설 `j0g1iqr40i0gxk`, 로그 확인 `ul0b0fi6fgb7i6` | [docs/perf/load/2026-11-18_vm-2nd.md:62·64](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-19_vm-3rd.md:110](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:107](../perf/load/2026-11-20_vm-4th.md), [docs/ops/2026-11-20_failure-drills.md:54·111](../ops/2026-11-20_failure-drills.md), [docs/perf/load/2026-11-21_vm-5th.md:82](../perf/load/2026-11-21_vm-5th.md), [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md:8](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:13](../ops/data/2026-11-26_pod-log-check/README.md) |
| VM ↔ Pod 경로 | Tailscale. exp-01 은 DERP 중계(헬싱키, 왕복 약 300 ms). 2~5차는 직접 연결(`tailscale ping` 263~269 ms: 2차 268~269, 3차 263~264, 4차 269, 5차 267). 2026-11-26 은 직접 264 ms | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:16](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-18_vm-2nd.md:65](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-19_vm-3rd.md:115](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:112](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:85](../perf/load/2026-11-21_vm-5th.md), [docs/ops/data/2026-11-26_pod-log-check/README.md:18](../ops/data/2026-11-26_pod-log-check/README.md) |
| 부하 발생기 | Runpod CPU Pod `cpu3g`, US-CA-2, k6 v2.3.0. 1차 8 vCPU(RTT_base 153.6 ms), 2~5차 4 vCPU | [docs/perf/load/2026-11-18_vm-1st.md:40-41](../perf/load/2026-11-18_vm-1st.md), [docs/perf/load/2026-11-18_vm-2nd.md:67](../perf/load/2026-11-18_vm-2nd.md), [docs/perf/load/2026-11-19_vm-3rd.md:117](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:114](../perf/load/2026-11-20_vm-4th.md), [docs/perf/load/2026-11-21_vm-5th.md:87](../perf/load/2026-11-21_vm-5th.md) |
| 스테이징 훈련 | 대상 `beside-staging` e2-standard-2, 부하 발생기 `beside-loadgen` e2-standard-4, 같은 zone asia-northeast3-b, 왕복 4.49 ms | [docs/ops/2026-11-26_staging-drills.md:8-9](../ops/2026-11-26_staging-drills.md) |
| 장애·전환 탐침 | Runpod CPU Pod(US-CA-2), `drill_client.py`, 1초마다 healthz, 5 s 시간 초과. 장애 훈련은 블록 1 15 s·블록 2 20 s 마다 사진 작업도 보냈고, 전환 훈련은 healthz 만 보냈다(`--no-jobs`) | [docs/ops/2026-11-20_failure-drills.md:55](../ops/2026-11-20_failure-drills.md), [docs/ops/2026-11-26_failover-drills.md:13-14](../ops/2026-11-26_failover-drills.md) |
| 사진 | `dog.jpg` 169,451 B(CC0, 1마리). exp-01 은 `dog-single-black.jpg` 205,402 B, `dog-single-edge.jpg` 193,464 B 도 썼다 | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md:9](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-18_vm-2nd.md:66](../perf/load/2026-11-18_vm-2nd.md) |
| 털 측정 기기 | Samsung SM-S947N(Xclipse 960), OpenGLES3(ANGLE on Vulkan), Unity 6000.6.0f1 | [unity/experiments/2026-11-03_fur-benchmark.md:9·30](../../unity/experiments/2026-11-03_fur-benchmark.md) |
| 모델 비교 GPU | RTX 4060 Ti 8GB, WSL2 Ubuntu | [generation/experiments/2026-11-04_model-comparison.md:7](../../generation/experiments/2026-11-04_model-comparison.md) |

측정 날짜:

| 측정 | 날짜 | 기록 |
|---|---|---|
| 털 렌더링(FurLab) | 2026-11-03(1~3차 시험 측정 2026-11-02) | [unity/experiments/2026-11-03_fur-benchmark.md](../../unity/experiments/2026-11-03_fur-benchmark.md) |
| 모델 비교 | 2026-11-04 | [generation/experiments/2026-11-04_model-comparison.md](../../generation/experiments/2026-11-04_model-comparison.md) |
| exp-01 베이스라인 | 2026-11-17 | [generation/experiments/2026-11-17_exp-01_baseline-runpod.md](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) |
| 1차 A0~A4 (mock) | 2026-11-18 | [docs/perf/load/2026-11-18_vm-1st.md](../perf/load/2026-11-18_vm-1st.md) |
| 2차 G0r·G1r·G3r | 2026-11-18 | [docs/perf/load/2026-11-18_vm-2nd.md](../perf/load/2026-11-18_vm-2nd.md) |
| 3차 G0·G2r·F2·F1b·F1a | 2026-11-18 16:29~19:00 UTC | [docs/perf/load/2026-11-19_vm-3rd.md](../perf/load/2026-11-19_vm-3rd.md) |
| 4차 c=1·c=2 | 2026-11-19 UTC | [docs/perf/load/2026-11-20_vm-4th.md](../perf/load/2026-11-20_vm-4th.md) |
| 장애 훈련 1차·2차 | 2026-11-20 UTC | [docs/ops/2026-11-20_failure-drills.md](../ops/2026-11-20_failure-drills.md) |
| 5차 큐 상한 0 대 19, FD 다시 재현 | 2026-11-21 UTC | [docs/perf/load/2026-11-21_vm-5th.md](../perf/load/2026-11-21_vm-5th.md) |
| GPU 리허설, 스테이징 S1·S2 | 2026-11-25 (UTC) | [docs/ops/data/2026-11-25_demo-gpu-rehearsal/README.md](../ops/data/2026-11-25_demo-gpu-rehearsal/README.md), [docs/ops/2026-11-26_staging-drills.md](../ops/2026-11-26_staging-drills.md) |
| Pod 로그 확인, 계획 전환 D-1·D-3 | 2026-11-26 UTC(D-1·D-3 은 KST 11-27 00:43~01:06) | [docs/ops/data/2026-11-26_pod-log-check/README.md](../ops/data/2026-11-26_pod-log-check/README.md), [docs/ops/2026-11-26_failover-drills.md](../ops/2026-11-26_failover-drills.md) |
| GLB gltf-validator | 2026-11-27 KST(UTC 11-26 17:4x) | [generation/experiments/2026-11-27_glb-validator.md](../../generation/experiments/2026-11-27_glb-validator.md) |

## 빈칸과 다음 측정 [계획]

앱(기기):
- 앱 `metrics.csv` 0건. 순서는 M6(에디터 컴파일·Play·APK) → M9(10회 → `/admin` 올리기) → C9(기기 2종 FPS)다([docs/CHECKLIST.md:29·32·68](../CHECKLIST.md)).
- e2e 결합표를 실제 폰 파일로 돌리기([docs/METRICS.md:7](../METRICS.md)).
- 제품 이벤트의 실제 수치 0건([docs/METRICS.md:328](../METRICS.md)). 기기 없이 문서에 적힌 길은 둘이다: Unity 에디터 Play 도 platform `android` 로 보내고, 공유 뷰어 `/s/{token}` 는 platform `web` 으로 보낸다([docs/METRICS.md:276](../METRICS.md)). 에디터에서 `metrics.csv` 가 써지는지는 METRICS 에 적혀 있지 않다. 에디터 값은 기기 측정표에 쓰지 않는다.
- 서버 GLB(리깅 + 털 맵)를 기기에서 보고 `BESIDE_EMBED_HAIR` 정하기(M7), Unity glTFast 로드 확인([docs/CHECKLIST.md:30](../CHECKLIST.md), [generation/experiments/2026-11-27_glb-validator.md:55](../../generation/experiments/2026-11-27_glb-validator.md)).
- 털 성능을 main 코드로 다시 재기, 중저가 기기, 털 화질 블라인드 평가([docs/CHECKLIST.md:68](../CHECKLIST.md), [unity/experiments/2026-11-03_fur-benchmark.md:121·123](../../unity/experiments/2026-11-03_fur-benchmark.md)). 털 수치를 인용하려면 S11 결정이 먼저다.

추론:
- A40 서비스 경로(exp-01·부하 시험 2~5차·리허설·2026-11-26 로그 확인)의 GPU 수치는 사진 1장 반복이다(exp-01 의 VM 경유 3장 제외). 서버·추론 경로에서 사진 10장 이상을 잰 것과 3D 품질 6항목 채점은 0건이다([docs/METRICS.md:9](../METRICS.md)). RTX 4060 Ti 조사 실험(2026-11-04·11-05)은 사진 10장 이상을 썼지만 A40 서비스 경로가 아니다. 모델 비교의 시간·VRAM 은 정의도 다르다(2.5, [generation/experiments/2026-11-04_model-comparison.md:7·24·26](../../generation/experiments/2026-11-04_model-comparison.md), [generation/experiments/2026-11-05_animallift-consistency.md:7](../../generation/experiments/2026-11-05_animallift-consistency.md), [generation/experiments/2026-11-05_animallift-robustness.md:7](../../generation/experiments/2026-11-05_animallift-robustness.md)).
- 리깅을 켠 실행(`+rig`)의 `inferMs`·`convertMs` 를 정리한 실험 기록이 없다. 부하 시험 2~5차는 모두 리깅 없음이다([docs/METRICS.md:9·92](../METRICS.md)).
- 16 GB GPU 는 재지 않았다. `gpuPeakMB` 5,120 MB 와 nvidia-smi 9,463 MiB 가 다르다([generation/experiments/2026-11-17_exp-01_baseline-runpod.md:76](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md)).
- 첫 추론이 느린 것(예: exp-01 5,728 ms, 3차 6.33~6.97 s)을 데움으로 보는 설명은 [가설]이고, 따로 잰 실험은 없다([generation/experiments/2026-11-17_exp-01_baseline-runpod.md:32](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md), [docs/perf/load/2026-11-19_vm-3rd.md:408](../perf/load/2026-11-19_vm-3rd.md), [docs/perf/load/2026-11-20_vm-4th.md:123](../perf/load/2026-11-20_vm-4th.md)). VM·Pod 시계 차도 재지 않았다([docs/perf/load/2026-11-21_vm-5th.md:367](../perf/load/2026-11-21_vm-5th.md)).
- 넘치는 c=2 실행. 지금은 센 하한 495건/h 만 있다([docs/perf/load/2026-11-20_vm-4th.md:227](../perf/load/2026-11-20_vm-4th.md)).

서버:
- 모든 부하 시험은 1회다. TRAFFIC_MODEL §9 의 3회 반복을 하지 않았다([docs/perf/load/2026-11-18_vm-1st.md:305](../perf/load/2026-11-18_vm-1st.md)).
- A1 다시 재기. `BROWSE_PRE_VUS` 는 PR #9 로 들어갔고 VM 에서 다시 돌리지 않았다([docs/perf/load/2026-11-18_vm-1st.md:88](../perf/load/2026-11-18_vm-1st.md)).
- 한국·일본 리전 부하 발생기로 다시 재기([docs/perf/load/2026-11-18_vm-1st.md:26·313](../perf/load/2026-11-18_vm-1st.md)).
- 실제 GPU 대기열과 수천 명 폴링을 한 VM 에서 함께 건 시험은 없다. A 시리즈(API)는 mock 워커로 했고, G 시리즈(GPU 대기열)는 따로 했다([docs/perf/load/2026-11-18_vm-1st.md:303](../perf/load/2026-11-18_vm-1st.md), [docs/perf/load/TRAFFIC_MODEL.md:22-23](../perf/load/TRAFFIC_MODEL.md)). 스테이징 S1 도 `mock,prod` 였다([docs/ops/2026-11-26_staging-drills.md:13](../ops/2026-11-26_staging-drills.md)).
- 운영 서버의 큐 상한은 꺼져 있다. `QUEUE_FULL` 을 아는 Unity 새 빌드를 깐 뒤 켠다([docs/perf/load/2026-11-20_queue-cap.md:158](../perf/load/2026-11-20_queue-cap.md)). 실제 GPU 대기열(5차)은 `Retry-After` 167 s 로 시험했고 결정값 165 s 로는 돌리지 않았다([docs/perf/load/2026-11-21_vm-5th.md:366](../perf/load/2026-11-21_vm-5th.md)). 165 s 로 켠 것은 스테이징 mock S1 s1b(2026-11-25 UTC)다([docs/ops/2026-11-26_staging-drills.md:56](../ops/2026-11-26_staging-drills.md)).
- 멘토 지표 5종 PromQL 을 실제 Grafana 화면에서 확인하기([docs/METRICS.md:268](../METRICS.md)).

운영:
- 계획 전환은 GPU 없이 healthz 만 쟀다. 진행 중 작업 retry 와 전환 뒤 COMPLETED 1건은 [계획] ([docs/ops/2026-11-26_failover-drills.md:24](../ops/2026-11-26_failover-drills.md)).
- 비계획 전환(D-2)의 RTO·RPO 는 재지 않았다([docs/ops/REDUNDANCY.md:153](../ops/REDUNDANCY.md)). 운영 RPO 약 5~8분은 설정값이다([docs/ops/REDUNDANCY.md:68](../ops/REDUNDANCY.md)).
- 외부 가동 감시(R3) 0개라 VM·Caddy 전체 장애의 바깥 감지 시간은 없다([docs/ops/2026-11-20_failure-drills.md:145](../ops/2026-11-20_failure-drills.md)).
