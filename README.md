# Beside

사진 몇 장으로 반려동물을 3D로 재현하고 AR로 곁에 두는 캡스톤 프로젝트다(중앙대학교 소프트웨어학부, 팀 Beside). 작업 규칙과 계약 변경 규칙은 [CLAUDE.md](CLAUDE.md)를 먼저 읽는다.

```text
Beside/
├─ CLAUDE.md        공통 작업 원칙, 아키텍처, 계약 변경 규칙
├─ docs/            CONTRACT.md(계약 통합본), 아키텍처, 단계별 계획, openapi.yaml, GLB 에셋 계약, 측정 항목
├─ backend/         Spring Boot 3.2 / Java 17 — API v0(동결)·v1, Mock/Real 워커, 브라우저 Mock UI
├─ unity/           Unity Android AR 클라이언트 — 계약 DTO와 C# 틀 (Assets/Beside)
├─ generation/      3D 생성 트랙 — AnimalLift 실험 기록, FastAPI 추론 서비스 스텁, GLB 변환 도구 자리
└─ scripts/         e2e_mock.ps1 / e2e_mock.sh — 업로드 → 폴링 → GLB 다운로드 전체 흐름
```

## 현재 상태 (2026-10-04 기준)

- **[구현됨]** Spring Mock 서버: v1 `/api/v1`(Unity 와 브라우저 Mock UI 가 쓰는 계약), v0 `/api/jobs`(동결, 제거 대기), Mock 워커(`PENDING → PROCESSING → COMPLETED | FAILED`, 재시도), H2 파일 DB(재시작 후에도 작업 유지), 계약 테스트(openapi.yaml ↔ springdoc). real 워커는 Python `/infer` 를 호출한다(FastAPI 스텁의 패스스루로 연동 확인, 실제 모델은 [계획]).
- **[구현됨]** 계약 문서: 통합본 [docs/CONTRACT.md](docs/CONTRACT.md), 원본 [docs/api/openapi.yaml](docs/api/openapi.yaml), [ERROR_CODES](docs/api/ERROR_CODES.md), [GLB_SPEC](docs/asset/GLB_SPEC.md), [METRICS](docs/METRICS.md). Unity 계약 DTO([unity/Assets/Beside/Api](unity/Assets/Beside/Api)), FastAPI `/infer` 스텁([generation/inference_service](generation/inference_service)).
- **[계획]** Unity 실제 프로젝트와 AR 배치, AnimalLift 추론 연동, 측정 자동화. 단계·일정·완료 기준은 [docs/PLAN.md](docs/PLAN.md).
- **직접 할 일**: `backend/storage/results/sample-dog.glb`는 0바이트다. [GLB_SPEC](docs/asset/GLB_SPEC.md)을 만족하는 유효한 GLB로 교체해야 Unity 로드 검증을 할 수 있다.

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
