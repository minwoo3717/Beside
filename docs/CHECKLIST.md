# 프로젝트 점검표 (CHECKLIST)

기준일 **2026-10-31(토)**, 부하 시험 항목(2·5절)은 2026-11-01 추가, 2026-11-02 갱신(k6 v2.3.0 실제 실행으로 스크립트 확인·수정). 2026-11-21: 계약 안건 #1~#23 결정(계약 회의 대신 Backend 담당)으로 닫히거나 바뀐 항목에 표시했다. 결정의 문서·코드 반영은 브랜치 `feat/contract-v1.0`(main 병합·VM 배포 전)이다. 단계·기간·완료 기준(DoD)의 원본은 [PLAN.md](PLAN.md)이고, 이 문서는 "지금 무엇이 빠져 있고 누가 무엇을 해야 하나"를 한곳에 모은다. 주 1회 계약 회의 때 PLAN 과 함께 갱신한다.

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

1. [ ] **계약 회의 1회** — [REVIEW_CHECKLIST](api/REVIEW_CHECKLIST.md) 안건 결정(~~결정 칸 23개가 모두 비어 있음~~ → 2026-11-21 회의 대신 Backend 담당이 #1~#23 을 정해 결정 칸과 CHANGELOG 에 적었다) → `CHANGELOG.md` → `git tag api-v1.0`. 남은 것 [계획]: `api-v1.0` 반영(openapi·코드·문서) → `JobV1ContractTest` → 태그, 그다음 `api-v1.1`(큐 상한, PR #19)·`api-v1.2`(v1.1 검토 브랜치에서 고른 기능). 이것이 v1.1 병합(`review/api-v1.1`), GLB_SPEC 리깅 반영, 측정 이름 확정을 막고 있었다. 2026-11-01 에 부하·운영 안건 **#21 큐 상한(503 `QUEUE_FULL`)·#22 요청 제한(429 `RATE_LIMITED`)·#23 업로드·GLB 보존 정책**을 더했다(#15 에 `load.*` 이름 포함). (BE 주관, 전원)
2. [ ] **GPU(Runpod Pod, 대안 GPU PC) 세팅 + exp-01** — 2026-11-17 Runpod A40 에 AnimalLift 설치·모델 모드 실행, 1건 시간·GPU 메모리·출력 크기를 [exp-01](../generation/experiments/2026-11-17_exp-01_baseline-runpod.md) 에 기록(inferMs 약 4 s, gpuPeakMB 5,120, 표본 4건). 2026-11-18 2차 부하 시험에서 같은 사진으로 539건을 더 쟀다(exp-01 6절). 남은 것: 품질 채점, 사진 10장 이상. 1건 생성 시간·VRAM·출력 형식 기록. `inference.timeout-ms`(안건 #7 — 2026-11-21 결정: 600 s → 120 s, VM 배포 전)와 앱 최대 대기(600 s 그대로)의 근거이고, 실제 E2E·품질 평가의 전제다. (GEN, 직접 할 일)
3. [x] **`deploy/vm-4b` → main PR 열기** — CI 가 main push(`af4a747`)와 PR #4 에서 Docker 이미지 빌드·Caddy 경로 시험까지 통과했다. (BE)
4. [ ] **VM·도메인·Tailscale 준비** — VM·도메인은 2026-11-13 GCP 로 끝남(`https://beside-team.duckdns.org`, [deploy/2026-11-13_gcp-first-deploy.md](deploy/2026-11-13_gcp-first-deploy.md)). GPU 는 Runpod Pod + Pod 안 Tailscale 로 결정(2026-11-13). 2026-11-17 Runpod 크레딧 결제, VM·Pod Tailscale 연결, 공개 주소 → VM → Pod 사진 1건 성공(패스스루), 같은 날 Pod 에 AnimalLift 를 설치해 실제 모델로 3건 성공([deploy/2026-11-17_runpod-connect.md](deploy/2026-11-17_runpod-connect.md)). 남은 것: 폰 앱으로 1건. (BE+GEN)
5. [ ] **Unity 1단계 DoD 증빙** — 실기기 Mock 전체 흐름(영상 + `adb logcat`), `fail` 사진 → FAILED → retry, `metrics.csv` 10회. (UN)
6. [ ] **Unity 재시도 버그** — 업로드 단계에서 네트워크 오류가 나면 `Fail()` 이 `CurrentJobId` 를 갱신하기 전에 불려(`unity/Assets/Beside/Job/JobManager.cs:204-207`), 화면의 "다시 시도"가 아무것도 하지 않거나(첫 작업) **이전 작업을 다시 폴링**한다(`JobManager.cs:131-133`). 업로드 실패 때는 같은 사진으로 업로드를 다시 하도록 고친다. (UN)
7. [ ] **발표·보고서 제출일 확인** → 5단계 일정 확정. (전원)

## 3. 단계별 완료 기준 점검

| 단계 | 완료 기준 | 상태 | 빠진 것 | 담당 · 막힘 |
|---|---|---|---|---|
| 0 | 계약 회의 → CHANGELOG → `api-v1.0` 태그 | [ ] | 결정 기록은 2026-11-21(회의 대신 Backend 담당, 결정 칸·CHANGELOG). 반영·태그는 [계획]. `git tag -l` 결과 없음 | BE |
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
| 3 | GLB_SPEC 통과 GLB 3개, gltf-validator 오류 0 | [ ] | 변환기 테스트·검증 기록 없음. 실제 출력(리깅)이 GLB_SPEC("스킨 없음")과 다름 → 안건 #14 는 2026-11-21 결정: GLB_SPEC 을 실제 출력에 맞춤(스킨·클립 선택 항목, 털 맵은 base GLB 안 `extras.beside`). gltf-validator 기록은 [계획] | GEN |
| 4 | real 에서 실제 생성 GLB 수신 | [x] | exp-01(2026-11-17, VM 경유 3건)과 2차 부하 시험(2026-11-18, k6 가 539건을 받음). `e2e_mock.ps1`·폰 앱으로는 아직 | BE+GEN · GPU PC |
| 4 | 실패 경로 4종 | [x] | — | — |
| 4 | 실기기 실제 모델 AR(영상) | [ ] | — | UN · GPU PC |
| 4 | `events.jsonl` 10건 이상 [실험 결과] | [x] | 2차 부하 시험 539건(2026-11-18, VM 업로드 모드 + Runpod A40, 사진 1장 반복, [perf/load/2026-11-18_vm-2nd.md](perf/load/2026-11-18_vm-2nd.md)). 사진 10장 이상은 exp-01 다음 [계획] | BE+GEN |
| 4b | S1~S3 | [구현됨] | GCP VM 에서 스텁으로 실행(2026-11-13). Runpod A40 연결(2026-11-17), 실제 모델 539건(2026-11-18 2차 부하 시험) | BE |
| 4b | S4 Docker·Caddy·CI | [구현됨] | CI 통과, GCP VM 배포·감사·재부팅 확인(2026-11-13). Caddy 강화는 `deploy/gcp` 브랜치에서 PR #5 로 main 에 병합됐다(2026-11-18). Caddyfile 로컬 수정은 되돌렸다. 2026-11-19 PR #6~#11 병합 뒤 VM 을 main `1c87da5` 로 다시 배포했다(11:04 UTC, PR #8 의 정리 재시도 포함) | BE |
| 4b | S5~S9 | 검토 브랜치 | 계약 회의 #11~#13 뒤 병합 → 2026-11-21 결정: 항목별로 받아 `api-v1.2`(작업 삭제·`thumbnail`·`photos` 는 미룸), 병합 [계획] | BE |
| 4b | 실측 10건 이상 | [x] | VM + Runpod A40 에서 539건(2026-11-18 2차 부하 시험, 사진 1장 반복) | BE+GEN |
| 5 | 측정표 3종 + 환경 | [ ] | 서버·추론은 2차 부하 시험(539건, 사진 1장 반복) **[실험 결과]**([perf/load/2026-11-18_vm-2nd.md](perf/load/2026-11-18_vm-2nd.md))와 3차(2026-11-19, 슬롯 2개·장애 F1a·F1b·F2) **[실험 결과]**([perf/load/2026-11-19_vm-3rd.md](perf/load/2026-11-19_vm-3rd.md)), 4차(2026-11-19 UTC, 같은 Pod 의 c=1·c=2, Pod 요청별 시간, F1b 다시) **[실험 결과]**([perf/load/2026-11-20_vm-4th.md](perf/load/2026-11-20_vm-4th.md)). 앱(기기) 측정은 0건. 백엔드 단독 성능(개발 PC 루프백)은 [perf/backend-perf-report.pdf](perf/backend-perf-report.pdf) | 전원 |
| 5 | 리허설 1회, 보고서 초안 리뷰 | [ ] | 날짜 미정 | 전원 |
| 5 | "Animation·Rigging 은 향후 과제로만" | 충돌 | 리깅·애니메이션 5종이 #1 로 이미 머지됨 → 보고서에서 어떻게 쓸지 결정 | 전원 · 팀 결정 |
| 5 | 데모 영상 | [ ] | — | 전원 |

## 4. 트랙별 할 일

### Backend (BE)
- [ ] 계약 회의 주관(위 2-1). 회의 뒤 `openapi.yaml` → `JobV1ContractTest` → CHANGELOG → 태그 순서. — 2026-11-21 회의 없이 결정했다(결정 칸·CHANGELOG). 남은 순서는 그대로 [계획].
- [x] `review/api-v1.1`(S5~S9) 병합 방식 결정: 전부 / 앞 조각부터 일부 / 보류. 결정 자료는 리포 루트 `OVERNIGHT_REVIEW_2026-10-31.md`(커밋 안 됨). — 2026-11-21 #11·#12: 항목별(대기 정보·취소·공유 링크·보관함·관리 화면·품질 평가와 오류 코드 5개)로 `api-v1.2` 에. 작업 삭제·`thumbnail`·`photos` 는 미룸. 병합은 [계획].
- [ ] 0 B `sample-dog.glb` 교체(GEN 과 함께, 라이선스 문제없는 모델).
- [x] VM 배포 후 밖에서 `/h2-console`·`/swagger-ui.html`·`/api/jobs`·`GET /api/v1/jobs` 가 404/401 인지 확인 — 2026-11-13 GCP, 감사에서 나온 우회 4가지도 막음([deploy/2026-11-13_gcp-first-deploy.md](deploy/2026-11-13_gcp-first-deploy.md)).
- [ ] GCP 첫 배포 감사의 남은 것 [계획]: 지금 VM 의 기본 서비스 계정 떼기·`default-allow-rdp` 지우기(사용자, Cloud Shell — deploy/README 'GCP 메인 서버'), 공개 400 메시지의 Java 예외 클래스 이름 지우기, prod 이미지에서 springdoc UI 빼기(지금은 Caddy 가 `/webjars` 를 막음), `Idempotency-Key` 가 같은 작업 id 를 돌려주는 범위(키 만료·내용 무시 — 계약 안건 #5, 2026-11-21 지금대로 유지로 결정), Docker 수동 업데이트, 백업을 같은 디스크 밖으로(개발 PC 복사·스냅샷 일정), Caddy 정지 10초 초과(SIGKILL), 컨테이너 pids 제한. 공개 업로드 요청 수 제한은 범위 밖으로 두고 부하 시험 때 다시 본다.
- [x] `deploy/gcp` 브랜치(create_vm.sh, Caddy 강화, set_basic_auth.sh, 문서) push 와 PR 결정 — PR #5 로 main 에 병합(2026-11-18). VM 은 main `b6c0e6c` 로 바꾸고 `deploy/Caddyfile` 로컬 수정은 되돌렸다. (BE)
- [ ] 남은 낮은 위험(10-30 검토): V2 `RealJobQueueTest` 간헐 실패 1/20(원인 미확정). 같은 목록의 retry 를 동시에 두 번 누르는 경합과 GPU 오프라인 대기가 N건이면 N×30분은 2026-11-20 전체 리뷰에서 고쳤다 **[구현됨]**: retry 는 FAILED → PENDING 을 조건부 갱신 한 번으로 바꿔 한 요청만 202 를 받고 나머지는 409 `JOB_NOT_FAILED`(`JobServiceRetryTest`·`JobServiceRetryRaceTest`), 오프라인 대기는 슬롯을 잡은 때가 아니라 작업을 받은 때부터 30분을 센다(`RealJobWorkerOfflineTest`).
- [ ] 측정 기간에는 재배포하지 않는다(재시작하면 진행 중 작업이 `INTERNAL_ERROR`). 측정 전후 H2 백업을 개발 PC 로 복사.
- [ ] 대표 사진 검사·전처리(브랜치 `feat/preprocess`, [docs/preprocess/2026-11-05_photo-check.md](preprocess/2026-11-05_photo-check.md)) 남은 것 [계획]: 팀 사진(고양이·실내·어두운 곳·여러 마리)으로 폴더 도구를 돌려 기준값 재확인(안건 #19 — 2026-11-21 지금 동작·제안 기준값 확정, 이 판정표 뒤 재검토), VM 2 vCPU 에서 `checkMs`·`preprocessMs`·`docker stats`, VM 에 모델 마운트(deploy/README '띄우기'), `review/api-v1.1` 과 합칠 때 `RealJobWorker`·`JobServiceImpl`·`Job`·`JobRun` 충돌과 작업 삭제가 `storage/preprocessed/` PNG 도 지우게.
- [ ] 전체 코드 리뷰(2026-10-31)의 [계획] 항목: real 로 쓸 때 Mock UI 의 60초·15초 제한, 측정 중 한 번 본 'COMPLETED 직후 에셋 409'(재현부터) — [review/2026-10-31-code-review.md](review/2026-10-31-code-review.md).

### Unity (UN)
- [ ] 재시도 버그(위 2-6).
- [ ] 빌드 기본 서버 주소: 코드 `http://192.168.0.10:8080`(`JobManager.cs:25`), 씬 `http://localhost:8080` → VM 이 생겼다: `https://beside-team.duckdns.org`(2026-11-13, 추론은 아직 샘플 GLB 를 돌려주는 스텁). 앱의 서버 설정 화면에서 먼저 시험.
- [ ] HTTP 허용 설정 정리: `insecureHttpOption` 이 Always allowed(`ProjectSettings.asset`), README 는 "릴리스 전 Not allowed". VM HTTPS 뒤 정리.
- [ ] minSdk 34 가 의도인지 확인(README 는 24).
- [ ] 커밋 안 된 `unity/ProjectSettings` 변경 정리(URP 전역 설정 목록이 비워짐, 나머지는 줄바꿈만 다름) — 담당자가 판단.
- [ ] DoD 증빙: 1·2·4단계 영상, logcat, `metrics.csv`, FPS, 기기 2종.
- [ ] 서버 생성 모델의 털: 앱은 GLB 안 `extras.beside` 를 기대하지만 변환기는 털 맵을 별도 PNG 로 썼다 → 안건 #14 는 2026-11-21 결정: 변환기를 앱에 맞춘다(털 맵을 base GLB 안 `extras.beside` 에, 형식·반영 상태는 [GLB_SPEC](asset/GLB_SPEC.md)). 남은 것 [계획]: Pod 리포 갱신 → 실제 사진 1건으로 기기에서 털이 보이는지, `loadMs`·FPS 가 털 없을 때와 얼마나 다른지 기록(GEN 과 함께).
- [ ] (v1.1 을 받으면 — 2026-11-21 #11·#12 로 이 기능들은 `api-v1.2`) 진행 화면에 `queue`/`estimate`, 취소 버튼이 `POST /cancel` 호출(지금은 앱 안 코루틴만 멈춤), `CANCELED` 문구.
- [ ] 코드 리뷰 보고 7건: 에셋 다운로드 실패 때 화면이 멈춤, 바뀐 템플릿·GltfImport 를 해제하지 않아 memMB 가 계속 오름, 재개·retry 실행도 `metrics.csv` 에 줄을 더함(METRICS §3 '작업당 한 줄') 등 — [review/2026-10-31-code-review.md](review/2026-10-31-code-review.md) 'Unity 트랙에 보고'.
- [ ] 대표 사진 검사 문구 7개(`UNREADABLE_IMAGE` ~ `IMAGE_TOO_BLURRY`)는 2026-11-04 백엔드가 `ErrorMessages.cs` 에 넣었다(재시도 대상 아님). 실기기에서 real 서버에 두 마리 사진과 동물이 없는 사진을 올려 문구가 나오는지 확인한다. real·prod 서버에 '캡처로 테스트'를 하면 이제 400(`NO_ANIMAL_DETECTED`)이다(mock 은 그대로).

### 3D Generation (GEN)
- [ ] Runpod Pod: AnimalLift·conda·체크포인트·리깅 템플릿을 `/workspace` 아래에 설치(컨테이너 디스크는 정지 때 지워짐), 실행은 `deploy/runpod/start_pod.sh`([deploy/README.md](../deploy/README.md) 'Runpod Pod 쪽'). 대안으로 GPU PC 면 ASCII 경로, WSL 안에서 Tailscale, `BESIDE_HOST`·`BESIDE_INFERENCE_TOKEN`.
- [ ] exp-01(1건 시간·VRAM) → exp-02(우리 사진 3마리 이상, 실제 E2E 10건 이상, `job_runs` CSV) → exp-03·04(개선 2건 이상).
- [ ] 개선 후보(가설): 비정사각 사진을 정사각으로 늘려 넣음(`animallift_runner.py` 의 Resize) → 패딩·배경 제거와 비교(Spring 이 크롭·배경 제거를 한다 [구현됨] 2026-11-05, 3D 비교 실험 틀 `experiments/2026-11-05_exp-03_input-preprocess.md` 를 GPU PC 에서 실행 [계획]), 털 맵을 GLB 안에 넣기(2026-11-21 #14 로 결정 — 형식·반영 상태는 [GLB_SPEC](asset/GLB_SPEC.md)).
- [ ] gltf-validator·glTFast 로드 결과 기록, 변환기 테스트.
- [ ] `/infer/upload` 리뷰(업로드 모드, DELETE 범위). 2026-11-19 PR #11 로 `DELETE` 가 같은 jobId 의 실행 취소까지 한다. 이것도 함께 본다.
- [ ] 끊긴 `/infer` 가 MODEL_LOCK 을 쥔 채 다음 작업의 시간 제한을 깎는 문제(GEN·BE, 코드 리뷰 보고서). 업로드 모드는 2026-11-19 PR #11 로 정리 `DELETE` 가 그 작업의 실행을 취소한다 **[구현됨]**: 잠금을 기다리던 실행은 모델을 돌리지 않고, predict 중이던 실행은 끝난 뒤 변환하지 않는다. 이미 도는 predict 와 경로 모드는 남는다(busy 신호 등 설계 결정). Pod 에는 아직 없다 **[계획]**.
- [ ] 재현성: 리깅 템플릿과 그 생성 스크립트가 리포 밖에 있음 → 위치와 절차를 문서로.
- [ ] 라이선스: AnimalLift 리포에 라이선스 파일이 없고, AnimalLift 출력(`unity/Assets/Models/n02085782_186 (1)_mesh.obj` 등)이 커밋돼 있다 → 팀 결정.

### 배포·운영
- [ ] 위 2-3, 2-4.
- [ ] `.env`: `BASIC_AUTH_HASH` 는 작은따옴표로, `INFERENCE_TOKEN` 은 GPU PC 와 같은 값.
- [ ] 첫 배포 후 GPU PC 를 끈 채 작업 1건 → PENDING 대기 + Discord 알림 → 켜면 진행되는지 확인.

### 계약·문서 (회의 안건)
- [x] 안건 #1~#23 결정, #3 숫자 상한은 exp-02 수치가 있어야 정할 수 있다. #17(prod 의 사진 내용 검사)·#18(`modelVersion` 의 `+rig`)은 코드 리뷰에서, #19(대표 사진 검사의 프로파일 차이·제안 기준값)·#20(전처리 측정 이름)은 전처리(2026-11-04)에서, #21~#23(큐 상한·요청 제한·보존 정책)은 부하 시험 계획(2026-11-01)에서 나왔다. — 2026-11-21 계약 회의 대신 Backend 담당이 결정([REVIEW_CHECKLIST](api/REVIEW_CHECKLIST.md) 결정 칸). #3 은 사진 상한만 확정하고 에셋 상한은 제안값으로 두어 exp-02·기기 FPS 뒤 확정한다 [계획].
- [ ] 계약 파일의 상태 표기가 실제 코드보다 늦음(아래 6절) — 계약 파일이라 회의에서 함께 고친다. — 2026-11-21 #16 결정대로 METRICS·CONTRACT §4~§6 을 실제 상태([구현됨]·[실험 결과])로 고쳤다(`feat/contract-v1.0`, main 병합 전). 남은 것: GLB_SPEC 의 '변환기 리포에 없음' 등(#14 와 함께 GLB_SPEC 갱신 때).
- [x] 측정 정의 확정: `convertMs` 에 리깅 시간이 더해짐(`app.py`), 앱 `e2eMs` 가 첫 AR 배치까지(사용자 탭 포함), 앱 `downloadMs` 가 캐시 적중 때 0. — 2026-11-21 #16: `convertMs` 는 리깅이 성공한 실행이면 리깅 시간을 포함하는 것으로 확정해 METRICS §2 에 적었다. 앱의 두 값과 재개·retry 줄은 지금 동작 그대로 METRICS §3 에 적었다(바꿀지는 UN).
- [x] 백엔드 벤치마크 이름(`bench.*`)과 부하 시험 이름(k6 `load.*`)을 METRICS 에 올릴지(안건 #15). — 2026-11-21: (a) `docs/perf` 전용으로 유지, METRICS 에 넣지 않음.

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
- [ ] VM(HTTPS·Caddy·Tailscale) 위에서 같은 시나리오 재측정 — GCP VM 은 준비됨(2026-11-13, e2-standard-2 8GB. 부하 문서의 "2 vCPU·4GB" 전제는 2026-11-18 에 고쳤고 deploy/README '메모리 (4GB VM)' 절 제목만 남음), 동작 확인 수치만 [deploy/2026-11-13_gcp-first-deploy.md](deploy/2026-11-13_gcp-first-deploy.md). 2026-11-18 에 VM 위 부하 1차(A0~A4, 아래 '부하 시험')를 했다. 그중 A2 폴링 포화가 이 항목의 폴링 처리량에 가장 가깝다(VM·Caddy·TLS 에서 규칙상 마지막 통과 300 RPS, 900 RPS 는 지연 기준 안에서 유지했지만 drop 509·VM CPU 85.8 %, 1,200 붕괴, [perf/load/2026-11-18_vm-1st.md](perf/load/2026-11-18_vm-1st.md)). 루프백 벤치마크 시나리오 그대로의 VM 재측정은 아직이다. (BE)
- [x] 실제 GPU 로 `job_runs` 실측 10건 — 백엔드 단독 벤치마크와 별개로 METRICS §1.1 표를 채운다. 2차 부하 시험(2026-11-18)에서 539건 **[실험 결과]**. `job_runs` 와 같은 값이 실행 폴더의 `vm/events.jsonl` 에 있다. `job_runs` CSV 는 내보내지 않았고, 시험 볼륨(H2)은 시험 뒤 지웠다(운영 기록). 사진 1장 반복이라 사진 10장 이상은 exp-01 다음 [계획]. (BE+GEN)
- [ ] v1.1 을 병합하거나 다음 버전이 생기면 `versions.json` 에 한 줄 더해 같은 명령으로 재측정([perf/notes/20-next-version.md](perf/notes/20-next-version.md)). (BE)

### 부하 시험 — 쏘카 수준 목표 (2026-11-01 추가)

멘토링 피드백 "쏘카 수준 트래픽을 목표로 설정하고 테스트"(지표 5종 + 대시보드, 장애 시나리오 5종)와 교수님 피드백 "부하·장애 테스트도 배포 환경에서"는 2026-11-01 전까지 리포에 기록이 없었다. 목표 수치(T1/T2/T3, 모든 비율 [가정])·합격 기준(제안값)·시험 구성은 [perf/load/TRAFFIC_MODEL.md](perf/load/TRAFFIC_MODEL.md), 작업은 브랜치 `load/socar`(PLAN 4b '부하·운영'). 보고용 측정은 모두 VM 이 준비된 뒤에 한다 — 루프백 벤치마크나 아래 로컬 동작 확인과 섞어 인용하지 않는다.

- [x] 스크립트 동작 확인 (2026-11-01~02): 개발 PC 에서 실제 k6 v2.3.0 으로 k6 스크립트 7개와 `collect.py`·F3/F4 도우미를 짧게 줄여 돌렸다(루프백, mock jar·패스스루 스텁·Prometheus 대역). 찾은 문제 — 중단된 실행이 'all passed' 로 적힘, 잘린 유지 구간이 pass, A3 의 4xx 거부가 통과, A3·A4 가 `PRE_VUS` 를 읽지 않음, A2 id 의 상태를 보지 않음, F3 느린 업로드가 폴링 뒤에 돎, 'GC 뒤 힙' 판정 CSV 없음 등 — 은 `load/socar` 에서 고치고 시험을 더했다 **[구현됨]**(Node 82·Python 84). 용량 결과가 아니므로 [실험 결과] 로 인용하지 않는다. (BE)
- [x] A0~A4 기준 측정(연기 시험·혼합 흐름·폴링 포화·업로드·에셋 송신)을 VM 에서 — 1차 2026-11-18 **[실험 결과]**([perf/load/2026-11-18_vm-1st.md](perf/load/2026-11-18_vm-1st.md)). 부하 발생기는 Runpod CPU Pod(미국 서부, RTT 153 ms)였다. 혼합 흐름 9,000명(185 RPS) 8분 5xx 0(drop 12 로 규칙상 통과 단계 없음), 폴링 규칙상 통과 300 RPS·900 RPS 는 지연 기준 안에서 유지·1,200 붕괴(VM CPU 를 가장 많이 쓴 것은 Caddy), 업로드 20/s 통과, 에셋 10/s 통과(15/s 실패는 경로 상한 탓으로 봄 [가설]). (BE)
- [ ] 1차에서 남은 것 **[계획]**: (BE)
  - 한국·일본 리전 부하 발생기로 A1·A3·A4 다시 — asset 전체 시간·서버 `uploadMs` 기준은 153 ms 경로가 섞여 판정하지 못했고(경로 탓이라는 것은 [가설]), A4 는 미리 잰 경로 상한(약 210 Mbps) 때문에 서버 송신 상한까지 가지 못했다.
  - ~~`api_staged.js` 의 browse 시나리오 `preAllocatedVUs`(고정 5)를 환경변수로~~ — A1 의 drop 12 는 모두 browse 에서 났고 `PRE_VUS` 로는 없어지지 않았다. 2026-11-19 PR #9 로 `BROWSE_PRE_VUS`(기본 최고 browse 도착률 × 10초, A1 은 200)와 시나리오별 drop(`verdict.droppedByScenario`)을 넣었다 **[구현됨]**. A1 을 VM 에서 다시 돌려 browse drop 0 을 보는 것은 **[계획]**.
  - Caddy: 실행마다 Caddy 네트워크 이름공간의 연결 수·`nstat` 을 남긴다(호스트 nstat 은 Caddy 연결을 보지 못함). 명령과 15초 표본기는 2026-11-19 PR #9 로 [deploy/loadtest/README.md](../deploy/loadtest/README.md) 5번에 넣었다(WSL 에서만 확인, VM 실행 **[계획]**). A1 에서 2,246 MiB 까지 늘어난 메모리의 원인을 유휴 시간 A/B 로 가린 뒤 `mem_limit` 을 정한다. A2 가 무너질 때 VM CPU 를 가장 많이 쓴 것은 Caddy 였다. A2 붕괴 단계의 오류 7,298건은 상태 코드가 남지 않았다(Caddy 의 502·504 로 보임 [가설]). 상태 계열 카운터(`load_status*Count`, 요약 `verdict.httpStatus`)는 PR #9 로 넣었다 **[구현됨]**. A2 를 VM 에서 다시 돌려 가리는 것은 **[계획]**.
  - A3 large, A5·A6. 실제 GPU 의 G·F 는 아래 2차.
- [x] 2차 실제 GPU 대기열(G0r·G1r·G3r)을 VM + Runpod A40 에서 — 2026-11-18 **[실험 결과]**([perf/load/2026-11-18_vm-2nd.md](perf/load/2026-11-18_vm-2nd.md)). (BE)
  - 조건: 실제 AnimalLift, 사진 1장, 전처리 켬. 539건 모두 COMPLETED.
  - 슬롯 1개·이 경로에서 A40 한 대 앞의 처리 용량은 약 400건/h 였다(연속 처리 간격 8.8~9.1 s) [계산].
  - 대기는 실제 도착으로 다시 돌린 FIFO 재현과 평균 3~6 % 안에서 맞았다 [계산].
  - 포화에서도 GPU 사용률 평균은 23 % 였다.
- [ ] 2차에서 남은 것 **[계획]**: (BE, F 는 GEN 함께)
  - ~~G2r(A40 한 대에 슬롯 2개)·F2-real·F1b-real(τ 5 s)·F1a~~ — 2026-11-19 3차에서 했다(아래).
  - ~~정리 `DELETE` 가 실패하면 Pod 작업 폴더가 남는다(539건 중 1건, "header parser received no bytes")~~ — 응답 없는 연결 오류에 한 번 더 보내는 재시도(시간 초과는 다시 보내지 않음)를 2026-11-19 PR #8 로 main 에 병합하고 VM 에 배포했다(11:04 UTC) **[구현됨]**. 재시도가 실제로 일어난 기록은 아직 없다. 재시도로 막지 못하는 경우(3차 F1b-real)는 아래 3차 항목.
  - ~~Pod uvicorn 의 유휴 연결 5초 때문에 부하가 낮을 때 전송이 길어지는지 확인한다~~ — 3차에서 확인했다. 75 s 로 처리 중앙값 −0.88 s **[계산]**.
  - G1r ρ0.7 의 느린 작업 14건(추론 12건·변환 11건)의 원인을 가린다(Pod CPU·볼륨 지연 기록). 3차는 두 기록을 모든 실행에 남겼고 이런 묶음이 없었다(모델을 다시 올린 뒤 첫 추론 4건만 6.3~7.0 s). 2차의 원인은 가리지 못한 채 남는다.
  - 작업당 VM CPU 가 약 3.6~4.5 코어·초라 2 vCPU VM 은 T3(초당 1건)를 감당하지 못한다 [계산]. 대부분이 크롭이라는 것은 [가설]이다. VM 크기나 크롭 위치를 정한다. 3차 G2r 은 4.04 코어·초(70 % 상한 약 1,250건/h)였다.
- [x] 3차 실제 GPU(G2r·F2-real·F1b-real·F1a, 유휴 연결·스레드 G0 비교)를 VM + Runpod A40 에서 — 2026-11-19 **[실험 결과]**([perf/load/2026-11-19_vm-3rd.md](perf/load/2026-11-19_vm-3rd.md)). (BE)
  - 슬롯 2개: 처리 용량 약 510~530건/h, 슬롯 1개의 약 1.3배 [계산]. 늘어난 시간은 주로 변환 뒤 응답·내려받기다 [계산]. 원인(동시 전송이 Pod 의 보내는 경로를 나눠 씀·CPU 다툼)은 [가설].
  - F2-real: 잃은 작업 0(처리 중이던 1건은 클라이언트 retry 로 살아남), kill → UP 146.7 s(DOWN → UP 145.4 s), 5xx 0.
  - F1b-real: 모든 시도가 시간 초과(예상한 결과). 버려진 변환이 정리 뒤 작업 폴더 3개를 남겼다.
  - F1a(같은 Pod 를 스텁으로): 시도 실패율 0.17, 40건 중 39건이 마지막 COMPLETED, 5xx 0.
  - uvicorn 유휴 연결 75 s: 처리 중앙값 −0.88 s, S0 9.0 s. 스레드 8: Pod CPU 한도에 걸린 100 ms 주기 0(기본 스레드 0.5 %), 추론 −2.6 %.
  - 계획 밖 경로 끊김 2번(약 83 s·50 s). 오프라인 대기로 잃은 작업은 0 이었다.
- [ ] 3차에서 남은 것 **[계획]**: (BE, GEN 함께)
  - ~~추론 서비스: `DELETE` 를 받은 작업은 변환·쓰기를 하지 않게 하고(변환의 `os.makedirs` 가 지운 폴더를 다시 만든다), 오래된 작업 폴더를 지운다~~ — 2026-11-19 PR #11 로 main 에 병합했다 **[구현됨]**. `DELETE` 가 같은 jobId 의 실행을 취소하고, 변환은 작업 폴더를 다시 만들지 않으며(`make_dirs=False`), 겹친 `rmtree` 는 세 번까지 시도한다. 오래된 폴더 정리는 `BESIDE_WORK_SWEEP_MINUTES`(기본 꺼짐)다. ~~Pod 리포를 main 으로 맞추고 F1b-real 을 다시 돌린다~~ — 4차(2026-11-19 UTC)에서 Pod 리포를 `git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main origin/main` 으로 main `a9aa091` 에 맞추고(리포가 detached HEAD 라 `pull` 대신) `f1br4` 를 돌렸다: 74시도 모두 Pod 에서 실행 중 취소, 남은 폴더 0, `DELETE` 204 74번(4차 Pod 로그 전체 436번, 5xx 0), `conversion crashed`·Traceback 0 **[실험 결과]**([perf/load/2026-11-20_vm-4th.md](perf/load/2026-11-20_vm-4th.md) 6절). 남은 것 **[계획]**: Pod 에서 sweep 을 켤지 정한다. (GEN 함께)
  - ~~Pod 시작 기본값에 유휴 연결 75 s·스레드 8 을 둘지 정한다~~ — 2026-11-20 KST(`3b12c7b`) `deploy/runpod/start_pod.sh` 의 기본값으로 넣었다(`UVICORN_TIMEOUT_KEEP_ALIVE`·`THREADS` 로 바꿀 수 있다) **[구현됨]**. WSL 에서 가짜 Tailscale 로 스크립트를 끝까지 돌리고, uvicorn 0.54 에서 75 s 면 10초 뒤에도 연결이 이어지는 것을 확인했다. 4차에서 실제 Pod 로 확인했다 **[실험 결과]**: `[4/4]` 줄과 `/proc/<pid>/environ` 에 75 s·스레드 8, G0 `g0d4` 에서 Pod CPU 한도에 걸린 주기 0, 처리 중앙값 8.09 s(3차 `g0th8`(같은 두 값, 다른 Pod, JDK 유휴 1200 s)과 −0.004 s), 작업 연결 하나를 31건이 이어 씀([vm-4th 2절](perf/load/2026-11-20_vm-4th.md)).
  - ~~Pod 에서 요청마다 처리 시간을 남기고 G2r 을 다시 돌려 내려받기 가설을 가린다(스레드 8 도 함께). 같은 Pod 에서 c=1 과부하도 함께.~~ — 4차에서 `BESIDE_REQUEST_TIMING=1` 로 같은 Pod·같은 요청 λ 의 c=1 과부하(`g3c1`)와 c=2(`g2c2t`)를 돌렸다 **[실험 결과]**. 길어진 GLB 구간은 tailnet 연결 하나(74건, 중앙값 4.96 s)에 몰렸고, 겹침·tailscaled CPU·Pod CPU 한도와는 맞지 않았다 **[계산]**. 그래서 3차 가설(동시 전송이 보내는 경로를 나눠 씀·CPU 다툼)은 4차 조건(스레드 8·CPU 한도에 걸린 주기 0)에서 맞지 않았다. 3차(기본 스레드)의 CPU 다툼은 4차로 직접 가리지 못했다. 연결 하나가 느려지는 기제는 **[가설]**([vm-4th 5절](perf/load/2026-11-20_vm-4th.md)).
  - Pod tailscaled 로그도 남기고, 운영 끊김 알림 유예(45 s)를 이번 끊김 길이(50~83 s)와 맞춰 본다. 4차(약 1.5시간)에는 끊김이 없었다.
  - 앱 '다시 시도'가 F2 의 처리 중 실패(`INFERENCE_UNAVAILABLE`)에서 동작하는지 확인한다. (UN)
- [x] 4차 실제 GPU(새 시작 기본값 G0r, 같은 Pod 의 c=1 과부하·c=2 와 Pod 요청별 시간, PR #11 Pod 의 F1b-real)를 VM + 새 Runpod A40 Pod 에서 — 2026-11-19 UTC **[실험 결과]**([perf/load/2026-11-20_vm-4th.md](perf/load/2026-11-20_vm-4th.md)). (BE)
  - 같은 Pod·같은 요청 λ 에서 c=1 용량 415건/h(S_busy 8.67 s), c=2 는 넘치지 않아 센 하한 495건/h(도착 − 기울기 추정 507~516건/h)·슬롯 시간 식 678~689건/h, 비 1.19~1.66 **[계산]**.
  - G0: S0 8.64 s, h0 0.293 s **[계산]**. F1b-real: 남은 폴더 0, `DELETE` 5xx 0 **[실험 결과]**.
  - API 5xx 0, 추론 서비스 DOWN 0, 경로는 확인한 때(17:23, 실행마다 끝) 모두 직접 연결이었다 **[실험 결과]**.
  - A40 재고: 앞의 Pod 3개가 켜지지 않았고, 새 Pod 는 배치 조건(GPU 당 최소 vCPU 4·RAM 24 GB)을 주고서야 만들어졌다.
- [ ] 4차에서 남은 것 **[계획]**: (BE, GEN 함께)
  - c=2 에서 tailnet 연결 하나가 느려지는 기제를 가린다(VM `ss -ti`·Pod tailscaled 지표, 연결 수명을 짧게 한 비교, 긴 c=1).
  - c=2 를 넘치는 도착(약 690건/h 이상)으로 다시 돌려 용량을 바로 잰다.
  - τ 가 짧을 때 버려진 predict 가 GPU 를 쓰는 것(4차 F1b 약 270 s **[계산]**)을 줄일지 정한다.
- [ ] G1(큐 이론 검증, 스텁 T=10초)·G2(GPU 증설 c = 1, 2, 4) — 추론 스텁, 실제 GPU PC 는 쓰지 않음. (BE, 스텁 설정은 GEN 리뷰)
- [ ] F1~F5 장애 시나리오(추론 실패·타임아웃, 추론 서비스 꺼짐·재시작, 잘못된 업로드, 다운로드 중단, 스파이크 + 한 IP 집중). F1b 는 MODEL_LOCK 문제로 실패가 예상되며 그대로 기록한다. (BE, F1·F2 는 GEN 함께)
- [ ] 보호 장치(큐 상한 #21, 요청 제한 #22) 적용 전후 비교(G3·F5) — 계약 회의 결정 뒤. (BE) — 결정은 2026-11-21: #21 N=19·`Retry-After` 165 s(`api-v1.1`, PR #19), #22 는 429 이름만 예약. 큐 상한 적용과 비교 시험(5차)은 [계획].
- [ ] exp-01 로 생성 시간 T 를 잰 뒤 `scripts/load/model.py` 로 GPU 대수·큐 상한 표를 다시 계산해 계약 회의 자료([perf/load/2026-11-20_queue-cap.md](perf/load/2026-11-20_queue-cap.md))에 넣고, 회의가 정한 뒤 #21 의 상한 값과 TRAFFIC_MODEL 표를 고친다([TRAFFIC_MODEL 12절](perf/load/TRAFFIC_MODEL.md)과 같은 규칙). (GEN 이 T 측정, BE 가 재계산) — exp-01 표본 2건(T ≈ 10~16 s)으로 계산만 해 두었다: T2 는 GPU 1대, T3 는 15~23대, Q_max(c=1) 37~57 **[계산]**([vm-1st 7절](perf/load/2026-11-18_vm-1st.md)). 2차 부하 시험의 슬롯(8.8~11.5 s)으로 다시 계산하면 T3 는 13~17대, Q_max 51~67 이다 **[계산]**([vm-2nd 9절](perf/load/2026-11-18_vm-2nd.md)). 3차의 슬롯 2개(510~530건/h)면 T3 는 10~11대다 **[계산]**([vm-3rd 10절](perf/load/2026-11-19_vm-3rd.md)). 4차 Pod 하나에서 잰 값으로는 슬롯 1개 415건/h 면 13대, 슬롯 2개 센 하한 495건/h 면 11대로 3차 범위(10~11대) 안이다. 슬롯 2개가 슬롯 시간 식(678~689건/h)만큼 처리하면 8대지만 넘친 실행으로 확인하지 않았다 **[계산]**([vm-4th 9절](perf/load/2026-11-20_vm-4th.md)). 표·#21 고치기는 계약 회의 뒤. — #21 은 2026-11-21 결정(N=19, W=180 s, c=1). TRAFFIC_MODEL 표 고치기는 `api-v1.1`(PR #19)과 함께 [계획].
- [ ] VM 시험 전·중에 확인할 것 **[계획]** — 컨테이너 경로·실제 Prometheus 는 이 PC 에 Docker 가 없어 VM 에서, Linux k6 문구는 WSL 등 Linux 에서, 부하 PC 메모리는 이 PC 에서 VM 시험 중에 본다. (BE)
  - F3 남은 파일 확인 명령(`f3_uploads.js` 가 출력하는 'VM:' 줄)의 컨테이너 안 경로 `/app/storage/uploads`·`/tmp/tomcat.8080.*`. 로컬에서는 경로만 바꿔 같은 명령을 확인했다.
  - ~~실제 Prometheus 에 `jvm_memory_usage_after_gc_percent{area="heap",pool="long-lived"}` 시계열이 있는지~~ — 2026-11-18 VM 에서 있음을 확인했다(각 실행 `prometheus/jvm_heap_after_gc.csv`).
  - 서버를 죽였을 때 실제 Prometheus 의 staleness: Prometheus 대역은 마지막 수집값을 계속 내서 CSV 에 빈 구간이 보이지 않았다.
  - `SEED_ONLY` 의 `IDS_OUT` 검사가 기대는 k6 `open()` 오류 문구를 Linux k6 에서(Windows k6 v2.3.0 에서만 확인. 2026-11-18 VM 1차는 Linux k6(Runpod Pod)로 A2 시드를 돌렸지만 이 오류 경로를 확인한 기록은 없다).
  - 부하 PC 메모리: 초기화된 VU 하나에 약 1.1 MB(로컬 측정: 개발 PC·k6 v2.3.0, `api_staged.js` 를 `PRE_VUS`=`MAX_VUS` 20 과 300 으로 짧게(1,800명 단계 하나, 20초 유지, `BROWSE=0`, 루프백 mock jar) 돌려 k6 프로세스의 최대 작업 집합 83.9 MB·393.2 MB 를 비교한 값, VU 마다 0.4 MB 사진 사본 포함). A1 기본 `MAX_VUS` 3,300(흐름 VU. 시작 때는 사전 할당 450 만 만들고 나머지는 흐름이 길어질 때 생기므로 최악)이면 초기화만 약 3.6 GB, 흐름을 돈 VU 마다 0.8 MB 본문(실패 주입 흐름을 돈 VU 는 0.8 MB 더)이 붙어 약 6.3~8.9 GB [파생], 둘러보기 VU(최대 200)가 약 0.2 GB 더. A3 large 는 VU 마다 5 MB 사진 사본에 첫 업로드 때 20 MB 본문이 더해져 사전 할당 50 VU 면 약 1.25 GB, 업로드가 길어져 기본 `MAX_VUS` 600 까지 늘면 약 15 GB [파생]. 시험 중 k6 메모리를 CPU 와 함께 본다. 2026-11-18 VM 1차(Runpod Pod, Linux k6)에서는 A1 `PRE_VUS` 2,000(VU 2,017)에 k6 RSS 최대 6,662 MB, A2 VU 2,000 에 2,201 MB 였다 **[실험 결과]**(각 실행 `client-cpu.csv`).
- [ ] 판정 기준 결정 — 지금 코드의 동작은 **[구현됨]**, 바꿀지는 미정. (BE, 첫째는 계약 회의)
  - A3(`upload.js`)에서 429 `RATE_LIMITED`·503 `QUEUE_FULL` 거부를 유지 구간 실패로 볼지: 지금은 `load_rejectedCount` 로 따로 세고 기준이 없다(A3 기준은 `load_unexpectedCount == 0`, 전체·유지 구간마다). 안건 #21·#22 와 함께 정한다. — 2026-11-21 #21(D10): 503 `QUEUE_FULL` 은 A3 에서 지금처럼 따로 센다(유지 구간 실패 아님). METRICS §5.3 의 5xx 비율에서도 뺀다(D9, `api-v1.1` 때 반영 [계획]). 429 는 #22 구현 때 정한다.
  - F1a 실행에서 `load_flowOkRatio >= 0.95` 를 그대로 둘지: 스텁 실패율 0.2 면 첫 시도와 retry 가 다 실패하는 흐름이 약 4 % 라 기대값이 약 0.96 [파생] 이고, 줄인 로컬 실행에서도 통과와 실패가 둘 다 나왔다. 3차 F1a(`f1ar2`)는 0.975(40건 중 1건이 두 번 실패)로 통과했다.
  - `collect.py` 의 `http_5xx_ratio` 질의에 `or vector(0)` 을 붙일지: 서버가 뜬 뒤 5xx 를 한 번도 내지 않아 5xx 시계열이 없으면 머리줄만 있는 CSV 와 'no data: http_5xx_ratio' 가 된다(Prometheus 대역에서 봄).
- [ ] 찾은 개선 **[계획]**. (BE)
  - Grafana 'Beside 운영' 의 'JVM 힙' 패널(단위 bytes)이 'GC 뒤 살아 있는 데이터' 선을 `jvm_gc_live_data_size_bytes` 로 그려 G1 GC 에서는 0 이나 옛 값에 머문다(mock jar 로컬 k6 실행, 1.5 GB 힙에서 내내 0) → `max(jvm_memory_usage_after_gc_percent{area="heap",pool="long-lived"})`(0~1)를 기준선 0.75 와 함께 비율 패널로 따로 그린다(`deploy/monitoring/grafana/dashboards/beside-ops.json`).
  - `collect.py` 는 실행 구간 안의 서버 재시작을 알아보지 못한다: 오류 기준으로 멈춘 실행(run.json `outcome.ended` = `ruleStop`)은 지금 `vm/backend.log`·k6 출력으로 손으로 확인한다. `process_start_time_seconds` 를 19번째 CSV 로 더하면 재시작이 보인다(README 의 CSV 수와 `test_collect.py` 도 함께 바꿈).
- [x] F2 는 `gpu_queue.js` 에 `-e SCENARIO=F2` 를 붙여 돌리고, k6 가 끝날 때 내는 보고의 첫 줄(`== beside load: gpu_queue.js (F2) ...`)과 k6-summary.json `meta.scenario`(run.json 에서는 `k6.meta.scenario`)가 F2 이고 `meta.recompute` 가 null 인지 확인한다 — 2026-11-19 3차 `f2r1` 에서 확인했다(k6.log 첫 줄 `(F2)`, `meta.scenario` F2, `meta.recompute` null) **[실험 결과]**(VM). run.json 맨 위 `scenario` 는 `collect.py --scenario` 로 준 값이 우선이라(배포 절차는 늘 준다) 이 확인에 쓰지 못한다. 이름 없이 돌린 F2 실행은 늘 G1 으로 적히고, 오프라인 대기 안에 스텁이 돌아와 FAILED 작업이 하나도 없으면 retry 도 없어 큐 재계산(`meta.recompute`)까지 남는다(`gpu_queue.js` 머리말). 빠뜨렸으면 run.json 의 `k6.meta.scenario`·`k6.meta.recompute` 는 쓰지 않는다. (BE)

## 6. 문서 불일치

| 무엇 | 어디 | 처리 |
|---|---|---|
| 추론 수치·앱 `metrics.csv`·변환기가 "[계획]/리포에 없음"으로 적혀 있지만 코드는 있음 | `METRICS.md:5,57-59,72`, `CONTRACT.md:104,132-133`, `GLB_SPEC.md:5` | 계약 파일 → 회의 안건(#16). **2026-11-21 결정 → METRICS·CONTRACT 고침**(`feat/contract-v1.0`). GLB_SPEC 은 #14 와 함께 |
| `INTERNAL_ERROR` 의 예기치 못한 오류를 "real 워커"만 적음 ↔ mock 워커도 예기치 못한 오류를 `INTERNAL_ERROR`("Mock worker error (…)")로 끝냄(2026-11-20) | `ERROR_CODES.md:44`, `CONTRACT.md:107` | 계약 파일 → 회의 안건(#16 과 함께). 설명 문구만 고치고 코드 값·상태 코드·앱 문구는 그대로. **CONTRACT §3 은 2026-11-21 고침**(워커 mock·real, FD4 디스크 오류 포함). ERROR_CODES 는 그 담당이 |
| GLB_SPEC "스킨·애니메이션 없음" ↔ 실제 리깅 출력 | `GLB_SPEC.md:35` | 안건 #14 — 2026-11-21 결정: GLB_SPEC 을 실제 출력에 맞춤(CONTRACT §4 는 고침) |
| `sample-dog.glb` 가 문서마다 "0바이트" / "리깅 GLB 로 교체해 씀" | 루트·backend README, `unity/README.md`, `unity/CLAUDE.md` | 교체 결정 후 한 번에 |
| #6 업로드 모드를 PLAN 은 "결정", 체크리스트는 "제안"으로 적음. (b) 를 "GLB 바이너리 응답"이라 적었지만 구현은 JSON `glbUrl` + `GET/DELETE /artifacts` | `PLAN.md`, `REVIEW_CHECKLIST.md:12` | 회의에서 결정 기록. PLAN 위험 표는 이번에 구현에 맞춤. **2026-11-21 결정 (b) = JSON `glbUrl` + `GET/DELETE /artifacts`(경로 모드는 같은 호스트용) → PLAN·CONTRACT §6.1·ARCHITECTURE 고침** |
| minSdk(README 24 ↔ 설정 34), HTTP 허용, 핀치 범위 | `unity/README.md`, `ProjectSettings.asset`, 프리팹 | UN |
| 생성 결과 커밋 금지 규칙 ↔ `unity/Assets/Models` 의 AnimalLift 출력 | `generation/README.md`, `unity/CLAUDE.md` | 팀 결정(라이선스) |
| 커밋 메시지 규칙 `feat:` 등 ↔ 실제 `[Backend] 무엇을 — 왜` | 루트 `CLAUDE.md` | 팀 결정 |
| 루트 README 상태가 10-04 기준, PLAN 의 "10-06(월)"(실제 화요일), 표 머리 날짜, 간트에 4b 없음, scripts 표 누락, CLAUDE.md 디렉터리 맵 | 루트 `README.md`, `PLAN.md`, `scripts/README.md`, `CLAUDE.md` | **이번에 고침**(2026-10-31) |

## 7. 정리할 것

- [ ] 에이전트 브랜치 `worktree-agent-*` 7개, 검토 워크트리 `.claude/worktrees/review-v1.1` — v1.1 결정 뒤 삭제.
- 백업 브랜치 `backup/review-api-v1.1-6a3959f`·`-ecc5fb5`·`-d12498c` 는 지우지 않는다: 앞의 둘은 성능 보고서의 v2·v2p 를 다시 재는 데, 마지막은 코드 리뷰 보고서가 적은 SHA 에 필요하다.
- [ ] 커밋 안 된 리포 루트 파일: `OVERNIGHT_REVIEW_2026-10-31.md`, `claudecode_bootstrap_prompt.md`.
- [ ] 로컬 `main` 은 `origin/main` 보다 25커밋 앞서 있다(같은 커밋이 `origin/deploy/vm-4b` 에 있음). PR 을 squash 로 합치면 로컬 main 을 `origin/main` 에 맞춘다.
