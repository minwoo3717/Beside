# Beside

사진 몇 장으로 반려동물을 3D로 재현하고 AR로 곁에 두는 캡스톤 프로젝트다(중앙대학교 소프트웨어학부, 팀 Beside). 작업 규칙과 계약 변경 규칙은 [CLAUDE.md](CLAUDE.md)를 먼저 읽는다.

```text
Beside/
├─ CLAUDE.md        공통 작업 원칙, 아키텍처, 계약 변경 규칙
├─ docs/            CONTRACT.md(계약 통합본), 아키텍처, 단계별 계획, openapi.yaml, GLB 에셋 계약, 측정 항목
├─ backend/         Spring Boot 3.2 / Java 17 — API v0(동결)·v1, Mock/Real 워커, 브라우저 Mock UI
├─ unity/           Unity Android AR 클라이언트 — 업로드·폴링·AR 배치·애니메이션·쉘 털, 털 측정 도구(FurLab), 앱 실험 기록
├─ generation/      3D 생성 트랙 — AnimalLift 추론 서비스, GLB 변환·리깅·털 맵 삽입 도구, 실험 기록
└─ scripts/         e2e_mock.ps1 / e2e_mock.sh(전체 흐름), run_real.ps1(GPU PC), analyze_fur_bench.py(털 측정 분석)
```

## 현재 상태 (2026-11-17 기준)

- **[구현됨]** Spring 서버: v1 `/api/v1`, Mock/Real 워커, H2 파일 DB, 실행별 측정 테이블 `job_runs`, 계약 테스트. real 워커가 Python `/infer` 를 호출한다.
- **[구현됨]** AnimalLift 추론 서비스: 사진 → AnimalLift → GLB 변환(+Z 정면, 최장 축 1 m) → SMAL 뼈대 리깅 → **개별 털 길이·결 맵을 GLB 안에 삽입**(`extras.beside`). [generation/inference_service/ANIMALLIFT_SETUP.md](generation/inference_service/ANIMALLIFT_SETUP.md)
- **[구현됨]** Unity 앱: 갤러리 → 업로드 → 폴링 → GLB 캐시·로드 → AR 배치(1마리, 바닥만) → 애니메이션(Idle/Walk/Sit/TailWag/HeadTilt). **쉘 털 v2**(털 결 방향, 화면 차지율에 따른 쉘 수 자동 조절)와 그림자 바닥면. [unity/README.md](unity/README.md)(프로젝트 루트 `unity/BesideApp/`), [unity/docs/FUR_RENDERING.md](unity/docs/FUR_RENDERING.md)
- **[실험 결과]** 털 렌더링 성능(실기기, 방식·최적화별 GPU 시간): [unity/experiments](unity/experiments/README.md). 3D 생성 모델 비교, AnimalLift 방해 요소·일관성: [generation/experiments](generation/experiments/README.md).
- **[계획]** 화질 옵션(구형 기기 대응), 부하 테스트(k6)와 장애 시나리오, 서버 이중화 검토, 털 색 보존·귀 가장자리 색 번짐 수정, 블라인드 화질 평가. 단계·일정은 [docs/PLAN.md](docs/PLAN.md).
- SMAL 파생 데이터(리깅 템플릿·웨이트·`.pkl`)와 AnimalLift 결과물은 라이선스 때문에 공개 배포 전 확인이 필요하다. 템플릿은 커밋하지 않는다.

## 빠른 시작

```powershell
cd backend
.\gradlew.bat test            # Java 테스트 전체
.\gradlew.bat bootRun         # http://localhost:8080 (브라우저 Mock UI), /swagger-ui/index.html (v1 스펙)
```

다른 터미널에서:

```powershell
.\scripts\e2e_mock.ps1        # healthz → 업로드 → 폴링 → GLB 다운로드, 결과는 scripts/out/
node --test backend/src/test/js/mock-mvp.test.cjs
```

## 트랙별 진입점

| 트랙 | 문서 |
|---|---|
| Backend | [backend/README.md](backend/README.md), [backend/CLAUDE.md](backend/CLAUDE.md) |
| Unity | [unity/README.md](unity/README.md), [unity/CLAUDE.md](unity/CLAUDE.md) |
| 3D Generation | [generation/README.md](generation/README.md), [generation/CLAUDE.md](generation/CLAUDE.md) |
| 공통 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PLAN.md](docs/PLAN.md), 계약 회의 안건 [docs/api/REVIEW_CHECKLIST.md](docs/api/REVIEW_CHECKLIST.md) |
