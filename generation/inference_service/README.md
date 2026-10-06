# inference_service — 내부 추론 API (FastAPI)

Spring 의 real 워커만 호출하는 **내부 전용** 서비스다. Unity 는 호출하지 않는다. 담당은 3D Generation 트랙, `/infer` 계약 변경은 Backend 와 합의한다.

상태: **[구현됨]** 스키마·`/healthz`·501 응답·패스스루 모드. **[계획]** AnimalLift 모델 로드·추론, `tools/convert_asset.py` 호출 (`app.py` 의 TODO). Spring 쪽 호출(`RealJobWorker`·`InferenceClient`)은 **[구현됨]** 2026-10-04 — 이 스텁의 패스스루와 501 로 연동을 확인했다.

## GPU 서버 실행

```bash
# 1. 환경 (AnimalLift 의존성은 PLAN 3단계에서 같은 env 에 추가)
conda create -n beside-infer python=3.10 -y && conda activate beside-infer   # 또는 python -m venv .venv
cd generation/inference_service
pip install -r requirements.txt

# 2. 실행
uvicorn app:app --host 0.0.0.0 --port 8001            # /infer 는 501 (모델 미구현)
BESIDE_SAMPLE_GLB=/data/sample.glb uvicorn app:app --host 0.0.0.0 --port 8001   # 패스스루: 샘플 GLB 경로를 돌려준다

# 3. 확인
curl -s http://localhost:8001/healthz
curl -s -X POST http://localhost:8001/infer -H "Content-Type: application/json" \
  -d '{"jobId":"test","imagePaths":["/data/dog1.jpg"],"options":{"hair":false}}'
```

Swagger: http://localhost:8001/docs (FastAPI 자동 생성). Spring 쪽 설정은 `backend/src/main/resources/application-real.properties` 의 `inference.base-url`, `inference.timeout-ms`, `inference.max-concurrency`. Spring 과 함께 이 PC 에서 시험하는 절차는 [backend/README.md](../../backend/README.md) '프로파일 real'.

## 계약 v0 (내부)

### `POST /infer`

요청:

```json
{ "jobId": "3fa85f64-…", "imagePaths": ["/srv/beside/uploads/3fa85f64-…_dog1.jpg"], "options": { "hair": false } }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `jobId` | string | Spring 작업 ID. 출력 파일명·로그에 사용 |
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
| 501 | `INFERENCE_UNAVAILABLE` | 모델 미구현/미로드 | `INFERENCE_UNAVAILABLE` |
| (연결 실패·타임아웃) | — | 서비스 다운, `inference.timeout-ms` 초과 | `INFERENCE_UNAVAILABLE` / `INFERENCE_TIMEOUT` |
| 501~504 | 없음·모르는 코드 | 프록시·서비스 준비 안 됨 | `INFERENCE_UNAVAILABLE` |
| 그 밖의 4xx·5xx | 없음·모르는 코드 | 처리하지 않은 예외(FastAPI 기본 500), 요청 검증 실패 422 | `INFERENCE_FAILED` |
| 200 | — | `glbPath` 없음, 파일 없음, glTF 2.0 바이너리 아님(Spring 이 헤더 magic `glTF`·version 2 만 확인) | `CONVERSION_FAILED` |

`detail.code` 가 위 Job 실패 코드면 HTTP 상태와 관계없이 그 코드가 기록된다. Spring 은 `detail.message` 를 앱 응답에 싣지 않고 서버 로그에만 남기므로 message 에 경로를 넣어도 된다.

### `GET /healthz`

```json
{ "status": "ok", "device": "cuda", "modelLoaded": false, "passthrough": false }
```

## 가정과 열린 결정 (계약 회의 안건)

- Spring ↔ Python **파일시스템 공유**(같은 호스트 또는 NFS/SMB 볼륨): 업로드 경로를 그대로 넘기고 GLB 경로를 돌려받는다. 분리 배포가 필요해지면 `/infer` 를 multipart 업로드 + GLB 바이너리 응답으로 바꾼다(PLAN 4단계에서 결정).
- 동기 호출: Spring 워커 스레드가 응답까지 기다린다(기본 600초). 추론이 더 길어지면 작업 큐·콜백 방식으로 바꾼다.
- 동시성: GPU 1장 기준 요청 1개씩. Spring 이 `inference.max-concurrency`(기본 1)건만 동시에 보내고 나머지 작업은 Spring 에서 PENDING 으로 기다린다 **[구현됨]**. 그래서 `inference.timeout-ms` 는 그 작업 자신의 추론 시간에만 걸린다.
