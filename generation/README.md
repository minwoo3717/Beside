# generation — 3D Generation 트랙 (AnimalLift)

사진 → 반려동물 3D 메시 → GLB 를 만드는 트랙의 작업 폴더다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 품질 기준과 측정 항목은 [docs/METRICS.md](../docs/METRICS.md), GLB 파일 계약은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md).

```text
generation/
├─ CLAUDE.md
├─ README.md
├─ inference_service/      # FastAPI 내부 서비스 스텁: POST /infer, GET /healthz  (담당: 3D Generation, 계약은 Backend 와 합의)
│  ├─ app.py
│  ├─ requirements.txt
│  └─ README.md            # GPU 서버 실행법, /infer 계약
├─ tools/                  # convert_asset.py 자리 (OBJ+PNG → GLB + metrics.json). 별도 세션 결과물 투입 예정
│  └─ README.md
└─ experiments/            # 실험 기록. TEMPLATE.md 순서를 따른다
   ├─ README.md
   └─ TEMPLATE.md
```

## 파이프라인

```text
사진 1~10장 ─▶ AnimalLift 추론 ─▶ mesh.obj + uv.png (+ hair.npz) ─▶ tools/convert_asset.py ─▶ {jobId}-base.glb (+ -hair.glb) + metrics.json
                 inferMs, gpuPeakMB, modelParams                         convertMs, vertices, triangles, textureSize, bytes
```

Spring 의 real 워커가 `POST /infer` 로 이 파이프라인을 호출하고, 돌아온 GLB 경로를 `storage/results/{jobId}/` 로 옮겨 Unity 에 서빙한다. Unity 는 이 서비스를 직접 호출하지 않는다([docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 상태

- **[구현됨]** `inference_service/app.py` 스텁: 입·출력 스키마, `GET /healthz`, 모델이 없을 때 501, `BESIDE_SAMPLE_GLB` 패스스루 모드.
- **[계획]** AnimalLift 베이스라인 재현(PLAN 3단계) → 품질·속도 개선 실험 → `/infer` 에 모델 연결 → `convert_asset.py` 호출(PLAN 4단계).
- **직접 할 일**: 별도 세션에서 만든 `convert_asset.py` 와 glTFast 로드 검증 결과를 `tools/` 와 `experiments/` 에 투입한다. 이 리포에는 아직 없다.

## 실험 기록 규칙

- 파일명 `experiments/YYYY-MM-DD_exp-NN_주제.md`, 내용은 [TEMPLATE.md](experiments/TEMPLATE.md) 순서: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 품질은 사람 평가 1~5점 6항목(얼굴 형태, 귀 모양/위치, 체형 비율, 털 색상, 무늬 재현, 텍스처 번짐/seam), 수치는 `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs`.
- 모델 가중치와 생성 결과물은 커밋하지 않는다(`.gitignore`). 대표 렌더 이미지는 작게 줄여 실험 기록 옆에 둔다.
