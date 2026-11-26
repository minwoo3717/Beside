# GPU Pod 의 Python 로그·작업 트레이스 확인 (2026-11-26)

배포 창 2(2026-11-26 00:08 UTC, main `a44b3ed`)에서 남은 두 가지를 실제 GPU Pod 로 확인했다.

- 추론 서비스 로그(B4): 지금까지 [구현됨, 배포 전]이었다. Pod 는 시작할 때 main 을 받으므로, Pod 를 켜야 운영에 들어간다.
- 작업 한 건의 트레이스(`beside.job.run`): 지금까지 GPU Pod 를 켠 뒤 [계획]이었다.

결과로 고친 문서는 맨 아래 '고친 문서' 에 있다. 작업은 1건, Pod 는 1대다. 수치는 모두 1회 [실험 결과]다.

## 조건

- 운영 VM: `beside-vm`, main `a44b3ed`, real,prod. backend 는 재시작하지 않았다. 시작 전 대기열은 0이었다.
- 새 Pod `ul0b0fi6fgb7i6`(이름 `beside-gpu-logcheck`)를 만들었다. 확인이 끝나고 멈춘 뒤 지웠다(`get-pod` 404).
  - A40 SECURE $0.59/h, EU-SE-1(같은 날 조회 재고 LOW).
  - 설정은 [2026-11-25 리허설](../2026-11-25_demo-gpu-rehearsal/README.md)과 같다: 볼륨 `z4kgw4v5zi`, `minCudaVersion 12.4`, `minVcpuCountPerGpu 4`, `minRamPerGpu 24`, Secret 토큰.
  - 시작 명령은 기존 자동 시작 명령(`beside-gpu7` 과 같음)이다. `checkout -q -B main origin/main` 으로 **`cb2bfc8`**(B4 `a44b3ed` 를 포함)을 받았다(`autostart-git.log`).
  - 서비스: `python -m uvicorn app:app --host 127.0.0.1 --port 8002`, uvicorn 0.54.0. 시작 명령에 값을 붙이지 않았는데 환경에 `BESIDE_REQUEST_TIMING=1` 이 있었다(`start_pod.sh` 의 새 기본값).
- VM ↔ Pod: `tailscale ping` 이 직접 연결(264 ms)이었다.
- 작업: 개발 PC 에서 공개 주소로 `TIMEOUT_SECONDS=120 bash scripts/e2e_mock.sh https://beside-team.duckdns.org backend/src/test/resources/photos/dog.jpg` 를 1번 돌렸다. 운영 DB 에 시험 작업 1건(`35b357d7-82b1-468d-9377-d2963e732fe0`)이 남는다.
- 실패 줄을 보려고 VM 에서 Pod 로 요청 4개를 더 보냈다(아래 '실패 응답'). 토큰이 필요한 두 요청은 VM `.env` 의 값을 셸 변수로만 썼고, 출력하지 않았다.

## 시간 (1회)

| 단계 | 시각(UTC) | 생성부터 |
|---|---|---|
| `create-pod` 응답(createdAt) | 08:06:15.641 | 0 |
| Pod: `git checkout` 끝(`autostart-git.log`) | 08:06:18 | 약 2 s |
| Pod: `start_pod.sh` 시작 | 08:06:24 | 약 8 s |
| VM: DOWN → LOADING | 08:06:53.135 | 37.5 s |
| Pod: `model loaded in 44.3s on cuda` | 08:07:15.988 | 60.3 s |
| VM: LOADING → UP(`instanceId` 6af91bde1f16) | 08:07:23.700 | 68.1 s |
| 작업 COMPLETED | 08:07:58.375 | — |
| Pod stop(가동 270 s) → delete | 08:10:4x | — |
| VM: UP → DOWN | 08:11:14.524 | — |

- 리허설(2026-11-25)은 LOADING 까지 43.8 s, UP 까지 89.6 s 였다. 표본이 둘뿐이라 차이를 해석하지 않는다.
- 비용: 가동 270 s × $0.59/h ≈ $0.04 [계산].

## 작업 한 건

- 클라이언트: e2e 20,394 ms(업로드 1,403, 대기 18,797, 내려받기 194). 응답 헤더 `X-Request-Id: 103aac8a-9f6b-47aa-aa96-4bd9f0bef1e8`.
- 서버 `timings`: `queuedMs` 4,251, `processingMs` 15,072, `totalMs` 19,323.
  - `queuedMs` 의 대부분은 크롭·배경 제거 3.6 s 다. 이 단계는 PENDING 동안 돌기 때문이다([METRICS.md](../../../METRICS.md) §1 `preprocessMs`).
- Pod: `infer 7030 ms, convert 1065 ms, 7774 tris, 2100924 bytes`, 리깅 36 joints·클립 5개. `modelVersion …+rig`.
- 모델을 올린 뒤 첫 추론이다. 리허설의 첫 작업도 `inferMs` 8,353 으로 그 뒤 작업보다 느렸다.

### 트레이스 (Tempo API, VM 안에서 `http://tempo:3200/api/traces/<id>`)

업로드 요청 트레이스 `87237f0b2e1bf866a70543ce9a3bc9a6`:

| 시작(ms) | 길이(ms) | span | 속성 |
|---:|---:|---|---|
| 0.0 | 1,221.8 | `http post /api/v1/jobs` | `beside.request_id` = `103aac8a…`(응답 헤더와 같음), status 202 |

작업 실행 트레이스 `14c049d0d0e0df86abf4c3cd78e274d0`(첫 span 시작 기준):

| 시작(ms) | 길이(ms) | span | 속성 |
|---:|---:|---|---|
| 0.0 | 19,317.6 | `beside.job.run` | `beside.dispatched.trace_id` = `87237f0b…`(업로드 요청 트레이스) |
| 3.1 | 3,616.8 | `beside.job.cutout` | |
| 3,620.0 | 4.9 | `beside.job.slot-wait` | |
| 3,625.0 | 273.7 | `beside.job.service-wait` | |
| 3,919.6 | 12,878.8 | `POST /infer/upload` | `beside.call` infer |
| 16,799.1 | 2,164.3 | `GET /artifacts/{jobId}/base.glb` | `beside.call` download |
| 18,963.7 | 8.9 | `beside.job.store` | |
| 19,021.9 | 295.7 | `DELETE /artifacts/{jobId}` | `beside.call` cleanup |

- span 의 부모 id 는 출력하지 않았다. 그래서 '나무 모양'이 아니라 '같은 트레이스 안의 span 과 시작 순서'까지만 확인했다. 단위 시험 `TracingPropagationTest` 가 나무 모양을 본다.
- Grafana 화면에서 이 트레이스를 열어 보지는 않았다(캡처 [계획]).
- 같은 요청을 양쪽에서 잰 값:
  - `POST /infer/upload`: Spring 12,879 ms. Pod 의 시간 줄은 본문을 받는 데 1,334 ms(609,137 B), 응답 시작까지 12,317 ms 였다.
  - GLB: Spring 2,164 ms. Pod 는 2,100,924 B 를 넘기는 데 23.7 ms(`end_ms - start_ms`)였다. 나머지 약 2.1 s 는 경로(tailscaled·tailnet·VM) 몫이다. [4차 부하 시험](../../../perf/load/2026-11-20_vm-4th.md)에서 본 것과 같은 방향이다.

### backend 로그(prod JSON, 필드 일부)

| 시각(UTC) | logger | `requestId` | `traceId` | 메시지(앞부분) |
|---|---|---|---|---|
| 08:07:39.051 | (업로드 요청) | `103aac8a…` | `87237f0b…` | photo check passed: dog 0.93 … |
| 08:07:39.379 | JobServiceImpl | `103aac8a…` | `87237f0b…` | Job created: 35b357d7… status=PENDING worker=real |
| 08:07:43.021 | CutoutStage | 없음 | `14c049d0…` | cutout … in 3579 ms |
| 08:07:43.319 | RealJobWorker | 없음 | `14c049d0…` | stage=PROCESSING POST http://100.83.86.62:8002/infer/upload traceId=14c049d0… |
| 08:07:58.409 | RealJobWorker | 없음 | `14c049d0…` | stage=COMPLETED durationMs=18995 … |

- 202 뒤 워커(`@Async`)가 남긴 줄에는 `requestId` 가 없다. 그 줄은 `jobId`(와 `attempt`)·`traceId` 로 찾는다([RUNBOOK](../../RUNBOOK.md) '먼저 볼 곳' 의 '작업 하나').

## Python 로그 (Pod `/workspace/beside-logs/inference.log`)

원문은 [inference-log.txt](inference-log.txt) 에 있다. `/healthz` 줄 28개는 뺐다.

- 작업의 서비스 줄 7개(`request` 3개, `rigged`, 결과, 업로드 모드, 폴더 정리) 끝에 모두 `rid=35b357d7-…-1 trace=14c049d0…` 가 붙었다. trace 는 Tempo 의 작업 트레이스 id 와 같다. 요청 3개의 `traceparent=` 는 span id 가 서로 다르다(`d4f6399b…`·`bc659425…`·`20b4b03a…`).
- 모델 로드 줄: `2026-11-26T08:07:15.988Z INFO beside.inference model loaded in 44.3s on cuda (…)`.
- 요청별 시간 줄(`timing …`)은 `/healthz`·401 을 포함해 요청마다 하나씩 남았다.
- **실패 응답** — VM 에서 보낸 요청 4개, 각각 WARNING 한 줄:

| 요청 | 상태 | WARNING 줄(앞부분) |
|---|---|---|
| `POST /infer?probe=1`, 토큰 없음, `X-Request-Id: logcheck-401` | 401 | `POST /infer: 401 INFERENCE_UNAVAILABLE missing X-Inference-Token rid=logcheck-401` |
| `GET /artifacts/nope/base.glb`, 토큰 없음, `X-Request-Id: bad id with spaces` | 401 | `GET /artifacts/nope/base.glb: 401 … missing X-Inference-Token`(형식이 틀린 id 라 `rid` 없음) |
| `GET /artifacts/0000…/base.glb`, 토큰, `X-Request-Id: logcheck-404`, 시험용 `traceparent` | 404 | `job 0000…: 404 base.glb not in the work folder (…) rid=logcheck-404 trace=0123…` |
| `POST /infer` 본문 `{"jobId":"x"}`, 토큰, `X-Request-Id: logcheck-422` | 422 | `POST /infer: 422 invalid request [{"loc": ["body", "imagePaths"], "msg": "Field required"}] rid=logcheck-422`(입력값은 없음) |

- 토큰: 서비스 프로세스 환경의 토큰 값(64자)이 이번 시작 뒤 로그에 0번 나왔다(Pod 안에서 `grep -cF`, 값은 출력하지 않음). 틀린 토큰은 보내 보지 않았다.
- 그 전 판(2026-11-25 리허설, 옛 코드)의 서비스 줄은 `job 9fa89a7b…: work folder deleted` 처럼 시각·수준·rid 없이 메시지뿐이었다.

### 문서와 달랐던 것

| 문서가 말한 것 | 실제 | 고친 곳 |
|---|---|---|
| 토큰이 지키는 경로의 작업 요청'마다' `request` 줄 | 401 이 된 요청에는 없다. 이 줄은 토큰 검사 안쪽에서 쓴다(`app.py` `_RequestContext`) | inference_service/README '요청 줄' |
| `rid=`·`trace=` 가 요청 동안의 '모든 줄'에 | 서비스 로거 줄에만 붙는다. 같은 요청의 uvicorn 접근 줄(상태 코드가 있는 줄)과 시간 줄에는 없다 | README '`rid=`·`trace=`', CONTRACT §6, METRICS |
| 쿼리 문자열은 '어느 줄에도' 없다 | 서비스 줄에는 없다. uvicorn 접근 줄에는 `"POST /infer?probe=1 HTTP/1.1" 401` 처럼 남는다. Spring 은 쿼리를 보내지 않는다 | README '`rid=`·`trace=`' |
| 형식 `<UTC 시각> <수준> <로거> <메시지>` | 서비스 로거 줄의 형식이다. 같은 파일에 `start_pod.sh` 머리말·uvicorn 줄·라이브러리 경고·AnimalLift 출력이 이 형식 없이 섞인다 | README '형식' |
| Spring 이 같은 `rid` 를 남긴다(`app.py` 주석) | Spring 의 작업 실행 줄에는 `requestId` 가 없다. `jobId`·`attempt` 가 따로 있고, `traceId` 는 같다 | `app.py` 주석, README |

uvicorn 접근 줄을 끄거나(`--no-access-log`) 형식을 바꾸는 코드 변경은 하지 않았다. 문서를 실제에 맞췄다.

## 고친 문서

상태 표기를 오늘 본 것으로 바꿨다(작업 1건·Pod 1대라고 적음):

- [generation/inference_service/README.md](../../../../generation/inference_service/README.md): '요청 id·추적', '로그' 제목·확인, `BESIDE_REQUEST_TIMING` 기본값, `end_ms` 실제 Pod 값
- [docs/CONTRACT.md](../../../CONTRACT.md) §6, [docs/METRICS.md](../../../METRICS.md) §5
- [deploy/README.md](../../../../deploy/README.md) '추적' 확인, [docs/ARCHITECTURE.md](../../../ARCHITECTURE.md) 관측 표의 추적·추론 서비스 로그 행
- 배포 창 1 때 이미 적용됐는데 표기가 남아 있던 곳: [RUNBOOK](../../RUNBOOK.md) '먼저 볼 곳' 3행, [api/CHANGELOG.md](../../../api/CHANGELOG.md) 요청 id

고치지 않은 것:

- 보고서 표([멘토 대응](../../../report/2026-11-25_mentoring-response.md) §6, [최종보고서](../../../report/최종보고서.md) §6.4)의 '지금' 칸은 11-25 기준이다. 배포 창 1·2 의 결과(알림·Loki·JSON 로그·트레이스) 전체를 함께 바꿔야 하므로 이번에는 두었다.
- 앱에서 보낸 요청으로 앱 → 서버 → GPU 를 이어 본 것은 아직 없다(오늘은 스크립트로 보냈다).

## 파일

| 파일 | 내용 |
|---|---|
| [inference-log.txt](inference-log.txt) | Pod `inference.log` 의 이번 시작 뒤 부분(`/healthz` 줄 제외) |
