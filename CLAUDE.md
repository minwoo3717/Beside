# Beside — 사진 기반 반려동물 3D 재현 및 AR 동행 서비스

중앙대학교 소프트웨어학부 캡스톤디자인 팀 "Beside"의 모노레포다. Backend(Spring) / Unity(Android AR) / 3D Generation(AnimalLift) 세 트랙이 병렬로 개발하며, 트랙 사이의 경계면은 `docs/`의 계약 문서와 backend 계약 테스트로 고정한다. 이 파일은 모든 트랙에 공통인 규칙만 담고, 트랙별 실행·테스트 명령은 각 폴더의 `CLAUDE.md`에 있다.

## 작업 원칙

- 캡스톤 수준에서 실제 구현 가능한 방향을 우선한다.
- 새로운 기능 추가보다 MVP 완성도를 우선한다.
- Backend, 3D Generation, Unity AR이 연결되는 전체 시스템 관점에서 판단한다.
- 구현된 내용, 실험 결과, 계획 중인 내용을 명확하게 구분한다.
- 실험하지 않은 내용을 사실처럼 작성하지 않는다.
- 3D 품질은 얼굴, 귀, 체형, 털 색상/무늬 등 구체적인 기준으로 평가한다.
- 개선 방법을 제안할 때는 실패 사례 → 원인 가설 → 개선 → 비교 실험 순서로 접근한다.
- 생성 시간, GPU 사용량, 모델 크기, Unity 로딩 시간, FPS 등 가능한 항목은 수치로 측정한다.
- 실제 3D Worker가 완성되지 않아도 Mock을 사용해 전체 서비스 흐름을 먼저 구현한다.
- Animation, Rigging 등은 핵심 기능 완료 후 진행하는 확장 기능으로 본다.
- 발표와 보고서는 문제 → 구현 → 실험 → 결과 → 개선 흐름으로 구성한다.

## 아키텍처 (결정됨 — 재논의하지 않는다)

Unity Android 앱(ARCore, glTFast) ↔ Spring Boot 공개 API(Job 상태·저장·에셋 서빙) ↔ Python 추론 서비스(FastAPI, GPU, AnimalLift + GLB 변환, 내부 전용 `POST /infer`). Spring은 profile로 `mock | real` 워커를 교체하고, real 워커가 Python `/infer`를 호출한다. Unity는 Spring API 계약(v1)만 보며 Python 서비스를 직접 호출하지 않는다. 그림과 데이터 흐름은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 팀 역할

| 트랙 | 범위 |
|---|---|
| Backend (Spring) | API, Job 상태머신, 저장소, Mock/Real 워커, 계약 테스트 |
| Unity (Android/AR) | 갤러리 선택 → 업로드 → 폴링 → GLB 다운로드/캐시 → AR 배치 |
| 3D Generation (AnimalLift) | 베이스라인 재현 → 품질/속도 개선 실험 → `/infer` 서비스 → GLB 변환 |

## 디렉터리 맵

```text
Beside/
├─ CLAUDE.md                      # 이 파일 (공통 규칙)
├─ README.md                      # 프로젝트 소개와 진입점
├─ docs/
│  ├─ CONTRACT.md                 # 계약 통합본 (사람이 읽는 한 파일: API·오류 코드·GLB·측정·/infer·변경 규칙)
│  ├─ ARCHITECTURE.md             # 시스템 그림(mermaid), 데이터 흐름, 프로파일
│  ├─ PLAN.md                     # 단계별 계획·주차·담당·완료 기준(DoD)
│  ├─ METRICS.md                  # 세 트랙이 같은 이름으로 쓰는 측정 항목
│  ├─ api/openapi.yaml            # API v1 계약 — 단일 진실 공급원
│  ├─ api/ERROR_CODES.md          # error.code 목록과 앱 표시 문구
│  ├─ api/CHANGELOG.md            # 계약 변경 이력 (git tag api-v1.x 와 짝)
│  ├─ api/REVIEW_CHECKLIST.md     # 계약 회의 검토 안건
│  └─ asset/GLB_SPEC.md           # GLB 에셋 계약
├─ backend/                       # Spring Boot 3.2 / Java 17 — v0(/api/jobs, 동결) + v1(/api/v1) + Mock 워커
├─ unity/                         # Unity 트랙. Assets/Beside/ 를 Unity 프로젝트의 Assets/Beside 로 가져간다
├─ generation/                    # 3D 트랙: inference_service/(FastAPI), tools/(convert_asset.py 자리), experiments/(실험 기록)
└─ scripts/                       # e2e_mock.ps1 / e2e_mock.sh — curl 로 업로드→폴링→다운로드 전체 흐름
```

## 계약 변경 규칙 (API v1)

- 단일 진실 공급원은 [docs/api/openapi.yaml](docs/api/openapi.yaml)이다. 코드가 아니라 문서를 먼저 고치고, backend 계약 테스트(`JobV1ContractTest`)가 springdoc의 `/v3/api-docs`와 문서를 비교한다.
- 응답 필드 **추가**는 PR 설명에 한 줄 기록한 뒤 자유롭게 한다. 클라이언트는 모르는 필드를 무시한다.
- 기존 필드의 **이름/타입 변경·삭제, enum 값 변경, 상태 코드 변경**은 주 1회 계약 회의에서만 결정한다. 결정 후 [docs/api/CHANGELOG.md](docs/api/CHANGELOG.md)에 기록하고 `git tag api-v1.x`를 올린다.
- 모든 enum: 클라이언트는 알 수 없는 값을 받으면 크래시 대신 `UNKNOWN`으로 처리한다.
- 서버 내부 경로(`resultPath` 등)는 어떤 응답에도 노출하지 않는다.
- 기존 v0 `/api/jobs`는 동결 상태다(변경 금지). Unity가 v1으로 옮긴 뒤 계약 회의에서 제거 시점을 정한다.
- GLB 에셋 계약([docs/asset/GLB_SPEC.md](docs/asset/GLB_SPEC.md))과 측정 항목 이름([docs/METRICS.md](docs/METRICS.md))도 같은 규칙으로 바꾼다.

## 상태 표기 규칙

문서·보고서·커밋 설명에서 다음 세 표기를 구분한다.

- **[구현됨]** 코드와 테스트가 이 리포에 있고 실행된다.
- **[실험 결과]** 측정값과 조건이 `generation/experiments/` 또는 `docs/`에 기록되어 있다.
- **[계획]** 아직 하지 않았다.

아직 하지 않은 것을 한 것처럼 쓰지 않는다. 숫자 상한(삼각형 수, 파일 크기 등) 중 "제안값"으로 표시된 것은 계약 회의에서 확정하기 전까지 임시값이다.

## 실행·테스트 요약

```powershell
# Backend (기본 profile = mock)
cd backend
.\gradlew.bat test
.\gradlew.bat bootRun
# 브라우저 Mock UI(v1) 흐름 테스트
node --test backend/src/test/js/mock-mvp.test.cjs
# 서버가 떠 있을 때 v1 전체 흐름
.\scripts\e2e_mock.ps1
```

## Claude Code 작업 시 유의

- 작업 전에 해당 트랙의 `CLAUDE.md`와 [docs/PLAN.md](docs/PLAN.md)의 현재 단계를 읽는다.
- 계약(openapi.yaml, GLB_SPEC.md, METRICS.md)에 영향이 있으면 문서를 먼저 고치고 계약 테스트를 돌린다.
- 기존 테스트를 깨면서 진행하지 않는다. 테스트를 바꿔야 하면 이유를 PR에 적는다.
- 커밋 메시지는 `feat:`, `fix:`, `docs:`, `test:`, `chore:` 접두사로 시작한다.
