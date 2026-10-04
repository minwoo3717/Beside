# generation — 3D Generation (AnimalLift) 트랙

공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md). 여기에는 이 트랙의 규칙·실행·테스트 명령만 적는다.

## 규칙

- 모든 실험은 `experiments/YYYY-MM-DD_exp-NN_주제.md`에 [TEMPLATE.md](experiments/TEMPLATE.md) 순서(실패 사례 → 원인 가설 → 개선 → 비교 실험 → 결론)로 기록한다. 기록 없는 결과는 "실험 결과"로 인용하지 않는다.
- 3D 품질은 사람 평가 1~5점으로 **얼굴 형태, 귀 모양/위치, 체형 비율, 털 색상, 무늬 재현, 텍스처 번짐/seam** 여섯 항목을 채점한다([docs/METRICS.md](../docs/METRICS.md)).
- 수치 측정 항목 이름은 `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs`로 고정한다. 환경(GPU 모델, 드라이버, 배치 크기, 입력 해상도)을 함께 적는다.
- 출력물은 `mesh.obj + uv.png (+ hair.npz)` → `tools/convert_asset.py` → GLB + `metrics.json`. GLB는 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md)를 만족해야 한다(Y-up, 1 unit = 1 m, 원점 발바닥 중심, 정면 +Z).
- `inference_service/`의 `POST /infer` 입·출력 스키마를 바꾸면 Backend 트랙과 합의하고 [inference_service/README.md](inference_service/README.md)의 계약 절을 함께 고친다. Unity는 이 서비스를 직접 호출하지 않는다.
- 모델 가중치(`*.ckpt`, `*.pth`)와 생성 결과(`outputs/`)는 커밋하지 않는다.
- Animation, Rigging은 핵심 기능(정적 GLB 품질·속도) 완료 후의 확장 기능이다.

## 실행

```bash
# GPU 서버 (conda 또는 venv 안)
cd generation/inference_service
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8001                        # 모델 미구현: POST /infer 는 501
BESIDE_SAMPLE_GLB=/path/to/sample.glb uvicorn app:app --port 8001  # 패스스루: 샘플 GLB 경로를 돌려줘 Spring real 워커 연동을 먼저 시험
```

## 테스트

```bash
python -m py_compile generation/inference_service/app.py
curl -s http://localhost:8001/healthz
curl -s -X POST http://localhost:8001/infer -H "Content-Type: application/json" \
  -d '{"jobId":"test","imagePaths":["/data/dog1.jpg"],"options":{"hair":false}}'
```
