# inference_service — 내부 추론 API (FastAPI)

Spring 의 real 워커만 호출하는 **내부 전용** 서비스다. Unity 는 호출하지 않는다. 담당은 3D Generation 트랙, `/infer` 계약 변경은 Backend 와 합의한다.

상태: **[구현됨]** 스키마·`/healthz`·501/503 응답·패스스루 모드, 업로드 모드(`POST /infer/upload`·`/artifacts`)와 `X-Inference-Token`(2026-10-30, 아래 '전송 모드'). AnimalLift 모델 로드·추론(`animallift_runner.py`, 같은 프로세스·첫 사진 1장), `tools/convert_asset.py` 변환, 자동 리깅(`BESIDE_RIG_TEMPLATE` 이 있을 때 `tools/rig_transfer.py`)은 #1(2026-10-14)로 머지됐다 — 실측 기록(exp-01: 1건 시간·VRAM)은 **[계획]**. 설치는 [ANIMALLIFT_SETUP.md](ANIMALLIFT_SETUP.md). Spring 쪽 호출(`RealJobWorker`·`InferenceClient`)은 **[구현됨]** 2026-10-04 — 이 스텁의 패스스루와 501 로 연동을 확인했다.

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
