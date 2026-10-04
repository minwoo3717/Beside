# 단계별 계획 (PLAN)

기준일: **2026-10-06(월) 주차 시작**. 표기: **[구현됨]** 코드·테스트가 리포에 있음 / **[실험 결과]** 측정값이 기록됨 / **[계획]** 아직 안 함 / **직접 할 일** 코드로 해결되지 않아 사람이 해야 함. 아직 하지 않은 것을 한 것처럼 쓰지 않는다. 이 문서는 주 1회 계약 회의에서 갱신한다.

## 한눈에 보기

| 단계 | 기간 | 주 담당 | 핵심 산출물 | 상태 (2026-10-04) |
|---|---|---|---|---|
| 0 계약·Mock 고도화 | 10-06 ~ 10-12 (1주) | Backend, 전 트랙 리뷰 | API v1 동결, Mock 서버 v1, 문서 뼈대 | 계약·코드·문서 **[구현됨]**, H2·events.jsonl·GLB 교체 **[계획]** |
| 1 Unity Mock 연동 | 10-13 ~ 10-26 (2주) | Unity | 갤러리 → 업로드 → 폴링 → 다운로드 → 로드 (Mock) | **[계획]** |
| 2 AR 배치 | 10-27 ~ 11-09 (2주) | Unity | AR 평면 배치, 스케일/회전, metrics.csv | **[계획]** |
| 3 AnimalLift 연구 | 10-06 ~ 11-08 (5주, 병행) | 3D Generation | 베이스라인 재현, 개선 실험 ≥ 2, GLB 변환 | **[계획]** |
| 4 실 연동 | 11-09 ~ 11-22 (2주) | Backend + Generation, Unity 검증 | real 워커 ↔ `/infer`, 실제 GLB 서빙 | **[계획]** |
| 5 측정·보고 | 11-23 ~ 12-06 (2주) | 전원 | 측정표, 발표, 보고서, 데모 | **[계획]** |

```mermaid
gantt
    dateFormat  YYYY-MM-DD
    title Beside 2026 가을 일정
    section Backend
    0 계약·Mock 고도화        :s0, 2026-10-06, 7d
    4 실 연동 (real 워커)      :s4b, 2026-11-09, 14d
    section Unity
    1 Unity Mock 연동          :s1, 2026-10-13, 14d
    2 AR 배치                  :s2, 2026-10-27, 14d
    section 3D Generation
    3 AnimalLift 연구 (병행)   :s3, 2026-10-06, 34d
    4 실 연동 (/infer)         :s4g, 2026-11-09, 14d
    section 전원
    5 측정·보고                :s5, 2026-11-23, 14d
```

의존 관계: 0 → 1 → 2, 0 → 3(병행), (2, 3) → 4 → 5. 3단계는 GPU 서버 확보(직접 할 일)에 의존한다.

---

## 0단계 — 계약·Mock 고도화 (2026-10-06 ~ 10-12)

담당 Backend. 리뷰 Unity·Generation(계약 회의 1회, 안건은 [docs/api/REVIEW_CHECKLIST.md](api/REVIEW_CHECKLIST.md)). 의존 없음 — 다른 모든 단계의 전제.

| 항목 | 상태 | 비고 |
|---|---|---|
| API v1 계약 `openapi.yaml`, `ERROR_CODES.md`, `CHANGELOG.md` | **[구현됨]** 2026-10-04 | 계약 회의 후 `git tag api-v1.0` |
| Spring v1 엔드포인트(create/get/asset/retry/list/healthz), Mock 워커(실패 트리거, Idempotency-Key, timings) | **[구현됨]** | `JobV1ApiTest` |
| 계약 테스트 `openapi.yaml` ↔ `/v3/api-docs` | **[구현됨]** | `JobV1ContractTest` |
| profile `mock \| real` 분리, real 워커 스텁 | **[구현됨]** | 실제 `/infer` 호출은 4단계 |
| `GLB_SPEC.md`, `METRICS.md`, `ARCHITECTURE.md` | **[구현됨]** | 숫자 상한은 제안값 |
| Unity 계약 DTO (`unity/Assets/Beside/Api`) | **[구현됨]** | 알 수 없는 enum → Unknown |
| FastAPI `/infer` 스텁 (501 + 패스스루) | **[구현됨]** | 모델 로드는 3단계 |
| e2e 스크립트 (`scripts/e2e_mock.ps1`, `.sh`) | **[구현됨]** | |
| 0바이트 `sample-dog.glb` → GLB_SPEC 을 만족하는 유효 GLB 로 교체 | **직접 할 일** | Unity 로드 검증의 전제 |
| `convert_asset.py` 투입 (`generation/tools/`) | **직접 할 일** | 별도 세션 결과물, 인터페이스 맞추기 |
| Unity 프로젝트 생성(Unity Hub 2022.3 LTS, 패키지 설치) | **직접 할 일** | 1단계 선행 |
| GPU 서버 확보 (학교/클라우드), 접속 정보 | **직접 할 일** | 3단계 선행 |
| 메모리 저장소 → **H2 파일 DB** (Job + Idempotency-Key 영속화) | **[계획]** | 서버 재시작 후에도 `GET /jobs/{id}` 200 |
| `events.jsonl` 기록기 (METRICS §1) | **[계획]** | COMPLETED/FAILED 시 한 줄 |
| 브라우저 Mock UI `app.js` → v1 전환 | **[계획]** | `mock-mvp.test.cjs` URL 단언 갱신 포함 |

완료 기준(DoD):
- [ ] 계약 회의에서 REVIEW_CHECKLIST 결정 → `CHANGELOG.md` 반영 → `git tag api-v1.0`
- [ ] `cd backend; .\gradlew.bat test` 전부 통과, `node --test backend/src/test/js/mock-mvp.test.cjs` 통과
- [ ] `scripts/e2e_mock.ps1` 이 bytes > 0 인 GLB 를 받는다 (샘플 교체 후)
- [ ] 서버 재시작 후 기존 jobId 조회가 200 (H2)
- [ ] Unity 프로젝트에서 `Assets/Beside` 가 오류 없이 컴파일된다

## 1단계 — Unity Mock 연동 (2026-10-13 ~ 10-26)

담당 Unity. 의존: 0단계 계약 동결, Mock 서버, 유효 GLB.

산출물: NativeGallery 로 사진 선택(1~10장, 타입·용량 사전 검사) → `POST /api/v1/jobs` → 폴링(`JobStatus`, Unknown 처리) → `asset.url` 다운로드·캐시 → glTFast 로드(비AR 테스트 씬) → `error.code` 문구(ERROR_CODES) → retry 버튼 → `metrics.csv` 의 `uploadMs, waitMs, downloadMs, loadMs, e2eMs`.

| 주차 | 내용 |
|---|---|
| 10-13 ~ 10-19 | Unity 프로젝트·패키지, `BesideApiClient` 구현(업로드/조회/다운로드), 에디터에서 Mock 서버 전체 흐름 |
| 10-20 ~ 10-26 | NativeGallery, 실기기 연결(network_security_config), 오류/실패/retry UI, glTFast 로드, metrics.csv |

DoD:
- [ ] Android 실기기에서 PC Mock 서버로 전체 흐름 성공 (영상 + `adb logcat`)
- [ ] 파일명 `fail` 사진으로 FAILED → 문구 → retry → 다시 FAILED 흐름이 크래시 없이 동작
- [ ] 서버 꺼짐 / 404 / 409 / 413 각각 문구 표시
- [ ] `metrics.csv` 5개 열, 10회 측정값 표 **[실험 결과]** 로 기록

## 2단계 — AR 배치 (2026-10-27 ~ 11-09)

담당 Unity. 의존: 1단계 glTFast 로드 성공.

산출물: AR Foundation 평면 감지 → 탭 배치(원점 = 발바닥 접지) → 기본 스케일 0.6, 핀치 0.2~1.5, 회전 → 초기 정면이 카메라를 향함 → `avgFps, minFps, memMB` 기록 → (선택) `hair` 토글.

DoD:
- [ ] 실기기 바닥 평면 배치, 30초 측정 `avgFps` (목표 ≥ 30, 제안값) **[실험 결과]**
- [ ] 스케일·회전·재배치 조작
- [ ] `metrics.csv` 10열 모두 채움, 기기 2종 이상

## 3단계 — AnimalLift 연구 (2026-10-06 ~ 11-08, 병행)

담당 3D Generation. 의존: GPU 서버, 논문 공개 코드. 기록은 `generation/experiments/` (TEMPLATE 순서).

| 주차 | 내용 | 기록 |
|---|---|---|
| 10-06 ~ 10-12 | 환경 구축, 공식 코드 실행, 샘플 1건 생성 | exp-01 |
| 10-13 ~ 10-19 | 베이스라인 재현: 우리 사진 3마리 이상, 품질 체크리스트 채점, `inferMs/gpuPeakMB/modelParams/outputTriangles` | exp-02 |
| 10-20 ~ 11-01 | 개선 실험 ≥ 2건 — 실패 사례 → 가설 → 개선 → 비교. 후보(확정 아님): 입력 전처리(배경 제거·크롭), 입력 장수, 텍스처 해상도, 메시 후처리(decimation) | exp-03, exp-04 |
| 11-02 ~ 11-08 | `convert_asset.py` 연결, GLB_SPEC 통과, `/infer` 패스스루 → 실제 모델 전환 준비 | exp-05 |

DoD:
- [ ] exp-01 ~ exp-05 기록, 표 채움 **[실험 결과]**
- [ ] 베이스라인 수치와 환경 명시
- [ ] 품질 6항목 평균(평가자 2명 이상) baseline vs 개선안
- [ ] GLB_SPEC 통과 GLB 3건 이상, gltf-validator 오류 0

## 4단계 — 실 연동 (2026-11-09 ~ 11-22)

담당 Backend + Generation, Unity 검증. 의존: 3단계 `/infer` 동작, 0단계 H2.

산출물: `RealJobWorker` 구현(PROCESSING 전이, `/infer` 호출·타임아웃, 오류 코드 매핑, GLB 복사, `events.jsonl`), 배포 구성(같은 호스트 또는 공유 볼륨 — 불가 시 `/infer` 바이너리 응답으로 변경), Unity 에서 real 프로파일 전체 흐름.

DoD:
- [ ] `--spring.profiles.active=real` 로 `e2e_mock.ps1` 이 실제 생성 GLB 를 받는다
- [ ] 실패 경로 4종(`INFERENCE_FAILED / INFERENCE_TIMEOUT / INFERENCE_UNAVAILABLE / CONVERSION_FAILED`) 테스트
- [ ] Unity 실기기에서 실제 모델 AR 배치 (영상)
- [ ] `events.jsonl` 작업 10건 이상 **[실험 결과]**

## 5단계 — 측정·보고 (2026-11-23 ~ 12-06)

담당 전원. 의존: 4단계.

산출물: METRICS 전 항목 측정표(서버·추론·앱), 품질 평가표, 발표 자료·보고서(**문제 → 구현 → 실험 → 결과 → 개선**), 데모 영상.

DoD:
- [ ] 측정표 3종 + 환경 명시, 실험 기록과 숫자 일치
- [ ] 발표 리허설 1회, 보고서 초안 리뷰
- [ ] Animation·Rigging 은 "향후 과제"로만 기술

---

## 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| GPU 서버 확보 지연 | 3·4단계 지연 | 패스스루 모드로 Spring↔Python 연동을 먼저 끝낸다. 클라우드 GPU 시간제 사용 검토 |
| AnimalLift 재현 실패·품질 미달 | 핵심 가치 | 실패 사례를 실험 기록으로 남기고 전처리·후처리 개선에 집중. 최악의 경우 베이스라인 결과로 데모 |
| 생성 시간 수 분 | UX | `progress` 와 예상 시간 표시, 완료 알림. 폴링 간격 2초, 최대 대기 600초 |
| 파일시스템 공유 가정 불가 | 4단계 설계 | `/infer` 를 멀티파트 업로드 + GLB 바이너리 응답으로 변경(계약 회의) |
| 메모리 저장소 재시작 유실 | 데모 중단 | 0단계 H2 전환 |
| 기기 성능 (FPS) | 2단계 | 삼각형 상한 하향, 텍스처 1024 로 축소 실험 |
