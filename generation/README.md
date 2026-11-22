# generation — 3D Generation 트랙 (AnimalLift)

사진 → 반려동물 3D 메시 → GLB 를 만드는 트랙의 작업 폴더다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md), 품질 기준과 측정 항목은 [docs/METRICS.md](../docs/METRICS.md), GLB 파일 계약은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md).

```text
generation/
├─ CLAUDE.md
├─ README.md
├─ inference_service/      # FastAPI 내부 서비스: POST /infer·/infer/upload, GET /healthz, AnimalLift 실행 (계약은 Backend 와 합의)
│  ├─ app.py
│  ├─ animallift_runner.py # AnimalLift 를 한 번 불러 두고 사진 → mesh.obj, uv.png, hair.npz
│  ├─ ANIMALLIFT_SETUP.md  # AnimalLift 설치, 환경 변수, WSL/Runpod 실행, 모델 교체
│  ├─ test_app.py          # 추론 서비스·변환기·리깅 시험(모델 없이)
│  ├─ requirements.txt
│  └─ README.md            # GPU 서버 실행법, /infer 계약
├─ tools/                  # convert_asset.py (OBJ+PNG → GLB + metrics.json, 털 맵은 GLB 안), rig_transfer.py (템플릿 리그·애니메이션 이식)
│  └─ README.md
└─ experiments/            # 실험 기록. TEMPLATE.md 순서를 따른다
   ├─ README.md
   └─ TEMPLATE.md
```

## 파이프라인

```text
사진 1~10장(AnimalLift 는 첫 장만) ─▶ AnimalLift 추론 ─▶ mesh.obj + uv.png (+ hair.npz) ─▶ tools/convert_asset.py ─▶ {jobId}/base.glb (털 맵은 그 안 extras.beside) + base.metrics.json
                 inferMs, gpuPeakMB, modelParams                         convertMs, vertices, triangles, textureSize, bytes
```

Spring 의 real 워커가 `POST /infer` 로 이 파이프라인을 호출하고, 돌아온 GLB 경로를 `storage/results/{jobId}/` 로 옮겨 Unity 에 서빙한다. Unity 는 이 서비스를 직접 호출하지 않는다([docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 상태

- **[구현됨]** `inference_service/app.py`: 입·출력 스키마, `GET /healthz`, 모델 미설정 501·로딩 중 503, `BESIDE_SAMPLE_GLB` 패스스루, 업로드 모드(2026-10-30).
- **머지됨(#1, 2026-10-14)** AnimalLift 실행(`animallift_runner.py`), `tools/convert_asset.py`, 자동 리깅(`tools/rig_transfer.py`, 템플릿은 리포 밖).
- **[실험 결과]** 이 코드로 시험 사진 3장(`backend/src/test/resources/photos/`)을 돌린 1건 시간·GPU 메모리·출력 크기: [exp-01](experiments/2026-11-17_exp-01_baseline-runpod.md)(Runpod A40, 리깅 끔, 표본 4건 + 2차 부하 539건).
- **[실험 결과]** (2026-11-04~05, RTX 4060 Ti, Unity 트랙 조사 — 브랜치 `Unity` 에서 2026-11-21 옮김) 생성 모델 비교: 1장당 SF3D 0.67 s·TripoSR 1.63 s·AnimalLift 5.31 s, 고정 토폴로지·`hair.npz` 는 AnimalLift 만. AnimalLift 방해 요소·같은 개 일관성: 동물이 없는 사진에도 강아지를 낸다(서버 입력 검사 필요), 사진 고르기가 전처리보다 영향이 크다, 개 구분은 거의 털색. 시간·VRAM 정의가 METRICS 와 다르다 — [experiments/README.md](experiments/README.md).
- **[구현됨]** (2026-11-21, 브랜치 `Unity` 2d2e235 를 main 변환기에 맞춰 옮김) 추론 서비스 `BESIDE_EMBED_HAIR=0`(털 맵 끄기), 변환기 선택 기능 `--hair-meta`/`BESIDE_HAIR_META`(가이드 텍셀로 털 맵 다시 만들기·코·발바닥 털 없애기)·`--pad-texture`/`BESIDE_PAD_TEXTURE=1`(uv.png 초록 배경 채우기). 둘 다 기본 꺼짐이라 기본 GLB 는 그대로다. 시험은 `test_app.py`, 실제 AnimalLift 메타·Pod·기기 확인은 [계획]. 자세히 [tools/README.md](tools/README.md).
- **[계획]** ([docs/PLAN.md](../docs/PLAN.md) 3단계) 품질 6항목 채점, 우리 사진으로 실제 E2E(exp-02, `job_runs` CSV) → 품질·속도 개선 실험(exp-03·04, exp-03 은 틀만 있다). 개선 후보(가설): 비정사각 사진을 정사각으로 늘려 넣는 지금 전처리(`animallift_runner.py` 의 Resize) 대신 정사각 패딩·배경 제거(Spring 쪽 구현은 [구현됨] 2026-11 — 원본/크롭/배경 제거 3D 비교 실험은 [계획], [experiments/](experiments/) 의 틀), 여러 장 중 가장 좋은 사진 고르기, 털 색 재현 개선(흰 개·붉은 털), 부위 번호 지도(`hair_region.png`, `--hair-meta` 사본)를 GLB 에 넣어 부위별 털 혼합(GLB 계약 추가 필요). (털 맵을 GLB 에 넣는 것은 2026-11-21 PR #25 로 변환기·리깅 쪽 [구현됨] — main 병합(2026-11-21)·Pod 반영 전, 기기에서 털·FPS 확인은 [계획], [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §4.1.)
- AnimalLift 리포에 라이선스 파일이 없다(체크포인트 태그는 [inference_service/ANIMALLIFT_SETUP.md](inference_service/ANIMALLIFT_SETUP.md)). 결과물·가중치를 공개 배포하기 전에 확인한다. 리깅 템플릿(SMAL 파생)은 커밋하지 않는다.
- **직접 할 일**: glTFast 로드 검증 결과와 실험 기록을 `experiments/` 에 넣는다. 모델은 추론 서비스와 같은 conda env 에서 같은 프로세스로 돈다([inference_service/ANIMALLIFT_SETUP.md](inference_service/ANIMALLIFT_SETUP.md)).

## 실험 기록 규칙

- 파일명 `experiments/YYYY-MM-DD_exp-NN_주제.md`, 내용은 [TEMPLATE.md](experiments/TEMPLATE.md) 순서: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 품질은 사람 평가 1~5점 6항목(얼굴 형태, 귀 모양/위치, 체형 비율, 털 색상, 무늬 재현, 텍스처 번짐/seam), 수치는 `inferMs, gpuPeakMB, modelParams, outputVertices, outputTriangles, convertMs`.
- 모델 가중치와 생성 결과물은 커밋하지 않는다(`.gitignore`). 대표 렌더 이미지는 작게 줄여 실험 기록 옆에 둔다.
