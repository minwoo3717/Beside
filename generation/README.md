# generation — 3D Generation 트랙 (AnimalLift)

사진 → 반려동물 3D 메시 → GLB 를 만드는 트랙의 작업 폴더다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 품질 기준과 측정 항목은 [docs/METRICS.md](../docs/METRICS.md), GLB 파일 계약은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md).

```text
generation/
├─ CLAUDE.md
├─ README.md
├─ inference_service/      # FastAPI 내부 서비스: POST /infer·/infer/upload, GET /healthz, AnimalLift 실행 (계약은 Backend 와 합의)
│  ├─ app.py
│  ├─ requirements.txt
│  └─ README.md            # GPU 서버 실행법, /infer 계약
├─ tools/                  # convert_asset.py (OBJ+PNG → GLB + metrics.json), rig_transfer.py (템플릿 리그·애니메이션 이식)
│  └─ README.md
└─ experiments/            # 실험 기록. TEMPLATE.md 순서를 따른다
   ├─ README.md
   └─ TEMPLATE.md
```

## 파이프라인

```text
사진 1~10장 ─▶ AnimalLift 추론 ─▶ mesh.obj + uv.png (+ hair.npz) ─▶ tools/convert_asset.py ─▶ {jobId}/base.glb (+ hair.glb) + base.metrics.json
                 inferMs, gpuPeakMB, modelParams                         convertMs, vertices, triangles, textureSize, bytes
```

Spring 의 real 워커가 `POST /infer` 로 이 파이프라인을 호출하고, 돌아온 GLB 경로를 `storage/results/{jobId}/` 로 옮겨 Unity 에 서빙한다. Unity 는 이 서비스를 직접 호출하지 않는다([docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 상태

- **[구현됨]** `inference_service/app.py`: 입·출력 스키마, `GET /healthz`, 모델 미설정 501·로딩 중 503, `BESIDE_SAMPLE_GLB` 패스스루, 업로드 모드(2026-10-30).
- **머지됨(#1, 2026-10-14)** AnimalLift 실행(`animallift_runner.py`), `tools/convert_asset.py`, 자동 리깅(`tools/rig_transfer.py`, 템플릿은 리포 밖). 이 코드로 실제 사진을 돌린 측정 기록은 아직 없다.
- **[계획]** ([docs/PLAN.md](../docs/PLAN.md) 3단계) exp-01(1건 시간·VRAM·출력 형식 기록) → 우리 사진으로 실제 E2E(exp-02, `job_runs` CSV) → 품질·속도 개선 실험(exp-03·04). 개선 후보(가설): 비정사각 사진을 정사각으로 늘려 넣는 지금 전처리(`animallift_runner.py` 의 Resize) 대신 정사각 패딩·배경 제거, 여러 장 중 가장 좋은 사진 고르기, 털 맵을 GLB 에 넣어 앱의 털 표현 켜기.
- **직접 할 일**: glTFast 로드 검증 결과와 실험 기록을 `experiments/` 에 넣는다. 모델은 추론 서비스와 같은 conda env 에서 같은 프로세스로 돈다([inference_service/ANIMALLIFT_SETUP.md](inference_service/ANIMALLIFT_SETUP.md)).

## 실험 기록 규칙

- 파일명 `experiments/YYYY-MM-DD_exp-NN_주제.md`, 내용은 [TEMPLATE.md](experiments/TEMPLATE.md) 순서: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 품질은 사람 평가 1~5점 6항목(얼굴 형태, 귀 모양/위치, 체형 비율, 털 색상, 무늬 재현, 텍스처 번짐/seam), 수치는 `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs`.
- 모델 가중치와 생성 결과물은 커밋하지 않는다(`.gitignore`). 대표 렌더 이미지는 작게 줄여 실험 기록 옆에 둔다.
