# Beside

사진 몇 장으로 반려동물을 3D로 재현하고 AR로 곁에 두는 캡스톤 프로젝트다(중앙대학교 소프트웨어학부, 팀 Beside). 작업 규칙과 계약 변경 규칙은 [CLAUDE.md](CLAUDE.md)를 먼저 읽는다.

```text
Beside/
├─ CLAUDE.md        공통 작업 원칙, 아키텍처, 계약 변경 규칙
├─ docs/            CONTRACT.md(계약 통합본), 아키텍처, 단계별 계획·점검표, openapi.yaml, GLB 에셋 계약, 측정 항목
├─ backend/         Spring Boot 3.2 / Java 17 — API v0(동결)·v1, Mock/Real 워커, 브라우저 Mock UI
├─ unity/           Unity 6 Android AR 앱 (앱 코드는 Assets/Beside, 셸 털·털 측정 도구 FurLab), 문서 docs/·앱 실험 기록 experiments/
├─ generation/      3D 생성 트랙 — FastAPI 추론 서비스(AnimalLift), GLB 변환·리깅 도구, 실험 기록
├─ deploy/          VM 배포 — Docker Compose, Caddy(HTTPS·Basic 인증), 절차
└─ scripts/         e2e_mock.ps1 / e2e_mock.sh(업로드 → 폴링 → GLB 다운로드), GPU PC·VM 실행 스크립트, 털 측정 분석(analyze_fur_bench.py·make_rating_set.py)
```

## 현재 상태 (2026-11-26 기준)

발표는 2026-12-04, 종강은 12-21 이다. 발표까지 남은 일과 담당은 [docs/CHECKLIST.md](docs/CHECKLIST.md) 0절, 단계·완료 기준은 [docs/PLAN.md](docs/PLAN.md).

- **[구현됨] 서버·계약**: Spring v1 `/api/v1`(Unity·브라우저가 쓰는 계약, v0 `/api/jobs` 는 동결), Mock·Real 워커, H2 파일 DB, 실행별 측정(`job_runs`·`events.jsonl`), 계약 테스트. 계약 안건 #1~#23 은 2026-11-21 계약 회의 대신 Backend 담당이 정했다([docs/CONTRACT.md](docs/CONTRACT.md) §7). 태그 `api-v1.0`(3ae3ca1)·`api-v1.1`(fe10683, 큐 상한 — 운영은 꺼 둠)·`api-v1.2`(f6dfa66, 2026-11-22: 대기 정보·취소·공유·보관함·관리·품질 평가)가 main 에 있다([docs/api/CHANGELOG.md](docs/api/CHANGELOG.md)).
- **[구현됨] 운영**: GCP VM `https://beside-team.duckdns.org`(main `a44b3ed`, 2026-11-26 00:04~00:08 UTC 배포 창 2, real·prod, 추론 시간 제한 120 s) → Tailscale → Runpod A40 Pod 의 AnimalLift + GLB 변환(털 맵은 GLB 안) + 자동 리깅(템플릿이 있을 때만, 번호 맞추기 도구 PR #30). Pod 는 쓸 때만 켠다. 배포·백업·감시는 [deploy/README.md](deploy/README.md), 장애 대응은 [docs/ops/RUNBOOK.md](docs/ops/RUNBOOK.md). 서버 이중화는 2026-11-26 사용자 결정으로 발표 전 Phase 1 까지 실행한다([docs/ops/REDUNDANCY.md](docs/ops/REDUNDANCY.md)): 5분 백업 **[구현됨: 적용]**(배포 창 1), 대기 VM `beside-standby` 만듦(운영과 같은 커밋·이미지, Tailscale 로그인, 전환 DRY_RUN 통과), 전환 훈련 **[계획]**.
- **[실험 결과]**: AnimalLift 베이스라인 [exp-01](generation/experiments/2026-11-17_exp-01_baseline-runpod.md)(A40 시간·VRAM), VM 부하 시험 1~5차 [docs/perf/load/](docs/perf/load/)(5차: 큐 상한 19 판정 통과, 장애 재현), 장애 훈련 [docs/ops/2026-11-20_failure-drills.md](docs/ops/2026-11-20_failure-drills.md), 스테이징 터뜨려 보기 S1·S2 [docs/ops/2026-11-26_staging-drills.md](docs/ops/2026-11-26_staging-drills.md), GPU Pod 로그·작업 트레이스 확인 [docs/ops/data/2026-11-26_pod-log-check/](docs/ops/data/2026-11-26_pod-log-check/README.md), 3D 생성 모델 비교·방해 요소·같은 개 일관성 [generation/experiments](generation/experiments/README.md), 백엔드 단독 성능 [docs/perf/README.md](docs/perf/README.md). 털 렌더링 성능(SM-S947N 1대)은 [unity/experiments](unity/experiments/README.md)에 있다(이 위치를 [CLAUDE.md](CLAUDE.md) 의 [실험 결과] 정의에 넣을지는 결정 전).
- **Unity 앱 [미검증]**(Unity 트랙 표기: 코드는 main 에 있고 컴파일만 확인): 업로드 → 폴링 → 다운로드 → AR 배치, 애니메이션 5종, 셸 털 v2·그림자 바닥면·FurLab(PR #29), 디자인 UI 시안 E(PR #31). main 코드의 Unity 6 에디터 Play·실기기 확인은 [계획]. 털 자세히: [unity/docs/FUR_RENDERING.md](unity/docs/FUR_RENDERING.md).
- **[계획] 발표 전**: 실기기 전체 흐름 영상·logcat, 앱 `metrics.csv` 10회, 품질 평가(exp-02, 평가자 2명 이상), 측정표 3종, 리허설, 이중화 전환 훈련(REDUNDANCY 조각 1-5: 훈련 D-1 넘기기·D-3 되돌리기). **발표 뒤**: 큐 상한을 운영에서 켜기(Unity 새 빌드 뒤), 요청 제한, 보존 정책 자동 삭제, 이중화 Phase 2·3.
- **직접 할 일**: `backend/storage/results/sample-dog.glb`는 0바이트다. [GLB_SPEC](docs/asset/GLB_SPEC.md)을 만족하고 라이선스 문제없는 GLB로 바꿔야 Mock 흐름에서 Unity 로드를 확인할 수 있다.

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
| 공통 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PLAN.md](docs/PLAN.md), 계약 안건과 결정 [docs/api/REVIEW_CHECKLIST.md](docs/api/REVIEW_CHECKLIST.md) |
