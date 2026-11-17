# generation — 3D Generation 트랙 (AnimalLift)

사진 → 반려동물 3D 메시 → GLB 를 만드는 트랙의 작업 폴더다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 품질 기준과 측정 항목은 [docs/METRICS.md](../docs/METRICS.md), GLB 파일 계약은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md).

```text
generation/
├─ CLAUDE.md
├─ README.md
├─ inference_service/      # FastAPI 내부 서비스: POST /infer, GET /healthz  (담당: 3D Generation, 계약은 Backend 와 합의)
│  ├─ app.py               # 추론 → 변환 → 리깅 → 털 지도 삽입
│  ├─ animallift_runner.py # AnimalLift 를 한 번 불러 두고 사진 → mesh.obj, uv.png, hair.npz
│  ├─ ANIMALLIFT_SETUP.md  # AnimalLift 설치, 환경 변수, WSL/Ubuntu 실행, 모델 교체
│  ├─ requirements.txt
│  └─ README.md            # /infer 계약
├─ tools/                  # convert_asset.py (GLB 변환 + 털 지도), rig_transfer.py (리깅), embed_hair.py (털 지도 삽입)
│  └─ README.md
└─ experiments/            # 실험 기록. TEMPLATE.md 순서를 따른다
   ├─ README.md
   └─ TEMPLATE.md
```

## 파이프라인

```text
사진 1장 ─▶ AnimalLift 추론 ─▶ mesh.obj + uv.png + hair.npz ─▶ convert_asset.py ─▶ rig_transfer.py ─▶ embed_hair.py ─▶ {jobId}/base.glb (리깅 + 털 지도) + base.metrics.json
                 inferMs, gpuPeakMB, modelParams                         convertMs, vertices, triangles, textureSize, bytes
```

Spring 의 real 워커가 `POST /infer` 로 이 파이프라인을 호출하고, 돌아온 GLB 경로를 `storage/results/{jobId}/` 로 옮겨 Unity 에 서빙한다. Unity 는 이 서비스를 직접 호출하지 않는다([docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 상태

- **[구현됨]** (2026-10-14) AnimalLift 추론 서비스: 시작 시 모델 로드, `/infer` 에서 추론 → GLB 변환 → SMAL 리깅(템플릿이 있을 때) → `glbPath`·metrics·`modelVersion` 응답. 패스스루 모드(`BESIDE_SAMPLE_GLB`) 유지. 실행은 [ANIMALLIFT_SETUP.md](inference_service/ANIMALLIFT_SETUP.md).
- **[구현됨]** (2026-11-01) 개별 털 지도: `hair.npz` → 털 길이·결 지도(가이드 텍셀만 사용, 코·발바닥 털 제거) → GLB `extras.beside` 에 삽입, TANGENT 추가, 텍스처 초록 배경 채우기. 앱이 그 개의 털로 쉘 털을 그린다. [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §3.1.
- **[실험 결과]** (2026-11-04~05) 생성 모델 비교(AnimalLift / SF3D / TripoSR), AnimalLift 방해 요소·같은 개 일관성. [experiments/README.md](experiments/README.md).
- **[계획]** 입력 사진 전처리(반려동물 검출·배경 제거) 연동 효과 정리, 여러 장 중 좋은 사진 고르기, 털 색 재현 개선(흰 개·붉은 털), 부위 번호 지도(`hair_region.png`)를 GLB 에 넣어 부위별 털 혼합.
- AnimalLift 리포에 라이선스 파일이 없다. 결과물·가중치를 공개 배포하기 전에 확인한다. 리깅 템플릿(SMAL 파생)은 커밋하지 않는다.

## 실험 기록 규칙

- 파일명 `experiments/YYYY-MM-DD_exp-NN_주제.md`, 내용은 [TEMPLATE.md](experiments/TEMPLATE.md) 순서: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 품질은 사람 평가 1~5점 6항목(얼굴 형태, 귀 모양/위치, 체형 비율, 털 색상, 무늬 재현, 텍스처 번짐/seam), 수치는 `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs`.
- 모델 가중치와 생성 결과물은 커밋하지 않는다(`.gitignore`). 대표 렌더 이미지는 작게 줄여 실험 기록 옆에 둔다.
