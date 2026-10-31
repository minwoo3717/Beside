# Beside

사진 몇 장으로 반려동물을 3D로 재현하고 AR로 곁에 두는 캡스톤 프로젝트다(중앙대학교 소프트웨어학부, 팀 Beside). 작업 규칙과 계약 변경 규칙은 [CLAUDE.md](CLAUDE.md)를 먼저 읽는다.

```text
Beside/
├─ CLAUDE.md        공통 작업 원칙, 아키텍처, 계약 변경 규칙
├─ docs/            CONTRACT.md(계약 통합본), 아키텍처, 단계별 계획·점검표, openapi.yaml, GLB 에셋 계약, 측정 항목
├─ backend/         Spring Boot 3.2 / Java 17 — API v0(동결)·v1, Mock/Real 워커, 브라우저 Mock UI
├─ unity/           Unity 6 Android AR 앱 (앱 코드는 Assets/Beside)
├─ generation/      3D 생성 트랙 — FastAPI 추론 서비스(AnimalLift), GLB 변환·리깅 도구, 실험 기록
├─ deploy/          VM 배포 — Docker Compose, Caddy(HTTPS·Basic 인증), 절차
└─ scripts/         e2e_mock.ps1 / e2e_mock.sh(업로드 → 폴링 → GLB 다운로드), GPU PC·VM 실행 스크립트
```

## 현재 상태 (2026-10-31 기준)

- **[구현됨]** Spring 서버: v1 `/api/v1`(Unity 와 브라우저 Mock UI 가 쓰는 계약), v0 `/api/jobs`(동결, 제거 대기), Mock·Real 워커, H2 파일 DB, 실행별 측정(`job_runs`·`events.jsonl`), 계약 테스트. real 워커는 Python `/infer` 를 경로 모드 또는 업로드 모드(VM ↔ GPU PC)로 부른다. VM 배포용 prod 프로파일·GPU 오프라인 대기·Discord 알림·H2 백업. 연동은 추론 서비스의 패스스루로만 확인했다.
- **머지됨(#1, 2026-10-14)** Unity 앱(업로드 → 폴링 → 다운로드 → AR 배치, 애니메이션 5종)과 AnimalLift 추론·GLB 변환·자동 리깅. 실기기 증빙과 측정값은 아직 없다.
- **[계획]** 실제 모델 E2E 측정, VM 실제 배포, 계약 회의(`api-v1.0` 태그). 기능 4묶음(관리·대기열·공유·보관함)은 검토 브랜치 `review/api-v1.1` 에 있고 회의 뒤 병합한다.
- 무엇이 빠졌고 누가 할지는 [docs/CHECKLIST.md](docs/CHECKLIST.md), 단계·일정·완료 기준은 [docs/PLAN.md](docs/PLAN.md).
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
