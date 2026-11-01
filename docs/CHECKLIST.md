# 프로젝트 점검표 (CHECKLIST)

기준일 **2026-10-31(토)**, 부하 시험 항목(2·5절)은 2026-11-01 추가, 2026-11-02 갱신(k6 v2.3.0 실제 실행으로 스크립트 확인·수정). 단계·기간·완료 기준(DoD)의 원본은 [PLAN.md](PLAN.md)이고, 이 문서는 "지금 무엇이 빠져 있고 누가 무엇을 해야 하나"를 한곳에 모은다. 주 1회 계약 회의 때 PLAN 과 함께 갱신한다.

- 표기: **[구현됨]** 코드·테스트가 리포에 있고 실행된다 / **[실험 결과]** 측정값과 조건이 기록돼 있다 / **[계획]** 아직 안 함 / **직접 할 일** 사람이 해야 함(하드웨어·계정·회의).
- 담당: **BE** Backend · **UN** Unity · **GEN** 3D Generation · **전원**.
- 근거는 `파일:줄`로 적었다. 탐색 시점(10-30~31)의 줄 번호라 이후 수정으로 조금 밀릴 수 있다.

## 1. 남은 기간

| 마감 | 날짜 | 10-31 기준 남은 일수 |
|---|---|---|
| 3단계(AnimalLift 연구) 끝 | 11-08 | 8일 |
| 2단계(AR 배치) 끝 | 11-09 | 9일 |
| 4단계(실 연동)·4b(배포·확장) 끝 | 11-22 | 22일 |
| 5단계(측정·보고) | 11-23 ~ 12-06 | 23 ~ 36일 |
| 발표·보고서 제출일 | **리포에 없음** — 학과 일정 확인 필요 | — |

지연: 0단계 DoD(계약 회의·태그) 18일, 1단계 DoD 증빙 5일, exp-01 19일. 3단계 기록이 0건이라 4·5단계의 모든 수치(생성 시간·VRAM·품질)가 그 뒤에 막혀 있다.

## 2. 이번 주 우선 (11-01 ~ 11-07)

막는 범위가 큰 것부터.

1. [ ] **계약 회의 1회** — [REVIEW_CHECKLIST](api/REVIEW_CHECKLIST.md) 안건 결정(결정 칸 21개가 모두 비어 있음) → `CHANGELOG.md` → `git tag api-v1.0`. 이것이 v1.1 병합(`review/api-v1.1`), GLB_SPEC 리깅 반영, 측정 이름 확정을 막고 있다. 2026-11-01 에 부하·운영 안건 **#19 큐 상한(503 `QUEUE_FULL`)·#20 요청 제한(429 `RATE_LIMITED`)·#21 업로드·GLB 보존 정책**을 더했다(#15 에 `load.*` 이름 포함). (BE 주관, 전원)
2. [ ] **GPU PC 세팅 + exp-01** — 1건 생성 시간·VRAM·출력 형식 기록. `inference.timeout-ms`(안건 #7)와 앱 최대 대기의 근거이고, 실제 E2E·품질 평가의 전제다. (GEN, 직접 할 일)
3. [ ] **`deploy/vm-4b` → main PR 열기** — CI(`.github/workflows/backend-ci.yml`)는 main push·main 대상 PR 에서만 돈다. Docker 이미지 빌드와 Caddy 경로 시험이 이때 처음 실행된다. (BE)
4. [ ] **VM·도메인·Tailscale 준비(11-04 까지)** — [deploy/README.md](../deploy/README.md) 순서대로. 폰 → VM → GPU PC 1건이 4b 의 첫 증빙이다. VM 이 있어야 부하 시험(아래 5절, 보고용 수치는 VM 에서만 잰다)도 시작할 수 있다. (BE, 직접 할 일)
5. [ ] **Unity 1단계 DoD 증빙** — 실기기 Mock 전체 흐름(영상 + `adb logcat`), `fail` 사진 → FAILED → retry, `metrics.csv` 10회. (UN)
6. [ ] **Unity 재시도 버그** — 업로드 단계에서 네트워크 오류가 나면 `Fail()` 이 `CurrentJobId` 를 갱신하기 전에 불려(`unity/Assets/Beside/Job/JobManager.cs:204-207`), 화면의 "다시 시도"가 아무것도 하지 않거나(첫 작업) **이전 작업을 다시 폴링**한다(`JobManager.cs:131-133`). 업로드 실패 때는 같은 사진으로 업로드를 다시 하도록 고친다. (UN)
7. [ ] **발표·보고서 제출일 확인** → 5단계 일정 확정. (전원)

## 3. 단계별 완료 기준 점검

| 단계 | 완료 기준 | 상태 | 빠진 것 | 담당 · 막힘 |
|---|---|---|---|---|
| 0 | 계약 회의 → CHANGELOG → `api-v1.0` 태그 | [ ] | 회의·결정 기록·태그 모두. `git tag -l` 결과 없음 | BE · 회의 |
| 0 | gradlew test + node test | [x] | GitHub CI 는 아직 한 번도 돌지 않았다(위 3번) | BE |
| 0 | `e2e_mock` 이 bytes > 0 인 GLB 를 받음 | [ ] | `backend/storage/results/sample-dog.glb` 가 0 B. 리깅 GLB 는 SMAL 파생이라 커밋 금지 → 라이선스 문제없는 GLB 를 정해야 함 | BE+GEN · 라이선스 결정 |
| 0 | 재시작 후 jobId 200 | [x] | — | — |
| 0 | `Assets/Beside` 컴파일 | [ ] | 증빙 없음(에디터 스크린샷 또는 빌드 로그) | UN |
| 1 | 실기기 Mock 전체 흐름(영상·logcat) | [ ] | `unity/README.md` 의 "검증됨" 주장만 있고 리포에 증빙 없음 | UN · 기기 |
| 1 | fail → retry 흐름 | [ ] | 위 재시도 버그 수정 후 확인 | UN |
| 1 | 서버 꺼짐/404/409/413 문구 | [ ] | 문구 코드는 있음(`ErrorMessages.cs`), 실기기 미확인 | UN |
| 1 | `metrics.csv` 5열 10회 [실험 결과] | [ ] | 기록 코드 있음(`MetricsRecorder.cs`), 데이터 0건 | UN |
| 2 | 30초 `avgFps` ≥ 30 [실험 결과] | [ ] | 측정 0건 | UN · 기기 |
| 2 | 스케일·회전·재배치 | [ ] | 증빙 없음. 핀치 범위가 PLAN(0.2~1.5)과 프리팹(`m_MinScale` 0.3)에서 다름 | UN |
| 2 | `metrics.csv` 10열, 기기 2종 이상 | [ ] | 데이터 0건. minSdk 34 라 Android 14 이상만 설치됨(`ProjectSettings.asset:184`) | UN · 기기 |
| 3 | exp-01 ~ 04 기록 | [ ] | `generation/experiments/` 에 기록 0건 | GEN · GPU PC |
| 3 | 베이스라인 수치·환경 | [ ] | 측정 0건 | GEN |
| 3 | 품질 6항목(평가자 2명 이상) baseline vs 개선 | [ ] | 0건. 정면·측면·위 렌더 필요. 평가 입력 화면은 `review/api-v1.1` 에만 있음 | GEN+전원 |
| 3 | GLB_SPEC 통과 GLB 3개, gltf-validator 오류 0 | [ ] | 변환기 테스트·검증 기록 없음. 실제 출력(리깅)이 GLB_SPEC("스킨 없음")과 다름 → 안건 #14 | GEN · 회의 |
| 4 | real 에서 실제 생성 GLB 수신 | [ ] | 패스스루로만 확인 | BE+GEN · GPU PC |
| 4 | 실패 경로 4종 | [x] | — | — |
| 4 | 실기기 실제 모델 AR(영상) | [ ] | — | UN · GPU PC |
| 4 | `events.jsonl` 10건 이상 [실험 결과] | [ ] | 실측 0건 | BE+GEN |
| 4b | S1~S3 | [구현됨] | VM·GPU PC 연결에서 실행한 적 없음 | BE |
| 4b | S4 Docker·Caddy·CI | 파일만 | 이미지 빌드·Caddy 시험 미실행(위 3번) | BE |
| 4b | S5~S9 | 검토 브랜치 | 계약 회의 #11~#13 뒤 병합 | BE · 회의 |
| 4b | 실측 10건 이상 | [ ] | VM·GPU PC 준비 뒤 | BE+GEN |
| 5 | 측정표 3종 + 환경 | [ ] | 서버·추론·앱 측정 모두 0건(VM·GPU·기기). 백엔드 단독 성능(개발 PC 루프백)만 [실험 결과] [perf/backend-perf-report.pdf](perf/backend-perf-report.pdf) | 전원 |
| 5 | 리허설 1회, 보고서 초안 리뷰 | [ ] | 날짜 미정 | 전원 |
| 5 | "Animation·Rigging 은 향후 과제로만" | 충돌 | 리깅·애니메이션 5종이 #1 로 이미 머지됨 → 보고서에서 어떻게 쓸지 결정 | 전원 · 팀 결정 |
| 5 | 데모 영상 | [ ] | — | 전원 |

## 4. 트랙별 할 일

### Backend (BE)
- [ ] 계약 회의 주관(위 2-1). 회의 뒤 `openapi.yaml` → `JobV1ContractTest` → CHANGELOG → 태그 순서.
- [ ] `review/api-v1.1`(S5~S9) 병합 방식 결정: 전부 / 앞 조각부터 일부 / 보류. 결정 자료는 리포 루트 `OVERNIGHT_REVIEW_2026-10-31.md`(커밋 안 됨).
- [ ] 0 B `sample-dog.glb` 교체(GEN 과 함께, 라이선스 문제없는 모델).
- [ ] VM 배포 후 밖에서 `/h2-console`·`/swagger-ui.html`·`/api/jobs`·`GET /api/v1/jobs` 가 404/401 인지 확인([deploy/README.md](../deploy/README.md)).
- [ ] 남은 낮은 위험(10-30 검토): retry 를 동시에 두 번 누르는 경합, GPU 오프라인 대기가 N건이면 N×30분, V2 `RealJobQueueTest` 간헐 실패 1/20(원인 미확정).
- [ ] 측정 기간에는 재배포하지 않는다(재시작하면 진행 중 작업이 `INTERNAL_ERROR`). 측정 전후 H2 백업을 개발 PC 로 복사.
- [ ] 전체 코드 리뷰(2026-10-31)의 [계획] 항목: real 로 쓸 때 Mock UI 의 60초·15초 제한, 측정 중 한 번 본 'COMPLETED 직후 에셋 409'(재현부터) — [review/2026-10-31-code-review.md](review/2026-10-31-code-review.md).

### Unity (UN)
- [ ] 재시도 버그(위 2-6).
- [ ] 빌드 기본 서버 주소: 코드 `http://192.168.0.10:8080`(`JobManager.cs:25`), 씬 `http://localhost:8080` → VM 이 생기면 `https://<도메인>`.
- [ ] HTTP 허용 설정 정리: `insecureHttpOption` 이 Always allowed(`ProjectSettings.asset`), README 는 "릴리스 전 Not allowed". VM HTTPS 뒤 정리.
- [ ] minSdk 34 가 의도인지 확인(README 는 24).
- [ ] 커밋 안 된 `unity/ProjectSettings` 변경 정리(URP 전역 설정 목록이 비워짐, 나머지는 줄바꿈만 다름) — 담당자가 판단.
- [ ] DoD 증빙: 1·2·4단계 영상, logcat, `metrics.csv`, FPS, 기기 2종.
- [ ] 서버 생성 모델의 털: 앱은 GLB 안 `extras.beside` 를 기대하지만 변환기는 털 맵을 별도 PNG 로 쓴다 → 안건 #14 결정 후 한쪽에 맞춤(GEN 과 함께).
- [ ] (v1.1 을 받으면) 진행 화면에 `queue`/`estimate`, 취소 버튼이 `POST /cancel` 호출(지금은 앱 안 코루틴만 멈춤), `CANCELED` 문구.
- [ ] 코드 리뷰 보고 7건: 에셋 다운로드 실패 때 화면이 멈춤, 바뀐 템플릿·GltfImport 를 해제하지 않아 memMB 가 계속 오름, 재개·retry 실행도 `metrics.csv` 에 줄을 더함(METRICS §3 '작업당 한 줄') 등 — [review/2026-10-31-code-review.md](review/2026-10-31-code-review.md) 'Unity 트랙에 보고'.

### 3D Generation (GEN)
- [ ] GPU PC: AnimalLift·체크포인트 설치, ASCII 경로, WSL 이면 그 안에서 Tailscale, `BESIDE_HOST`·`BESIDE_INFERENCE_TOKEN`([deploy/README.md](../deploy/README.md)).
- [ ] exp-01(1건 시간·VRAM) → exp-02(우리 사진 3마리 이상, 실제 E2E 10건 이상, `job_runs` CSV) → exp-03·04(개선 2건 이상).
- [ ] 개선 후보(가설): 비정사각 사진을 정사각으로 늘려 넣음(`animallift_runner.py` 의 Resize) → 패딩·배경 제거와 비교, 털 맵을 GLB 안에 넣기.
- [ ] gltf-validator·glTFast 로드 결과 기록, 변환기 테스트.
- [ ] `/infer/upload` 리뷰(업로드 모드, DELETE 범위).
- [ ] 끊긴 `/infer` 가 MODEL_LOCK 을 쥔 채 다음 작업의 시간 제한을 깎는 문제: busy 신호 또는 끊긴 요청 건너뛰기 중 설계 결정(GEN·BE, 코드 리뷰 보고서).
- [ ] 재현성: 리깅 템플릿과 그 생성 스크립트가 리포 밖에 있음 → 위치와 절차를 문서로.
- [ ] 라이선스: AnimalLift 리포에 라이선스 파일이 없고, AnimalLift 출력(`unity/Assets/Models/n02085782_186 (1)_mesh.obj` 등)이 커밋돼 있다 → 팀 결정.

### 배포·운영
- [ ] 위 2-3, 2-4.
- [ ] `.env`: `BASIC_AUTH_HASH` 는 작은따옴표로, `INFERENCE_TOKEN` 은 GPU PC 와 같은 값.
- [ ] 첫 배포 후 GPU PC 를 끈 채 작업 1건 → PENDING 대기 + Discord 알림 → 켜면 진행되는지 확인.

### 계약·문서 (회의 안건)
- [ ] 안건 #1~#21 결정, #3 숫자 상한은 exp-02 수치가 있어야 정할 수 있다. #17(prod 의 사진 내용 검사)·#18(`modelVersion` 의 `+rig`)은 코드 리뷰에서, #19~#21(큐 상한·요청 제한·보존 정책)은 부하 시험 계획(2026-11-01)에서 나왔다.
- [ ] 계약 파일의 상태 표기가 실제 코드보다 늦음(아래 6절) — 계약 파일이라 회의에서 함께 고친다.
- [ ] 측정 정의 확정: `convertMs` 에 리깅 시간이 더해짐(`app.py`), 앱 `e2eMs` 가 첫 AR 배치까지(사용자 탭 포함), 앱 `downloadMs` 가 캐시 적중 때 0.
- [ ] 백엔드 벤치마크 이름(`bench.*`)과 부하 시험 이름(k6 `load.*`)을 METRICS 에 올릴지(안건 #15).

### 보고서·발표 (전원)
- [ ] 구성: 문제 → 구현 → 실험 → 결과 → 개선. 기록 없는 결과는 인용하지 않는다.
- [ ] 보고서에 밝힐 것: 사진은 첫 1장만 쓴다(`app.py`), 리깅은 템플릿이 있을 때만.
- [ ] 측정표 3종(서버·추론·앱) + 품질표 + 환경, 데모 영상, 리허설.

## 5. 백엔드 성능 측정

- 방법·버전 비교·다음 버전 측정 절차: [perf/README.md](perf/README.md). 보고서 PDF: [perf/backend-perf-report.pdf](perf/backend-perf-report.pdf).
- **[실험 결과]** 개발 PC 루프백에서 버전 5개 × 3회(2026-10-31, `perf/data/2026-10-31_1434`): 이전 → 현재 회귀 없음, 현재의 개선(P1 키셋 목록)으로 목록 첫 페이지 −72 %, 다음 버전(v1.1)의 업로드 비용은 P4(썸네일을 202 뒤로)로 v1 수준. 사례와 남은 비용: [perf/notes/10-improvements.md](perf/notes/10-improvements.md).
- [x] 다음 버전에 남은 비용 확인 **[실험 결과]** (2026-11-01): 응답마다 하던 썸네일 파일 확인(P6)과 PENDING 조회의 `@Query` 재파싱(P7)이 원인. `review/api-v1.1` 에 고쳐 폴링 처리량·단건 조회·목록은 v1 과 차이 없음, PENDING −24 %. [perf/notes/10-improvements.md](perf/notes/10-improvements.md).
- [ ] 아직 남은 v1.1 비용 [계획]: PENDING 조회 +74 %(조회 두 개를 문장 하나로, pending_poll 워밍업 늘려 정상 상태 측정), 썸네일 일과 CPU 공유(작은 업로드 +38 %, 동시 E2E −26 %: EXIF 읽기 생략·writer 재사용, 가짜 사진 대조 측정), 결과 GLB 크기 저장(항목당 `File.length` 약 0.1 ms, v1 부터 있던 비용). (BE)
- [ ] 2026-11-01 실행에서 다른 작업과 겹친 반복(v2p 2회차 등 13:30~13:46)을 다시 잴지 결정 — 결론은 바뀌지 않았다. (BE)
- [ ] VM(HTTPS·Caddy·Tailscale) 위에서 같은 시나리오 재측정 — VM 준비 뒤. (BE)
- [ ] 실제 GPU 로 `job_runs` 실측 10건 — 백엔드 단독 벤치마크와 별개로 METRICS §1.1 표를 채운다. (BE+GEN)
- [ ] v1.1 을 병합하거나 다음 버전이 생기면 `versions.json` 에 한 줄 더해 같은 명령으로 재측정([perf/notes/20-next-version.md](perf/notes/20-next-version.md)). (BE)

### 부하 시험 — 쏘카 수준 목표 (2026-11-01 추가)

멘토링 피드백 "쏘카 수준 트래픽을 목표로 설정하고 테스트"(지표 5종 + 대시보드, 장애 시나리오 5종)와 교수님 피드백 "부하·장애 테스트도 배포 환경에서"는 2026-11-01 전까지 리포에 기록이 없었다. 목표 수치(T1/T2/T3, 모든 비율 [가정])·합격 기준(제안값)·시험 구성은 [perf/load/TRAFFIC_MODEL.md](perf/load/TRAFFIC_MODEL.md), 작업은 브랜치 `load/socar`(PLAN 4b '부하·운영'). 보고용 측정은 모두 VM 이 준비된 뒤에 한다 — 루프백 벤치마크나 아래 로컬 동작 확인과 섞어 인용하지 않는다.

- [x] 스크립트 동작 확인 (2026-11-01~02): 개발 PC 에서 실제 k6 v2.3.0 으로 k6 스크립트 7개와 `collect.py`·F3/F4 도우미를 짧게 줄여 돌렸다(루프백, mock jar·패스스루 스텁·Prometheus 대역). 찾은 문제 — 중단된 실행이 'all passed' 로 적힘, 잘린 유지 구간이 pass, A3 의 4xx 거부가 통과, A3·A4 가 `PRE_VUS` 를 읽지 않음, A2 id 의 상태를 보지 않음, F3 느린 업로드가 폴링 뒤에 돎, 'GC 뒤 힙' 판정 CSV 없음 등 — 은 `load/socar` 에서 고치고 시험을 더했다 **[구현됨]**(Node 82·Python 84). 용량 결과가 아니므로 [실험 결과] 로 인용하지 않는다. (BE)
- [ ] A0~A4 기준 측정(연기 시험·혼합 흐름·폴링 포화·업로드·에셋 송신)을 VM 에서. (BE)
- [ ] G1(큐 이론 검증, 스텁 T=10초)·G2(GPU 증설 c = 1, 2, 4) — 추론 스텁, 실제 GPU PC 는 쓰지 않음. (BE, 스텁 설정은 GEN 리뷰)
- [ ] F1~F5 장애 시나리오(추론 실패·타임아웃, 추론 서비스 꺼짐·재시작, 잘못된 업로드, 다운로드 중단, 스파이크 + 한 IP 집중). F1b 는 MODEL_LOCK 문제로 실패가 예상되며 그대로 기록한다. (BE, F1·F2 는 GEN 함께)
- [ ] 보호 장치(큐 상한 #19, 요청 제한 #20) 적용 전후 비교(G3·F5) — 계약 회의 결정 뒤. (BE)
- [ ] exp-01 로 생성 시간 T 를 잰 뒤 `scripts/load/model.py` 로 GPU 대수·큐 상한 표를 다시 계산하고 #19 의 상한 값을 고친다. (GEN 이 T 측정, BE 가 재계산)
- [ ] VM 에서만 확인할 수 있는 것 **[계획]** — 이 PC 에는 Docker 가 없다. (BE)
  - F3 남은 파일 확인 명령(`f3_uploads.js` 가 출력하는 'VM:' 줄)의 컨테이너 안 경로 `/app/storage/uploads`·`/tmp/tomcat.8080.*`. 로컬에서는 경로만 바꿔 같은 명령을 확인했다.
  - 실제 Prometheus 에 `jvm_memory_usage_after_gc_percent{area="heap",pool="long-lived"}` 시계열이 있는지(`collect.py` 의 `jvm_heap_after_gc`, 'GC 뒤 힙 < 75 %' 판정). 이름은 로컬 `/actuator/prometheus` 에서만 봤다.
  - 서버를 죽였을 때 실제 Prometheus 의 staleness: Prometheus 대역은 마지막 수집값을 계속 내서 CSV 에 빈 구간이 보이지 않았다.
  - `SEED_ONLY` 의 `IDS_OUT` 검사가 기대는 k6 `open()` 오류 문구를 Linux k6 에서(Windows k6 v2.3.0 에서만 확인).
  - 부하 PC 메모리: 초기화된 VU 하나에 약 1.1 MB(개발 PC·k6 v2.3.0 에서 VU 초기화만 20→300 VU 로 잰 값)라 A1 기본 `MAX_VUS` 3,300 이면 약 3.6 GB + 본문 [파생]. A3 large 는 VU 마다 5 MB 사진 사본에 첫 업로드 때 20 MB 본문이 더해져 사전 할당 50 VU 면 약 1.25 GB [파생], VU 가 늘면 하나에 약 25 MB 씩 더. 시험 중 k6 메모리를 CPU 와 함께 본다.
- [ ] 판정 기준 결정 — 지금 코드의 동작은 **[구현됨]**, 바꿀지는 미정. (BE, 첫째는 계약 회의)
  - A3(`upload.js`)에서 429 `RATE_LIMITED`·503 `QUEUE_FULL` 거부를 유지 구간 실패로 볼지: 지금은 `load_rejectedCount` 로 따로 세고 기준이 없다(A3 기준은 `load_unexpectedCount == 0`, 전체·유지 구간마다). 안건 #19·#20 과 함께 정한다.
  - F1a 실행에서 `load_flowOkRatio >= 0.95` 를 그대로 둘지: 스텁 실패율 0.2 면 첫 시도와 retry 가 다 실패하는 흐름이 약 4 % 라 기대값이 약 0.96 [파생] 이고, 줄인 로컬 실행에서도 통과와 실패가 둘 다 나왔다.
  - `collect.py` 의 `http_5xx_ratio` 질의에 `or vector(0)` 을 붙일지: 5xx 가 하나도 없는 건강한 실행은 머리줄만 있는 CSV 와 'no data: http_5xx_ratio' 가 된다(Prometheus 대역에서 봄).
- [ ] 찾은 개선 **[계획]**. (BE)
  - Grafana 'Beside 운영' 의 'JVM 힙' 패널이 'GC 뒤' 선을 `jvm_gc_live_data_size_bytes` 로 그려 G1 에서는 0 이나 옛 값에 머문다(mock jar·G1 로컬 k6 실행에서 내내 0) → `jvm_memory_usage_after_gc_percent` 로(`deploy/monitoring/grafana/dashboards/beside-ops.json`).
  - `collect.py` 는 실행 구간 안의 서버 재시작을 알아보지 못한다: 오류 기준으로 멈춘 실행(run.json `outcome.ended` = `ruleStop`)은 지금 `vm/backend.log`·k6 출력으로 손으로 확인한다. `process_start_time_seconds` 를 19번째 CSV 로 더하면 재시작이 보인다(README 의 CSV 수와 `test_collect.py` 도 함께 바꿈).
- [ ] F2 는 `-e SCENARIO=F2` 로 돌리고 run.json `scenario` 가 F2 인지 확인한다 **[계획]**(VM). 이름 없이 돌린 F2 실행에서 오프라인 대기 안에 스텁이 돌아와 FAILED 작업이 하나도 없으면, retry 가 없어 G1 으로 적히고 큐 재계산(`meta.recompute`)도 남는다(`gpu_queue.js` 머리말). 빠뜨렸으면 `collect.py --scenario F2` 로 이름을 고치고 재계산은 쓰지 않는다. (BE)

## 6. 문서 불일치

| 무엇 | 어디 | 처리 |
|---|---|---|
| 추론 수치·앱 `metrics.csv`·변환기가 "[계획]/리포에 없음"으로 적혀 있지만 코드는 있음 | `METRICS.md:5,57-59,72`, `CONTRACT.md:104,132-133`, `GLB_SPEC.md:5` | 계약 파일 → 회의 안건(#16) |
| GLB_SPEC "스킨·애니메이션 없음" ↔ 실제 리깅 출력 | `GLB_SPEC.md:35` | 안건 #14 |
| `sample-dog.glb` 가 문서마다 "0바이트" / "리깅 GLB 로 교체해 씀" | 루트·backend README, `unity/README.md`, `unity/CLAUDE.md` | 교체 결정 후 한 번에 |
| #6 업로드 모드를 PLAN 은 "결정", 체크리스트는 "제안"으로 적음. (b) 를 "GLB 바이너리 응답"이라 적었지만 구현은 JSON `glbUrl` + `GET/DELETE /artifacts` | `PLAN.md`, `REVIEW_CHECKLIST.md:12` | 회의에서 결정 기록. PLAN 위험 표는 이번에 구현에 맞춤 |
| minSdk(README 24 ↔ 설정 34), HTTP 허용, 핀치 범위 | `unity/README.md`, `ProjectSettings.asset`, 프리팹 | UN |
| 생성 결과 커밋 금지 규칙 ↔ `unity/Assets/Models` 의 AnimalLift 출력 | `generation/README.md`, `unity/CLAUDE.md` | 팀 결정(라이선스) |
| 커밋 메시지 규칙 `feat:` 등 ↔ 실제 `[Backend] 무엇을 — 왜` | 루트 `CLAUDE.md` | 팀 결정 |
| 루트 README 상태가 10-04 기준, PLAN 의 "10-06(월)"(실제 화요일), 표 머리 날짜, 간트에 4b 없음, scripts 표 누락, CLAUDE.md 디렉터리 맵 | 루트 `README.md`, `PLAN.md`, `scripts/README.md`, `CLAUDE.md` | **이번에 고침**(2026-10-31) |

## 7. 정리할 것

- [ ] 에이전트 브랜치 `worktree-agent-*` 7개, 검토 워크트리 `.claude/worktrees/review-v1.1` — v1.1 결정 뒤 삭제.
- 백업 브랜치 `backup/review-api-v1.1-6a3959f`·`-ecc5fb5`·`-d12498c` 는 지우지 않는다: 앞의 둘은 성능 보고서의 v2·v2p 를 다시 재는 데, 마지막은 코드 리뷰 보고서가 적은 SHA 에 필요하다.
- [ ] 커밋 안 된 리포 루트 파일: `OVERNIGHT_REVIEW_2026-10-31.md`, `claudecode_bootstrap_prompt.md`.
- [ ] 로컬 `main` 은 `origin/main` 보다 25커밋 앞서 있다(같은 커밋이 `origin/deploy/vm-4b` 에 있음). PR 을 squash 로 합치면 로컬 main 을 `origin/main` 에 맞춘다.
