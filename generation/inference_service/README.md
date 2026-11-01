# inference_service — 내부 추론 API (FastAPI)

Spring 의 real 워커만 호출하는 **내부 전용** 서비스다. Unity 는 호출하지 않는다. 담당은 3D Generation 트랙, `/infer` 계약 변경은 Backend 와 합의한다.

상태: **[구현됨]** 스키마·`/healthz`·501/503 응답·패스스루 모드, 업로드 모드(`POST /infer/upload`·`/artifacts`)와 `X-Inference-Token`(2026-10-30, 아래 '전송 모드'). AnimalLift 모델 로드·추론(`animallift_runner.py`, 같은 프로세스·첫 사진 1장), `tools/convert_asset.py` 변환, 자동 리깅(`BESIDE_RIG_TEMPLATE` 이 있을 때 `tools/rig_transfer.py`)은 #1(2026-10-14)로 머지됐다 — 실측 기록(exp-01: 1건 시간·VRAM)은 **[계획]**. 설치는 [ANIMALLIFT_SETUP.md](ANIMALLIFT_SETUP.md). Spring 쪽 호출(`RealJobWorker`·`InferenceClient`)은 **[구현됨]** 2026-10-04 — 이 스텁의 패스스루와 501 로 연동을 확인했다. 패스스루 부하 설정 `BESIDE_PASSTHROUGH_*` 는 **[구현됨]** 2026-11-01(아래 '패스스루 부하 설정', VM 부하 시험에 쓰는 것은 [계획]).

## GPU 서버 실행

```bash
# 1. 환경 (AnimalLift 의존성은 PLAN 3단계에서 같은 env 에 추가)
conda create -n beside-infer python=3.10 -y && conda activate beside-infer   # 또는 python -m venv .venv
cd generation/inference_service
pip install -r requirements.txt

# 2. 실행
uvicorn app:app --host 127.0.0.1 --port 8001          # 모델 미설정이면 /infer 는 501, 모델 로딩 중·로드 실패면 503
BESIDE_SAMPLE_GLB=/data/sample.glb uvicorn app:app --host 127.0.0.1 --port 8001   # 패스스루: 샘플 GLB 경로를 돌려준다

# 3. 확인
curl -s http://localhost:8001/healthz
curl -s -X POST http://localhost:8001/infer -H "Content-Type: application/json" \
  -d '{"jobId":"test","imagePaths":["/data/dog1.jpg"],"options":{"hair":false}}'
```

Spring 이 다른 기계(클라우드 VM)에 있을 때 — 업로드 모드. 사설망(Tailscale) 주소에만 열고 토큰을 건다:

```bash
# WSL2 + AnimalLift: 스크립트가 BESIDE_HOST·BESIDE_INFERENCE_TOKEN 을 받는다(기본 127.0.0.1, 토큰 없음 = 같은 PC 경로 모드)
BESIDE_HOST=<이 PC 의 tailnet IP> BESIDE_INFERENCE_TOKEN=<비밀값> bash scripts/start_inference_wsl.sh
# 또는 직접
BESIDE_INFERENCE_TOKEN=<비밀값> uvicorn app:app --host <tailnet IP> --port 8001

# 확인 (VM 에서). /healthz 는 토큰 없이 열린다
curl -s http://<IP>:8001/healthz
curl -s -X POST http://<IP>:8001/infer/upload -H "X-Inference-Token: <비밀값>" \
  -F 'request={"jobId":"test-1","options":{"hair":false}};type=application/json' -F "photos=@dog1.jpg"
curl -s -o base.glb -H "X-Inference-Token: <비밀값>" http://<IP>:8001/artifacts/test-1/base.glb
curl -s -X DELETE -H "X-Inference-Token: <비밀값>" http://<IP>:8001/artifacts/test-1
```

Spring 쪽은 `--inference.transfer=upload --inference.base-url=http://<IP>:8001` 과 같은 비밀값의 `inference.token`(환경변수 `INFERENCE_TOKEN` 권장 — 명령줄 인자는 프로세스 목록에 보인다).

Swagger: http://localhost:8001/docs (FastAPI 자동 생성). Spring 쪽 설정은 `backend/src/main/resources/application-real.properties` 의 `inference.base-url`, `inference.transfer`, `inference.token`, `inference.timeout-ms`, `inference.max-concurrency`. Spring 과 함께 이 PC 에서 시험하는 절차는 [backend/README.md](../../backend/README.md) '프로파일 real'.

## 패스스루 부하 설정 (`BESIDE_PASSTHROUGH_*`)

> **3D Generation 담당 리뷰 필요.** 부하 계획 B5 로 Backend 트랙이 넣은 변경이다. 패스스루 분기와, 모델 실패 응답을 한 곳에서 만드는 헬퍼 `_inference_failed`(기존 `raise` 한 줄을 이 헬퍼로 바꿈 — 응답은 같다)만 건드렸고 모델 모드 동작은 바뀌지 않는다. 담당이 확인한 뒤 머지한다.

상태: 코드와 테스트 **[구현됨]** 2026-11-01. VM 부하 시험(G1~G3, F1a·F1b)에서 Tailscale 너머 스텁으로 쓰는 것은 **[계획]** — 아직 돌리지 않았다.

패스스루(`BESIDE_SAMPLE_GLB`)일 때만 읽는다. 샘플을 즉시 돌려주는 대신 GPU 를 흉내 내는 스텁으로 만들어, 실제 GPU PC 없이 Spring real 워커의 큐(대기·처리 시간), 실패 → retry, 타임아웃을 시험한다. 요청마다 환경변수를 다시 읽는다(테스트가 바꿀 수 있게; 띄운 서비스에서는 재시작해서 바꾼다). **모두 비어 있으면 sleep 도 슬롯 대기도 없이 지금과 같은 즉시 응답**이다. `scripts/perf` 는 `BESIDE_*` 를 지운 환경에서 스텁을 띄우므로 그 측정에는 영향이 없다.

| 환경변수 | 기본 | 동작 |
|---|---|---|
| `BESIDE_PASSTHROUGH_DELAY_MS` | 0 | 흉내 낸 생성 시간. 슬롯을 잡은 채로 이만큼 sleep |
| `BESIDE_PASSTHROUGH_JITTER_MS` | 0 | DELAY 에 균등분포 0~JITTER 를 **더한다**. sleep 은 [DELAY, DELAY+JITTER], 평균은 DELAY+JITTER/2 |
| `BESIDE_PASSTHROUGH_SLOTS` | 1 | 동시에 도는 생성 수 상한(세마포어). 1 = GPU 1장, N = GPU N장 흉내(G2). 나머지 요청은 슬롯을 기다린다 |
| `BESIDE_PASSTHROUGH_FAIL_RATE` | 0 | 0~1. 이 확률로 실패 |
| `BESIDE_PASSTHROUGH_FAIL_TOKEN` | 없음 | 첫 사진 파일 이름에 이 문자열이 있으면 실패 |
| `BESIDE_PASSTHROUGH_SLOW_TOKEN` + `BESIDE_PASSTHROUGH_SLOW_MS` | 없음, 0 | 첫 사진 파일 이름에 토큰이 있으면 DELAY+JITTER 대신 SLOW_MS 동안 sleep(슬롯 안). Spring `inference.timeout-ms` 시험용 |

- **실패 응답은 모델 실패와 같다**: HTTP 500 `{ "detail": { "code": "INFERENCE_FAILED", "message": "passthrough simulated failure (BESIDE_PASSTHROUGH_FAIL_TOKEN)" } }`(모델 모드와 같은 헬퍼). Spring 은 `detail.code` 대로 `INFERENCE_FAILED` 를 기록하고 앱은 retry 할 수 있다. 실패도 생성 시간을 다 쓴 뒤 돌려준다(실패하는 모델도 GPU 시간을 쓴다).
- 순서: 슬롯 대기 → sleep(SLOW 또는 DELAY+JITTER) → 실패 판정(토큰 먼저, 그다음 확률) → 슬롯 반납. 두 엔드포인트(`/infer`, `/infer/upload`)가 같은 `_run` 을 거치므로 둘 다 적용된다.
- `metrics.inferMs` 는 슬롯 안에서 보낸 시간(흉내 낸 생성 시간)이다. 슬롯 대기는 넣지 않는다 — 모델 모드의 inferMs 가 `MODEL_LOCK` 대기를 넣지 않는 것과 같다. 설정이 없으면 지금처럼 0 근처다.
- 토큰은 첫 사진의 **파일 이름**(디렉터리 제외)에서 대소문자를 구분해 찾는다. 경로 모드는 Spring 이 저장한 이름(`{jobId}_{원래 이름}`), 업로드 모드는 이 서비스가 저장한 이름(`00_{영문·숫자·._- 로 바꾼 이름}`)이므로 토큰은 영문·숫자·`._-` 로만 쓴다(예 `-fail-`, F1b 의 `slow`).
- `SLOTS` 만 설정하면 아무것도 흉내 내지 않는다(기다리게 할 시간이 없으므로 즉시 응답).
- 실행마다 로그 한 줄: `job <id>: passthrough sim <ms> ms (planned <ms>), slot wait <ms> ms of <n> slot(s), outcome ok|failed (fail token|fail rate|slow token)`.
- 숫자가 아닌 값은 기본값으로 두고 경고 로그를 남긴다. 음수는 0, `SLOTS` < 1 은 1, `FAIL_RATE` 는 0~1 로 자른다.
- 슬롯은 모델 모드의 `MODEL_LOCK` 과 별개인 세마포어다(패스스루는 `MODEL_LOCK` 을 쓰지 않는다). 첫 요청 때 `SLOTS` 크기로 만들고 값이 바뀌면 새로 만든다(바뀌는 순간 옛 슬롯을 쥔 요청과 잠깐 겹칠 수 있다 — 시험용 설정이다).
- Spring 이 시간 초과로 연결을 끊어도 sleep 은 끝까지 돌며 슬롯을 쥔다. 모델 모드에서 포기한 요청이 `MODEL_LOCK` 을 쥐는 것(아래 '가정과 열린 결정'의 동시성)과 같은 동작이라, F1b 에서 다음 작업이 그만큼 기다리는지 볼 수 있다.
- 업로드 모드에서는 Spring 이 시간 초과 뒤 정리로 `DELETE /artifacts/{jobId}` 를 보내 sleep 중인 작업 폴더가 지워진다. sleep 이 끝나 샘플을 복사하려 할 때 폴더가 없으면 경고 로그 `job <id>: work folder deleted during the run …` 를 남기고 위와 같은 500 `INFERENCE_FAILED` 로 답한다(아무도 읽지 않는 답이지만 처리하지 않은 예외 트레이스백 대신). 폴더는 다시 만들지 않는다.
- 동기 엔드포인트는 스레드 풀(anyio 기본 40)에서 돌고 슬롯을 기다리는 요청도 스레드를 하나 잡는다. Spring 은 `inference.max-concurrency` 건만 보내므로 보통은 문제없지만, 직접 40건 넘게 몰아넣으면 그 너머는 스레드 풀에서 기다린다.

부하 시험(VM)에서 쓰는 스텁 명령 — 절차이며 아직 돌리지 않았다 **[계획]**. 시리즈별 VM·스텁 값의 기준은 [deploy/loadtest/README.md](../../deploy/loadtest/README.md) '시리즈별 값' 표다.

```bash
# 0. 샘플 GLB 를 스텁을 띄울 같은 Linux·WSL 셸에서 만든다(부하 시험의 합성 GLB 하나: 1,500,000 바이트, seed 14).
#    generation/inference_service 에서:
python3 -c "import sys; sys.path.insert(0, '../../scripts/perf'); import fixtures; open('/tmp/lt-sample-1500000.glb', 'wb').write(fixtures.glb_synthetic(1_500_000, seed=14))"
ls -l /tmp/lt-sample-1500000.glb && sha256sum /tmp/lt-sample-1500000.glb   # 1500000 바이트, 03a0c8f2b05aee204237cee07ddb41c42173014b33ef0007a43c7b83e2fc0ee9

# G1: GPU 1장, 생성 시간 10초 고정. JITTER·FAIL_RATE 없음 (G2 는 SLOTS=<c> 만 바꾸고, G3 는 G1 과 같다)
BESIDE_SAMPLE_GLB=/tmp/lt-sample-1500000.glb BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1 \
BESIDE_INFERENCE_TOKEN=<비밀값> uvicorn app:app --host <tailnet IP> --port 8001

# F1a (생성 실패 20 %): G1 + FAIL_RATE
BESIDE_SAMPLE_GLB=/tmp/lt-sample-1500000.glb BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1 \
BESIDE_PASSTHROUGH_FAIL_RATE=0.2 \
BESIDE_INFERENCE_TOKEN=<비밀값> uvicorn app:app --host <tailnet IP> --port 8001

# F1b (타임아웃): G1 + 첫 사진 파일 이름에 slow 가 든 작업만 30초. BESIDE_PASSTHROUGH_FAIL_TOKEN 은 설정하지 않는다
BESIDE_SAMPLE_GLB=/tmp/lt-sample-1500000.glb BESIDE_PASSTHROUGH_DELAY_MS=10000 BESIDE_PASSTHROUGH_SLOTS=1 \
BESIDE_PASSTHROUGH_SLOW_TOKEN=slow BESIDE_PASSTHROUGH_SLOW_MS=30000 \
BESIDE_INFERENCE_TOKEN=<비밀값> uvicorn app:app --host <tailnet IP> --port 8001
```

- **G1·G2·G3 에는 JITTER·FAIL_RATE 를 쓰지 않는다.** G 시리즈는 생성 시간이 `DELAY_MS` 하나로 고정되고 모든 작업이 성공한다는 가정으로 측정한 대기 시간을 큐 이론 예측과 비교한다. JITTER 는 생성 시간을 흩뜨리고 평균을 DELAY+JITTER/2 로 바꾸며, FAIL_RATE 는 실패 → retry(k6 흐름도 앱처럼 한 번 retry 한다)로 도착을 늘려 그 비교가 성립하지 않는다. 실패는 F1a 에서만 본다.
- **F1b 의 짝**: k6 는 `scripts/load/gpu_queue.js` 에 `-e RHO=0.3 -e SLOTS=1 -e T_SEC=10 -e FAIL_TOKEN=slow -e FAIL_RATIO=0.2` 를 준다(ρ 를 G1 보다 낮추는 이유는 [deploy/loadtest/README.md](../../deploy/loadtest/README.md) 'F1b 명령'). 그러면 흐름의 20 % 만 사진 이름이 `lt-<RUN_ID>-gq-slow-<n>.png`(이 서비스가 저장한 이름은 `00_lt-…-gq-slow-<n>.png`)가 되어 SLOW_TOKEN 에 걸리고, k6 는 이 흐름이 FAILED 로 끝나는 것을 정상으로 센다. `RUN_ID` 에는 `slow` 를 넣지 않는다 — 넣으면 모든 작업이 느려지므로 `scripts/load/lib/config.js` 가 실패 토큰이 든 RUN_ID 를 거절한다. VM 쪽은 G1 + `LOADTEST_INFERENCE_TIMEOUT_MS=20000`(SLOW_MS 30초보다 짧아 Spring 이 `INFERENCE_TIMEOUT` 으로 포기한다). 끝나면 바로 보통 G1 을 한 번 돌려 뒤 작업이 연달아 실패하지 않는지 본다.
- **샘플 GLB 파일이 스텁 PC 에 실제로 있어야 한다.** `/healthz` 의 `"passthrough": true` 는 `BESIDE_SAMPLE_GLB` 가 설정됐다는 뜻일 뿐 파일이 있다는 증거가 아니다 — 파일이 없으면 모든 요청이 500 `CONVERSION_FAILED`(`BESIDE_SAMPLE_GLB not found`)로 끝난다. 위 0번으로 만들고 `ls -l`·`sha256sum` 으로 확인한다. 이 파일은 [deploy/loadtest/README.md](../../deploy/loadtest/README.md) '3. 합성 GLB 1.5 MB 넣기'의 `/tmp/lt-sample-1500000.glb` 와 같은 바이트(`scripts/perf/fixtures.py` 의 `glb_synthetic(1_500_000, seed=14)`)다. Windows 의 Python 으로 만들면 `/tmp` 가 `C:\tmp` 가 되어 WSL 의 스텁이 보지 못한다. 생성 식은 이 PC(Windows Python 3.14, 출력 경로만 다르게)에서 1,500,000 바이트·위 해시를 확인했다(2026-11-01).

확인한 것 **[구현됨]**: `test_app.py` 의 `PassthroughKnobTest` 9개 — 설정 없음 → sleep·슬롯 없음, 지연+지터 범위와 sleep 중 슬롯 점유, `SLOTS=2` 에서 둘은 겹치고 셋째는 기다림, 실패 토큰·실패율 응답의 상태 코드·`detail` 모양이 모델 실패 경로(가짜 모델)와 같음, 느린 토큰, 느린 업로드 도중 `DELETE /artifacts` 로 폴더가 지워지면 500 `INFERENCE_FAILED`·경고 로그·폴더 재생성 없음, `inferMs`, 패스스루 밖(501·모델 모드)에서는 무시, 값 범위. 이 PC 에서 uvicorn 스텁을 직접 띄워 동시 4건을 보낸 동작 확인(성능 측정 아님): `DELAY_MS=300` 에서 `SLOTS=1` 은 약 300 ms 간격으로 하나씩, `SLOTS=2` 는 둘씩 끝났고, `FAIL_TOKEN` 사진은 위 500 응답을 받았다.

## 계약 v0 (내부)

### `POST /infer`

요청:

```json
{ "jobId": "3fa85f64-…", "imagePaths": ["/srv/beside/uploads/3fa85f64-…_dog1.jpg"], "options": { "hair": false } }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `jobId` | string | Spring 작업 ID(`[A-Za-z0-9-]{1,64}`, Spring 은 UUID — 아니면 422). `{BESIDE_WORK_DIR}/{jobId}` 폴더와 로그에 사용 |
| `imagePaths` | string[1..10] | 이 서비스가 읽을 수 있는 **절대 경로**. Spring 과 같은 호스트 또는 공유 볼륨을 가정한다 |
| `options.hair` | bool | true 면 `hair.npz` 로 hair variant 도 생성 |

응답 200:

```json
{ "glbPath": "/srv/beside/results/3fa85f64-…/base.glb", "hairGlbPath": null,
  "metrics": { "inferMs": 48210, "gpuPeakMB": 9120, "modelParams": 312000000, "outputVertices": 10342, "outputTriangles": 19876, "convertMs": 850 },
  "passthrough": false, "modelVersion": "animallift-baseline@3f2a1c9" }
```

`metrics` 의 이름은 [docs/METRICS.md](../../docs/METRICS.md) §2 와 같다. `passthrough: true` 는 모델 대신 `BESIDE_SAMPLE_GLB` 를 돌려줬다는 뜻이다.

| 응답 필드 | 타입 | 설명 |
|---|---|---|
| `glbPath` | string | base GLB 의 절대 경로. Spring 이 헤더를 확인하고 `storage/results/{jobId}/base.glb` 로 복사한다 |
| `hairGlbPath` | string \| null | `options.hair` 일 때 hair GLB 경로 (Spring 쪽 저장은 [계획]) |
| `metrics.*` | int \| null | §2 의 여섯 수치. 숫자가 아니면 Spring 이 null 로 기록한다 |
| `passthrough` | bool | 샘플을 돌려줬으면 true. Spring 이 실행마다 저장하므로 측정표에서 거를 수 있다 |
| `modelVersion` | string \| null | **선택**(2026-10-06 추가). GLB 를 만든 모델·가중치 버전, 예 `animallift-baseline@<커밋>`. Spring 이 실행마다 `job_runs.model_version` 에 저장해 베이스라인과 개선안을 구분한다. 패스스루는 None, 128자까지 |

오류 (`detail` 에 `{ code, message }`, code 는 [docs/api/ERROR_CODES.md](../../docs/api/ERROR_CODES.md) 의 Job 실패 코드):

| HTTP | code | 상황 | Spring 이 Job 에 기록할 코드 |
|---|---|---|---|
| 400 | `INFERENCE_FAILED` | 이미지 없음·읽기 실패·입력 품질 불량 | `INFERENCE_FAILED` |
| 500 | `CONVERSION_FAILED` | OBJ/PNG → GLB 변환 실패, GLB_SPEC 위반 | `CONVERSION_FAILED` |
| 501 | `INFERENCE_UNAVAILABLE` | 모델 미설정(`BESIDE_ANIMALLIFT_DIR`·`BESIDE_MODEL_DIR`·`BESIDE_SAMPLE_GLB` 모두 없음) | `INFERENCE_UNAVAILABLE` |
| 503 | `INFERENCE_UNAVAILABLE` | 모델 로딩 중 또는 로드 실패(서비스 로그 확인) | `INFERENCE_UNAVAILABLE` |
| (연결 실패·타임아웃) | — | 서비스 다운, `inference.timeout-ms` 초과 | `INFERENCE_UNAVAILABLE` / `INFERENCE_TIMEOUT` |
| 501~504 | 없음·모르는 코드 | 프록시·서비스 준비 안 됨 | `INFERENCE_UNAVAILABLE` |
| 그 밖의 4xx·5xx | 없음·모르는 코드 | 처리하지 않은 예외(FastAPI 기본 500), 요청 검증 실패 422 | `INFERENCE_FAILED` |
| 200 | — | `glbPath` 없음, 파일 없음, glTF 2.0 바이너리 아님(Spring 이 헤더 magic `glTF`·version 2 만 확인) | `CONVERSION_FAILED` |

`detail.code` 가 위 Job 실패 코드면 HTTP 상태와 관계없이 그 코드가 기록된다. Spring 은 `detail.message` 를 앱 응답에 싣지 않고 서버 로그에만 남기므로 message 에 경로를 넣어도 된다.

### 전송 모드: 경로(path) / 업로드(upload) — 업로드는 [구현됨] 2026-10-30

업로드 모드는 이 서비스(FastAPI)와 Spring(`InferenceClient`) 양쪽 다 구현돼 있다. 확인한 것: Spring 가짜 서버 테스트(`InferenceClientTest`, `RealJobWorkerUploadTest`), 이 서비스의 TestClient 스모크(패스스루), 같은 PC 에서 패스스루 uvicorn(토큰 설정) ↔ Spring real(`inference.transfer=upload`) 으로 `scripts/e2e_mock.ps1` 전체 흐름. VM ↔ GPU PC(Tailscale) 실제 배치와 모델 모드의 업로드는 아직 돌려 보지 않았다 **[계획]**.

2026-10-30 결정(REVIEW_CHECKLIST #6 (b)): Spring 을 클라우드 VM 에 두고 이 서비스는 팀원 GPU PC 에 남긴다(Tailscale 사설망). 두 기계는 디스크를 공유하지 않으므로 사진 파일을 올리고 GLB 를 내려받는 업로드 모드를 **추가**한다. 위 `POST /infer`(경로 모드)는 바뀌지 않는다.

| 모드 | Spring 설정 | 쓰는 곳 |
|---|---|---|
| 경로 (기본) | `inference.transfer=path` | Spring 과 이 서비스가 같은 PC·공유 볼륨 (`scripts/run_real.ps1`) |
| 업로드 | `inference.transfer=upload` | 디스크를 공유하지 않는 배치 (VM 의 Spring ↔ GPU PC 의 이 서비스) |

### `POST /infer/upload` (multipart/form-data)

| 파트 | 형식 | 설명 |
|---|---|---|
| `request` | `application/json` 텍스트 1개, 파일명 없음 | `{ "jobId": "3fa85f64-…", "options": { "hair": false } }`. `jobId` 는 `[A-Za-z0-9-]{1,64}`(Spring 은 UUID), `options` 는 `/infer` 와 같다 |
| `photos` | 파일 1~10개 | 업로드 원래 파일명과 바이트. 파트 Content-Type 은 확장자로 정한다(jpg/jpeg·png·webp, 그 밖 `application/octet-stream`) |

서비스는 사진을 `{BESIDE_WORK_DIR}/{jobId}/input/` 에 순번 접두사를 붙여 저장한다(`00_dog.jpg`, `01_dog.png`). 파일명에서 경로 구분자 앞부분은 버리고 영문·숫자·`._-` 밖의 글자는 `_` 로 바꾼다(모델 쪽 이미지 로더가 한글 경로를 못 읽는 문제를 피한다). 같은 `jobId` 가 다시 오면(retry) `input/` 을 비우고 새로 저장한다. 그다음 처리와 오류는 `/infer` 와 같다.

응답 200 — `/infer` 와 같은 필드 + `glbUrl`:

```json
{ "glbPath": "/mnt/c/beside/work/3fa85f64-…/base.glb", "glbUrl": "/artifacts/3fa85f64-…/base.glb", "hairGlbPath": null,
  "metrics": { "inferMs": 48210, "gpuPeakMB": 9120, "modelParams": 312000000, "outputVertices": 10342, "outputTriangles": 19876, "convertMs": 850 },
  "passthrough": false, "modelVersion": "animallift-baseline@3f2a1c9" }
```

| 응답 필드 | 타입 | 설명 |
|---|---|---|
| `glbUrl` | string | 결과 GLB 의 **서비스 기준 상대 경로**(`/` 로 시작, 지금은 항상 `/artifacts/{jobId}/base.glb`). Spring 은 `inference.base-url` 뒤에 붙여 GET 한다. 절대 URL·`//` 로 시작하는 값은 받지 않는다 |
| `glbPath` | string | 이 서비스 PC 안의 경로. 참고용이며 업로드 모드의 Spring 은 쓰지 않는다 |
| 나머지 | | `/infer` 와 같다 |

패스스루(`BESIDE_SAMPLE_GLB`)에서도 동작한다: 샘플을 `{BESIDE_WORK_DIR}/{jobId}/base.glb` 로 복사해 artifact 로 내준다(`passthrough: true`). GPU 없이 VM ↔ GPU PC 연동을 먼저 시험하기 위해서다.

`request` 파트가 JSON 이 아니거나 `jobId` 가 패턴 밖이거나 사진이 0개·11개 이상이면 422(FastAPI 요청 검증과 같은 취급).

### `GET /artifacts/{jobId}/base.glb`

200 `model/gltf-binary`. `jobId` 는 `[A-Za-z0-9-]{1,64}` 만, 파일명은 화이트리스트(지금은 `base.glb` 만)라서 작업 폴더 밖의 파일은 열 수 없다. 패턴 밖·화이트리스트 밖·파일 없음은 404.

### `DELETE /artifacts/{jobId}`

그 작업 폴더 `{BESIDE_WORK_DIR}/{jobId}`(업로드 사진·모델 중간 산출물·결과 GLB)를 지운다. 없어도 204(멱등), `jobId` 가 패턴 밖이면 404. Spring 은 업로드 모드에서 GLB 를 저장한 뒤(작업 성공·실패 모두) 부르고, 이 호출이 실패해도 로그만 남긴다(작업 결과는 바뀌지 않는다). 그래서 업로드 모드에서는 GPU PC 에 작업 파일이 남지 않는다 — 실험용 중간 산출물이 필요하면 경로 모드로 돌린다.

### 인증: `X-Inference-Token` (두 모드 공통)

환경변수 `BESIDE_INFERENCE_TOKEN` 이 있으면 `/infer`, `/infer/upload`, `/artifacts/*` 는 요청 헤더 `X-Inference-Token` 이 그 값과 같아야 한다(상수 시간 비교). 없거나 다르면 본문을 읽기 전에 401 `{ "detail": { "code": "INFERENCE_UNAVAILABLE", "message": "…" } }`. `/healthz`(와 FastAPI 의 `/docs`·`/openapi.json`)는 토큰 없이 열려 있다. 환경변수가 없거나 빈 값이면 검사하지 않는다(지금 동작). Spring 은 `inference.token` 이 비어 있지 않으면 두 모드 모두 이 헤더를 보낸다.

### Spring 쪽 매핑 추가 (위 오류 표에 더해)

| 상황 | 모드 | Spring 이 Job 에 기록할 코드 |
|---|---|---|
| HTTP 401·403, `detail.code` 없음·모르는 코드 (토큰 누락·불일치, 프록시 거부) | 두 모드 | `INFERENCE_UNAVAILABLE` |
| 200 인데 `glbUrl` 없음·빈 값·`/` 로 시작하지 않음 | 업로드 | `CONVERSION_FAILED` |
| `GET glbUrl` 이 200 아님·연결 실패·시간 초과 | 업로드 | `CONVERSION_FAILED` |
| 내려받은 파일이 glTF 2.0 바이너리 아님 | 업로드 | `CONVERSION_FAILED` (경로 모드와 같은 헤더 확인) |

이 서비스가 직접 보내는 401 은 `detail.code` 가 `INFERENCE_UNAVAILABLE` 이라 위 규칙과 결과가 같다.

### `GET /healthz`

```json
{ "status": "ok", "device": "cuda", "modelLoaded": false, "passthrough": false }
```

## 가정과 열린 결정 (계약 회의 안건)

- 경로 모드는 Spring ↔ Python **파일시스템 공유**(같은 호스트 또는 NFS/SMB 볼륨)를 가정한다: 업로드 경로를 그대로 넘기고 GLB 경로를 돌려받는다. 디스크를 공유하지 않는 분리 배포는 업로드 모드(`POST /infer/upload` + `GET /artifacts/…`)로 한다(2026-10-30 결정, REVIEW_CHECKLIST #6 (b)).
- 동기 호출: Spring 워커 스레드가 응답까지 기다린다(기본 600초). 추론이 더 길어지면 작업 큐·콜백 방식으로 바꾼다.
- 동시성: GPU 1장 기준 요청 1개씩. Spring 이 `inference.max-concurrency`(기본 1)건만 동시에 보내고 나머지 작업은 Spring 에서 PENDING 으로 기다린다 **[구현됨]**. 그래서 보통은 `inference.timeout-ms` 가 그 작업 자신의 추론 시간에만 걸린다. 예외: Spring 이 시간 초과(또는 재시작)로 포기한 요청도 추론 서비스에서는 끝까지 돌며 `MODEL_LOCK` 을 쥐고 있어(동기 엔드포인트는 연결이 끊겨도 취소되지 않는다), 다음 작업의 `/infer` 는 그만큼 잠금을 기다린 뒤 시작하고 그 시간도 다음 작업의 timeout·`processingMs` 에 들어간다. `timeout-ms` 를 실측 추론 시간보다 넉넉히(약 3배, [backend/README.md](../../backend/README.md)) 둔다.
