# 최종 종합: 남은 일과 서버 이중화 계획 (2026-11-25, 발표 12/04까지 9일)

이번 종합은 읽기만 하고 만들었습니다. 파일, 브랜치, VM, Runpod, GCP 어느 것도 바꾸지 않았습니다. 근거 표기는 이렇게 씁니다.
- **[직접 확인]**: 이번 종합에서 다시 읽어 본 것
- **[조사]**: 세 조사 에이전트가 읽은 것
- **[구현됨]·[실험 결과]·[계획]**: CLAUDE.md 정의 그대로
- **[계산]·[가설]·[미확인]**: 계산한 값, 확인하지 않은 추정, 확인하지 못한 값

---

## 0. 조사 결과끼리 어긋난 곳 (직접 확인해 정리)

| # | 어긋난 점 | 확인 결과 | 근거 |
|---|---|---|---|
| 1 | 리뷰 브랜치에 커밋이 4개뿐이고 미커밋 변경이 있다고 함 | **main보다 8커밋 앞**입니다(7a8a56c·493a49a·725f0f7·59c5945·332ac60·877f63b·7c52918·24a6ac1). upstream은 없습니다. 추적 파일의 미커밋 변경은 **없고**, `git status`에는 미추적 파일 5개만 나옵니다 | [직접 확인] `git log main..fix/review-2026-11-25`, `git status --short` |
| 2 | Unity 취소(`POST /cancel`)와 `CANCELED` 문구가 시작 전이라고 함 | 24a6ac1(2026-11-25 13:44 KST)에 들어 있습니다(`JobManager.CancelByUser`, `BesideApiClient.CancelJob`, `JOB_NOT_PENDING` 문구). **Roslyn 컴파일만** 했고 main에는 없습니다. 남은 일은 병합, 에디터 Play, 기기 확인입니다 | [직접 확인] `git show 24a6ac1` 의 unity/CHANGELOG |
| 3 | "한 볼륨에 Pod 두 대는 켤 수 없다"(RUNBOOK) | Runpod 쪽에서는 **같은 데이터센터의 여러 Pod가 한 볼륨을 함께 붙일 수 있습니다.** 다만 동시에 쓰면 데이터가 깨질 수 있습니다. RUNBOOK 'GPU Pod 다시 세우기' 3번의 "둘 켜지 않는다"는 우리 쪽 규칙입니다. 실제로 막는 것은 `start_pod.sh:49`(`TS_DIR`)와 `:53`(`LOG_DIR`)이 고정값이라는 점입니다 | [직접 확인] Runpod lookup-concept `network-volume`(규칙 data-center-level-not-machine-level·concurrent-writes, docs.runpod.io/pods/storage/types, 2026-09-28 확인), `git show main:deploy/runpod/start_pod.sh` |
| 4 | 제약 표의 `DatabaseBackup.java:104·:122` | main 기준 `@Scheduled` 는 :48, `BACKUP TO` 는 :66 입니다. D2의 정정이 맞습니다 | [직접 확인] |
| 5 | D1 W2: `offsite_backup.sh` 를 고쳐야 한다고 함 | 파일을 "그날 첫 실행"에만 보내는 조건은 `offsite_backup.sh:193` 에 있습니다. `FORCE_FILES` 는 환경 변수로만 읽고(:41), 설정 파일에서는 `BUCKET`·`VOLUME` 만 읽습니다(:30). 그래서 **스크립트를 고치지 않고 systemd drop-in `Environment=FORCE_FILES=1` 만으로 매 실행 보내기가 될 수 있습니다** [가설: 시험 안 함]. 백업 주기는 `application-prod.properties:34`(`0 5 * * * *`)입니다. compose environment(:23~)에 `STORAGE_BACKUP_CRON` 이 없으므로 compose 1줄은 꼭 필요합니다(D1이 맞음). 타이머는 `*:20:00` 입니다 | [직접 확인] |
| 6 | 같은 항목의 우선순위가 조사마다 다름 | 다음처럼 맞췄습니다(아래 표 참고). 큐 상한은 **발표 뒤**로 두고 발표 문구만 정합니다. 진짜 IP 전환은 이중화 Phase 1로 **should** 입니다. sample-dog.glb는 **could** 입니다(VM 스텁에 유효한 GLB가 있음). 리깅 범위 결정은 **must**(30분)입니다. Pod가 origin/main을 자동으로 받는 문제는 문서 정리에서 **must 조치**로 올렸습니다: 리뷰 브랜치를 병합하는 순간(7c52918이 리깅 도구 확인을 바꿈) 시연 Pod 코드가 검증 없이 바뀝니다 | 세 조사 결과 비교 |
| 7 | Runpod 지출 기간 표기가 다름("2026-11" 과 "최근 10일") | 두 조사 모두 같은 값 **$17.93**(GPU 14.60·CPU 3.22·디스크 0.11)입니다. 기간 이름만 다릅니다 | [조사] list-pod-billing |

---

## A. 남은 일 (12/04 발표 기준)

담당 약어는 BE=Backend, UN=Unity, GEN=3D입니다. **[사람]** 은 콘솔, 기기, 팀원 손이 필요한 일입니다.

### A-1. must (발표 전에 반드시)

| # | [트랙·담당] 할 일 | 근거 | 예상 시간 |
|---|---|---|---|
| M1 | [보고·**사람**] 발표 형식과 보고서 제출일을 확인하고, PLAN 5단계와 CHECKLIST에 12/04·배포 동결일·리허설일을 적기 | CHECKLIST.md:17 "제출일 리포에 없음". PLAN.md:183 5단계가 "2026-11-23 ~ 12-06" 이라 발표일보다 늦음 [직접 확인]. 12/04는 git 밖의 `docs/budget/2026-11_purchase-plan.md:208` 에만 있음 | 30분 + 10분 |
| M2 | [전원·**사람**] 리깅·애니메이션을 발표에서 어떻게 다룰지 결정하고, 보고서 한계 문장(사진은 첫 1장만 씀, 리깅은 템플릿이 있을 때만) 넣기 | PLAN.md:192 "향후 과제로만" [직접 확인] ↔ PR #30(625c2a5) 뒤 운영 `/admin/stats` 에 `+rig` COMPLETED 8건. `app.py:776` `image_paths[0]` | 30분 + 15분 |
| M3 | [BE] 리뷰 브랜치 PR 병합 → VM 배포 **1번** → 배포 동결일 선언. 백업 주기 단축(B, Q2)도 같은 창에서 | 0절 1번. `deploy_vm.sh:7-8` 재시작 때 진행 중 작업 실패. VM은 625c2a5 | 배포·확인 1시간(리뷰는 그 세션 몫) |
| M4 | [BE·GEN·**사람**] 시연 Pod 코드 고정: 시작 명령의 `checkout -B main origin/main` 을 태그(예: `demo-1204`)로 바꾸기 | list-pods: beside-gpu7·gpu5·gpu2 의 시작 명령 [조사]. RUNBOOK 'GPU Pod 다시 세우기' 2번 [직접 확인] | 30분(Runpod 시작 명령 수정은 사람) |
| M5 | [UN·**사람**] Android 빌드 환경 갖추기(Unity Hub Android Build Support·SDK·adb), 또는 Unity 담당 PC 쓰기 | 이 PC의 6000.6.0f1·6000.6.7f1 에 AndroidPlayer 없음, `which adb` 실패 [조사] | 30~60분 |
| M6 | [UN] main(+24a6ac1)을 Unity 6 에디터에서 컴파일·Play하고 APK 만들기. 시연 설정: baseUrl 을 `https://beside-team.duckdns.org` 로, FurBenchmark·FpsOverlay 끄기 | Editor.log 마지막 수정 2026-10-31. `JobManager.cs:25` 는 `192.168.0.10:8080`, `SampleScene.unity:7601` 은 localhost, 두 측정 컴포넌트는 :3615·:7732 에서 `m_Enabled 1`. unity/README.md:122 [조사] | 반나절 + 15분 |
| M7 | [UN·GEN] 서버가 만든 GLB(리깅 + 털 맵)를 기기에서 보고 `BESIDE_EMBED_HAIR` 를 정하기 | GLB_SPEC.md:114-116 furLength 약 0.070(로컬 1건) ↔ `Dog_Fur_Mat.mat:116` 0.025. Pod env 에 `BESIDE_EMBED_HAIR` 없음 [조사] | 반나절, Pod 약 $1 |
| M8 | [UN] 실기기에서 VM(real,prod) + Pod 전체 흐름 영상과 logcat 남기기. 4단계 DoD이자 데모 영상이고, 실시간 시연이 실패하면 대체 자료 | PLAN.md:136 [ ], CHECKLIST.md:64. 리포에 mp4·logcat 0개. Caddy 로그에 UnityPlayer 요청 121건이 있지만 기기인지 에디터인지 모름 [조사] | 반나절 |
| M9 | [UN→BE] 앱 측정 metrics.csv 10회 → /admin 으로 올리기 | METRICS.md:9 "0건", client-metrics csv 는 머리줄뿐 [조사] | 반나절~1일(M7·M8 과 같은 Pod 세션) |
| M10 | [GEN·전원·**사람**] exp-02 품질 평가표: 사진 3마리 이상, 6항목, 평가자 2명 이상 | ratings/summary 의 groups 가 비어 있음, exp-01 품질 칸 비어 있음, 팀 사진 아직 안 옴(CHECKLIST.md:77) [조사] | 1일 + 사진 |
| M11 | [BE] 운영 VM에서 Grafana를 띄워 지표 5개 화면 캡처. JVM 힙 패널을 `after_gc_percent` 로 고치는 것 포함 | grafana 컨테이너 없음, prometheus Exited(0), `.env` 에 `GRAFANA_ADMIN_PASSWORD` 없음, deploy/README.md:27·204 [계획], CHECKLIST.md:188 [조사]. compose 는 `~/Beside` 에서만 실행 | 3시간 + Pod 약 $1 |
| M12 | [BE·GEN] 시연 당일 GPU 절차를 정하고 리허설에서 한 번 실제로 해 보기 (아래 순서) | RUNBOOK 'GPU Pod 다시 세우기', FD16a 98 s [실험 결과], Pod 8개 EXITED, A40 Pod 재고 EU-SE-1·CA-MTL-1 LOW(11-25, D3 get-gpu-type) [조사] | 2시간 + 리허설 안에서 |
| M13 | [BE·**사람**] R3 외부 가동 감시: 콘솔에서 메일 알림 채널 만들기 → `deploy/gcp/uptime_check.sh create <channel>` | `gcloud monitoring uptime list-configs` 비어 있음 [조사]. RUNBOOK '알림·감시' "VM·Caddy 가 죽으면 알림이 없다" [직접 확인] | 사람 15분 + 10분, $0(100만 회까지 무료, D3 인용 Google 가격 예시) |
| M14 | [BE·전원] 발표 수치 한 장과 측정표 3종 묶기. 조건(사진 1장 반복, 1회 실행)을 표마다 적기 | vm-1st~5th, failure-drills.md, METRICS.md:92, PLAN.md:190 DoD | 4시간 + 1일(앱 표는 M9 뒤) |
| M15 | [전원] 슬라이드(문제 → 구현 → 실험 → 결과 → 개선) | 리포에 pptx·key 0개 [조사] | 2~3일 |
| M16 | [전원] GPU를 켜고 M12 절차까지 포함한 리허설 1회 | PLAN.md:191 [ ] | 반나절 |

M12 의 시연 당일 GPU 순서:
1. 시연 2시간 전(T-2h) Pod를 켜고 healthz(`instanceId`) 확인 → 짧은 작업 1건으로 끝까지 확인
2. 실패하면 같은 볼륨으로 새 Pod(98 s)
3. 그것도 안 되면 스텁 폴백: `.env` 의 `INFERENCE_BASE_URL` 을 바꾸고 backend 재생성(deploy/README.md:55·65). 화면에서 "생성 결과 아님"을 밝힘
4. 최후에는 녹화 영상

### A-2. should

| # | [트랙·담당] 할 일 | 근거 | 예상 시간 |
|---|---|---|---|
| S1 | [BE] 문서 정리(A-5 목록) | 표기 규칙 위반, PLAN.md:190 "실험 기록과 숫자 일치" | 1~2시간 |
| S2 | [BE] 멘토 요구에 답하는 문서 3장 (아래) | TRAFFIC_MODEL.md:13-15·101·213-215, vm-4th §9, vm-5th | 반나절~1일 |
| S3 | [BE] 이중화 Phase 0~1(B절) | B절 | 약 2.5일(줄인 판 1.8일) |
| S4 | [전원] 보고서 초안과 팀 리뷰. 제출일이 발표 뒤면 발표 뒤로 | PLAN.md:191 | 2~3일 |
| S5 | [GEN·BE] exp-03 전처리 3안 사람 채점. M10 과 같은 Pod·채점 세션에서 | exp-03 :5·:10 "실행 전" | 반나절 |
| S6 | [GEN] gltf-validator 로 정적·털 맵·리깅 GLB 3개 검사 | GLB_SPEC.md:11·21·154 [계획] | 1시간 |
| S7 | [UN] 기기 확인 묶음 (아래) | unity/README.md:76, PLAN.md:85·93·97 | 2시간(M8 세션) |
| S8 | [BE] 장애 대응 녹화: Pod 서비스 `kill -9` → R7 재시작, 또는 backend SIGSTOP → R4 | 리포에 영상 없음. R7 41 s, FD2 112 s [실험 결과] | 2시간, Pod 약 $1(M11·M12 와 같은 창) |
| S9 | [**사람**·전원] 라이선스 결정: 공개 리포에 커밋된 AnimalLift 출력(SMAL 파생)과 CC BY-NC 체크포인트 | gh repo PUBLIC, `unity/Assets/Models` 추적 중, ANIMALLIFT_SETUP.md:66·134, unity/CLAUDE.md:26 [조사] | 1시간 |
| S10 | [GEN] 리깅 템플릿 재현성: 리포 밖 `capstone/rigging` 스크립트를 문서화 | CHECKLIST.md:99 | 1~2시간 |
| S11 | [**사람**] `unity/experiments` 를 [실험 결과] 정의에 넣을지 결정(털 측정을 인용하려면 필요) | unity/experiments/README.md 끝 | 5분 |
| S12 | [**사람**·GEN] Runpod 크레딧 소진 계획과 소진 기준 조교 확인 | 잔액 $181.52, 11월 Pod $17.93 [조사], budget :83·:229 | 1시간 |

S2 문서 3장:
- TRAFFIC_MODEL 3·5절을 잰 S_busy 로 고칩니다. 지금은 "86대" [가정]과 "10~13대" [계산]이 섞여 있습니다.
- 큐 처리량과 확장 기준을 한 장으로 모읍니다. 증설 기준은 [제안값]이고 GPU를 실제로 늘린 적은 0회임을 밝힙니다.
- 멘토 원안 F1~F5 와 실제로 한 FD 를 맞춘 대응표를 만듭니다.

S7 기기 확인 묶음:
- 업로드 실패 재시도: 코드는 `JobManager.cs:142-145·167-170` 에서 고쳐졌고 기기 확인만 남았습니다.
- fail → retry 흐름과 F2 다시 시도
- 취소(24a6ac1)
- 스케일·회전 조작: PLAN 의 핀치 범위 0.2~1.5 ↔ `besideObj.prefab` 의 `m_MinScale` 0.3

### A-3. could

| # | [트랙·담당] 할 일 | 근거 | 예상 시간 |
|---|---|---|---|
| C1 | [BE·GEN] 0 B `sample-dog.glb` 교체(라이선스 결정 뒤) | blob e69de29. 유효한 GLB 는 VM `~/beside-stub/sample.glb` 에만 있고 리포 밖이며 출처 기록이 없음 | 1시간 |
| C2 | [**사람**] Runpod Secret 값에서 `INFERENCE_TOKEN=` 이름 떼기. GPU 예비 Pod·볼륨을 만들면 must 로 올라감 | 2026-11-17_runpod-connect.md:40, `start_pod.sh:89-90` | 5분 |
| C3 | [**사람**] `default-allow-rdp` (0.0.0.0/0 tcp:3389) 삭제 | firewall-rules list [조사] | 2분 |
| C4 | [**사람**] GCP 크레딧 사용량과 예산 알림 확인 | deploy/README.md:69 | 10분 |
| C5 | [BE] FD4 디스크 가득 참, R4 디스크·백업 알림, 실제 모델이 멈춘 경우 재현 | RUNBOOK '남은 일' [직접 확인] | 1~2시간(훈련 볼륨) |
| C6 | [BE] F3·F4 실행(GPU 필요 없음, 스크립트는 [구현됨]) | TRAFFIC_MODEL.md:213-214 | 반나절 + 중단 창 |
| C7 | [BE·GEN] GPU 예비 Pod B(B절 G-c) | 0절 3번 | 0.5일 + 시연 시간 $0.59/h |
| C8 | [UN] 진행 화면에 queue·estimate 표시 | `JobResponse.cs` 에 해당 필드 없음 | 반나절 |
| C9 | [UN] 기기 2종 FPS, minSdk 34 가 의도인지 확인(`ProjectSettings.asset:184` ↔ README 는 24), 오류 문구 기기 확인, metrics 중복 줄, 털 성능을 main 코드로 다시 재기, QUEUE_FULL 확인 | 조사 결과 | 각각 10분~반나절 |

### A-4. 발표 뒤 (종강 12/21 전)

**운영·백엔드 (BE)**
- [BE·UN·**사람**] 큐 상한을 운영에서 켜기. QUEUE_FULL 을 아는 Unity 새 빌드를 팀·시연 기기에 깐 뒤에 합니다. 발표에서는 "5차 부하 시험에서 검증했고, 운영에서는 꺼 둠"으로 씁니다. 근거: vm-5th:349·376, CHANGELOG.md:34.
- [BE] 이중화 Phase 2·3(B절).
- [BE] FD16b 의 남은 두 길(120 s 시간 초과, GLB 내려받기 60 s 무응답)과 FD9 진짜 블랙홀 재현(vm-5th:377-379).
- [BE] 부하 시험 남은 것: A1 재측정, 한국 리전 부하 발생기, A3 large, A5·A6, 스텁 G1·G2, 넘치는 c=2. 근거 CHECKLIST.md:135-176. 2~3일.
- [BE] 보존 정책 자동 삭제 #23(1일), 요청 제한 #22·F5(1~2일).

**3D·Unity (GEN·UN)**
- [GEN] exp-04.
- [UN] 확장 기능(털 결용 TANGENT, 화질 옵션, 바닥 위 걷기, EditMode 시험, 크래시 수집), `insecureHttpOption` 정리.

**사람이 할 정리**
- 서식6, 월구독 해지·카드 삭제·크레딧 잔액 0 증빙(budget :210, §10).
- 종강 정리. 순서: 백업을 개발 PC로 옮기기 → VM, 고정 IP, 스냅샷(정기 5개 + 훈련용 7.03 GB), 버킷, (만들었다면) `beside-standby`, Runpod Pod 8개, 볼륨 `z4kgw4v5zi`(삭제는 되돌릴 수 없음). 근거 deploy/README.md:69-75.
- 리포 정리: worktree 15개, worktree-agent 브랜치 7개, stash 1개, 미추적 5개. `docs/budget/` 은 커밋할지 정해야 합니다.

### A-5. 끝났는데 문서가 [계획]으로 남은 곳

| 파일 | 고칠 곳 → 실제 |
|---|---|
| docs/CHECKLIST.md | :3·:37·:69 태그 [계획]·"`git tag -l` 결과 없음" → api-v1.0·1.1·1.2 있음(같은 파일 :25 와도 어긋남). :17 발표일 없음 → 12/04. :26 "timeout 120 s, VM 배포 전" → VM 625c2a5 가 120000. :30·:81 재시도 버그가 열린 항목 → 코드는 고쳐짐(기기 확인 전). :49-51 "exp 기록 0건" → 기록 5개(exp-01 [실험 결과]). :59 S5~S9 병합·태그 [계획] → 끝. :61 측정표에 5차 없음. :82 "VM 추론은 스텁" → 실제 AnimalLift(+rig). :85 ProjectSettings 미커밋 → 없음. :87 털 맵 "Pod 반영 전" → Pod 가 origin/main 을 받으니 반영됐을 것 [추정]. :175 5차 비교 [계획] → 끝. :211 "main 이 25커밋 앞" → 0/0 |
| docs/PLAN.md | :12 "exp 기록 0건" → exp-01 있음. :64·:154 태그·병합 [계획] → 끝. :175·:177 VM 측정 행에 5차 없음. :178 켜는 조건 중 5차는 끝(남은 것은 Unity 기기 설치뿐). :183 5단계 기간 ↔ 12/04 |
| docs/ops/RUNBOOK.md | 'GPU Pod 다시 세우기' 2번은 손으로 fetch·bootstrap → 실제로는 Pod 시작 명령이 자동으로 함(리포에 기록 없음). 3번의 "같은 볼륨 Pod 둘" 규칙에 이유를 적기(Runpod 은 허용, 상태 폴더가 부딪힘). 4번·'남은 일'의 "운영 600 s" → 120 s 배포됨. 'instanceId·FD16b·FD5·FD9 재현 [계획]' → vm-5th 7절에서 함(훈련 backend 였고, 알림은 운영 웹훅이 아니라 훈련 수집기로 감). 첫 스냅샷 확인 [계획] → 확인. 리뷰 브랜치 877f63b 가 'DB 복구' 절을 고쳤으므로 병합한 뒤에 손댑니다 |
| docs/ops/REDUNDANCY.md | :82 첫 스냅샷 [계획] → 정기 스냅샷 5개 READY(20261120191411~20261124191411). 3절 GPU 행의 재고 "EU-SE-1 Medium"(11-20) → 지금 LOW(D3, 11-25) |
| docs/ops/2026-11-20_failure-drills.md | :24·:26·:130·:131 → FD16b·FD5·FD9 는 재현했고 스냅샷도 확인됨. FD4 만 남음 |
| docs/api/CHANGELOG.md, REVIEW_CHECKLIST.md:5, CONTRACT.md:243 | :22·:68·:74·:77 "병합·태그·VM 배포 [계획]" → 끝(f6dfa66, VM 625c2a5) |
| docs/perf/README.md:9 / TRAFFIC_MODEL.md | "부하 시험 측정값 없음" → 1~5차 있음. TRAFFIC_MODEL :9 결과 목록에 5차 없음. :19 "VM 에서 상한 측정 없음" → 5차 g3cap19. :211 F1b [계획] → 5차에서 재현. 3·5절의 T=60 가정은 S2 에서 고침 |
| docs/perf/load/2026-11-21_vm-5th.md:378 | "Pod 시작 명령에 넣을지 [계획]" → 이미 들어가 있음(M4 에서 고정 커밋으로 바꿈) |
| docs/METRICS.md:9·92 | "리깅을 켠 실행 [계획]" → 운영 DB 에 +rig 8건이 있는데 기록이 없음(M2 결정에 따라 실험 기록으로 남길지) |
| deploy/README.md | :22 폰 앱 [계획](Caddy 로그에 흔적은 있음, 기기 기록은 없음). :28 "부하 1~3차" → 5차까지. :42 "7230dea" → 625c2a5. :217 "4GB VM" → 8 GB. :322·:340·:387 첫 스냅샷 → READY. :324·:404 "FD5 시작 거부 알림 재현 [계획]" → vm-5th(수집기로). :535 "서버 설정은 오른쪽 위" → 시안 E 에서는 홈 맨 아래 |
| GLB_SPEC.md:9·68, unity/README.md, unity/CLAUDE.md:53 | 털 맵 "Pod 반영 전" → 반영됨 [추정]. unity/README :71 "[검증됨] 실제 기기" → 증빙 없음. :88·:163 minSdk 24 → 34. :157·:255 와 unity/CLAUDE.md:53 "리깅 GLB 로 교체해 씀" → 리포 파일은 0 B. :269-276 "GPU PC" 절 → VM 절차로 |
| README.md:16-20 | "현재 상태 2026-10-31 기준" |
| docs/budget/2026-11_purchase-plan.md:3 | "아직 결제 안 함" → 결제 증빙 있음(이 폴더는 git 밖) |

### A-6. 멘토링·교수님 요구 대비

| 요구 | 답이 되는 기록 | 빈 곳 |
|---|---|---|
| 지표 5개 수집 + 대시보드 | METRICS.md §5.3 PromQL [구현됨]. 실행마다 Prometheus CSV [실험 결과](예: `docs/perf/load/data/2026-11-21_1126-g3cap19/prometheus/`). 운영 /admin 은 성공률·처리 시간 백분위·대기열·GPU·디스크 [구현됨] | Grafana 를 운영에서 띄운 적이 없음(M11). /admin 에 API 응답 시간·에셋 다운로드 실패율이 없음. 앱 측정 0건(M9). JVM 힙 패널 오류 |
| 쏘카 수준 목표 트래픽 + 단계형 부하 | TRAFFIC_MODEL.md T1~T3 [가정·파생]. vm-1st: A1 9,000명·185 RPS 에서 5xx 0이지만 drop 12라 통과 단계 없음, A2 폴링 300 RPS 통과·900 RPS 유지·1,200 붕괴, A3 small 20/s, A4 10/s [실험 결과] | 3·5절 표가 T=60 가정 그대로(S2). TRAFFIC_MODEL.md:223 규칙상 "API 계층이 T3 통과"라고 쓸 수 없음. A1 재측정, 한국 리전 발생기, A5·A6 [계획] |
| 큐 처리량·워커 확장 기준 수치화 | A40 c=1 S_busy 8.67/8.81 s → 415/409건/h, c=2 하한 495건/h [실험 결과]. T3 에 GPU 10~13대 [계산]. 큐 상한 N=19·R 165~167 s, 5차에서 기준 통과 [실험 결과] | 증설 기준(ρ>0.7 이 10분, p95 queuedMs>60 s)은 [제안값]이고 검증 안 함. 실제 증설 0회(최대 A40 1대). Pod 두 대의 처리량 μ₂ 미측정(B Phase 3) |
| 장애 시나리오 5개 | F1a, F1b(3·4차 실제, 5차 120 s), F2-real(잃은 작업 0), FD1~FD18, 5차 재현(FD16b 602→52 s, FD5 시작 거부, FD9) [실험 결과] | F3·F4·F5 [계획]. FD4 재현 안 함. 원안 번호와 맞춘 대응표 없음(S2) |
| (교수님) 배포 환경에서 부하·장애 시험 | 1~5차 모두 운영 VM. FD 는 VM·실제 Pod 에서 함 [실험 결과] | 진짜 IP 전환은 연습 안 함(B Phase 1). R3 없음(M13). 녹화 증빙 없음(S8) |

### A-7. 9일 일정 제안

| 날짜 | 할 일 |
|---|---|
| 11/25(수) | M1·M2 결정, M13 채널 만들기 [사람], M5 Android 모듈 설치 [사람], B 결정 Q1~Q4, 리뷰 브랜치 PR(그 세션) |
| 11/26(목) | M3 병합 + VM 배포 창(백업 주기 단축 포함), M4 Pod 코드 고정, M11 Grafana 띄우기, M6 에디터 Play·APK, 이중화 Phase 0 |
| 11/27(금) | Pod 세션 1(약 4 h): M7·M8·M9, Grafana 데이터 채우기와 캡처, exp-02 생성. 이중화 1-2·1-3 |
| 11/28(토) | M10 채점(2명), S5, 이중화 1-4 |
| 11/29(일) | 이중화 D-1·D-3 훈련(공지한 창), M14 수치 한 장 |
| 11/30(월) | 슬라이드 초안, S1 문서 정리, S2 멘토 문서 |
| 12/01(화) | **배포 동결.** 리허설 1(GPU 포함, M12·M16), 백업 데모 영상 확정 |
| 12/02(수) | 리허설 피드백 반영. 운영을 건드리는 훈련은 하지 않음 |
| 12/03(목) | Pod 기동 시험, R3·스냅샷 상태 확인, 슬라이드 확정 |
| 12/04(금) | T-2h Pod 기동 → 발표 |

Runpod 사용 예상: 위 Pod 세션과 리허설, 발표 당일을 합쳐 약 15 h × $0.59 ≈ **$9** [계산, A40 단가는 D3 get-gpu-type]. 잔액 $181.52 안입니다.

---

## B. 서버 이중화 계획

### B-1. 세 설계안 점수표 (각 1~5점)

| 기준 | D1 warm standby | D2 active-active | D3 GPU 먼저 |
|---|---|---|---|
| (1) 12/04 전 완료 가능성 | **3**: 전체 W0~W12 는 약 7인일이라 must 와 겹치면 못 끝냄. 핵심(대기 VM + 계획 전환)은 약 2.5일이라 가능 | **1**: 약 15인일. D2 스스로도 "12/04 전 운영 배포 안 함" | **2**: P2 가 핵심 경로 500~900줄(2~4일)이고, 리뷰 브랜치 병합(P0)을 기다려야 함 |
| (2) 훈련에서 드러난 위험을 줄이는 정도 | **3**: VM·디스크·zone 유실(FD6), 감지 공백(R3·대기 감시), 파일 RPO 24 h 를 줄임. 가장 잦은 GPU 장애는 그대로 | **2**: FD1~3·FD6 는 이미 R4·GCE 가 17.8~145 s 에 복구함. GPU 는 그대로이고 새 장애 지점(Cloud SQL zonal, gcsfuse 캐시)이 생김 | **4**: 실제로 가장 잦았던 GPU 장애(FD16a·R7/F2·FD10·FD17·FD18)를 겨눔. 다만 같은 데이터센터 재고가 LOW 라 두 대를 못 받을 수 있음 |
| (3) 발표 증거(측정 가능한 전환) | **5**: 계획·비계획 전환 RTO, 표지 작업 RPO, 되돌리기까지 운영에서 실측 | **2**: 12/04 전에는 단위 시험뿐 | **4**: 풀 훈련 표와 μ₂ 실측이 "확장 기준" 질문에 답함. P2 가 밀리면 증거 0 |
| (4) 비용(크레딧 안) | **4**: 12/04 까지 상시면 약 $19, 훈련 날만 켜면 약 $3 [계산, 단가는 gcloud-compute.com(제3자)] | **3**: 월 약 $205. 1~2일 창이면 $5~10 | **4**: Runpod 약 $14~21, 잔액 $181.52 안 |
| (5) 코드 위험(테스트·계약·MVP) | **4**: 앱 코드 0줄. compose 1줄, systemd, 셸, IAM(백업 계정이 버킷을 읽게 됨) | **1**: 작업 배분·상태 전이·DB·Flyway 를 바꿈. `ERROR_CODES.md:75` 문구가 바뀌고 7a8a56c 와 같은 파일 | **2**: 시연 9일 전에 `RealJobWorker`·`InferenceSlots` 를 바꿈. 같은 파일을 리뷰 브랜치가 고침 |
| (6) 되돌리기 | **5**: VM 삭제, env 한 줄, 타이머, IAM 결합만 되돌리면 됨 | **2**: PG 로 옮긴 뒤 생긴 데이터는 H2 로 돌아가지 않음 | **4**: `INFERENCE_BASE_URLS` 를 비우고 `up -d backend` |
| **합계 /30** | **24** | **11** | **20** |

### B-2. 추천: D1 을 줄여 단계로 나누고, D3 의 코드 0줄 GPU 대비를 접붙이고, D2 는 발표 뒤 시험 설계로만

**추천 이유.** 12/04 전에는 운영 앱 코드를 건드리지 않고 "실제로 넘어가는 전환"을 측정해 보여 주는 쪽이 must 작업량과 MVP 원칙에 맞습니다. 같은 이유로 GPU 쪽도 코드 대신 절차·코드 고정·예비 Pod 로 대비합니다. REDUNDANCY.md 4절이 정한 장기 순서(GPU 풀 → 저장소·DB → VM 2대)는 Phase 3 에서 그대로 따릅니다.

**전후 비교** (목표는 [제안값]):

| 항목 | 지금 | Phase 1 뒤 | Phase 2 뒤 |
|---|---|---|---|
| VM·zone 유실 RTO | 새 VM 이 응답하기까지 145 s(FD6) + 전환은 연습 안 함 → 사람 포함 20~40분 [가설] | 계획 전환 실측(목표 ≤ 3분). 비계획은 절차만 있음 | 비계획 ≤ 10분 |
| DB RPO | 최대 약 75분(deploy/README.md:327) | 약 8분 [계산] | 같음. 대기 VM 에 미리 복구돼 있음 |
| 사진·GLB RPO | 24 h | 약 8분 [계산] | 같음 |
| VM·Caddy 장애 감지 | 없음 | R3 메일 3~4분 [D1: uptime_check.sh:4-5] | + 다른 zone 의 대기 감시 약 90 s |

#### Phase 0 — 감지 공백 닫기, 문서 먼저 (11/25~26, BE 2시간 + 사람 15분)

| 조각 | 바꿀 것 | 시험·확인 | 시간 |
|---|---|---|---|
| 0-1 R3 (= M13) | 자원: 알림 채널 [사람], `uptime_check.sh create <id>` | `list-configs` 1개, `uptime_check.sh status` | 25분 |
| 0-2 문서 먼저(D1 W1) | REDUNDANCY 3절의 결정을 "연습된 대기 VM"으로 갱신. RUNBOOK 에 '대기 서버로 넘기기·되돌리기'와 금지 사항(복제본에서 `tailscale logout` 금지, 두 VM 동시 활성 금지) 추가. 0절 3번의 Runpod 사실 반영 | 문서 검토 | 0.3일 |
| 0-3 백업 계정에 버킷 읽기 권한(D1 W3) | `deploy/gcp/backup_bucket.sh`(`BESIDE_BACKUP_READ=1`), `scripts/test_gcp_ops.sh`. 지우기·덮어쓰기는 여전히 안 됨 | `DRY_RUN=1`, `get-iam-policy` 에 objectViewer | 0.2일, IAM 승인 [사람] |

- **DoD**: 가동 감시 1개가 켜져 있고, IAM 결합이 확인됨.
- **비용**: $0.

#### Phase 1 — 백업 주기 단축 + 대기 VM + 계획 전환·되돌리기 실측 (11/26~11/30, BE 약 2.5일)

| 조각 | 바꿀 것 | 시험·확인 | 시간 |
|---|---|---|---|
| 1-1 백업 주기(D1 W2, M3 배포 창에 묶음) | `deploy/docker-compose.yml` 에 `STORAGE_BACKUP_CRON`·`STORAGE_BACKUP_KEEP` 전달(기본값은 지금 값). `.env.example`. VM `.env` 는 `0 */5 * * * *`·288. 파일은 매 실행: **`beside-backup.service` drop-in `Environment=FORCE_FILES=1`**(스크립트 0줄, 0절 5번 [가설]). `beside-backup.timer` 는 `*:03/5`. 호스트별 접두사(W2-③)는 Phase 2 로 | `scripts/test_offsite_backup.sh`(WSL), CI compose config. VM 에서 5분 zip 3개·업로드 3번(`journalctl -u beside-backup`), 리뷰 브랜치의 `DB_STALE_MIN` 검사 통과 | 0.5일 |
| 1-2 대기 VM(D1 W4) | 새 `deploy/gcp/standby_vm.sh create\|ip-ephemeral\|status`: 가장 새 스냅샷 → zone b, e2-standard-2, `--no-address`, SA `beside-backup@`, 태그 `beside-web`, 라벨 `role=standby`, 삭제 방지 | `test_gcp_ops.sh` 가짜 gcloud. 할당량 여유 IN_USE_ADDRESSES 1/4, CPUS 2/12 [조사 D1] | 0.5일 |
| 1-3 대기 VM 첫 시작(D1 W5) | 새 `deploy/standby/standby_init.sh`: role 파일 → 점검 표시 → watchdog·backup 타이머 끔 → `compose stop` → `beside-stub` 제거 → `last.ok` 지움 → tailscaled 정지·상태 파일 삭제 → 임시 IP → `tailscale up --hostname=beside-standby` | WSL 가짜 명령. VM 에서 `tailscale status` 에 beside-vm·beside-standby 가 따로 보이고 "Duplicate node key" 없음 | 0.5일, Tailscale 로그인 링크·ACL 확인 [사람] |
| 1-4 계획 전환 스크립트(D1 W8 줄인 판) | 새 `scripts/failover_vm.sh --planned`(+`DRY_RUN`, 단계별 UTC 타임라인 파일). 순서: 원본 점검 표시 → `stop backend` → 오프라인 H2 백업을 **`beside-yyyyMMdd-HHmmss-SSS.zip` 형식 그대로** → `FORCE_FILES=1` 로 바로 올림 → 대상에서 당겨 와 복구 → 대상 활성화(`up -d`) → `delete-access-config beside-vm --access-config-name=external-nat` → 대상의 임시 IP 떼기 → `add-access-config --address=34.64.91.254` → healthz·61개 경로. 실패하면 원본에 IP 를 다시 붙이는 되돌림 단계 포함. `scripts/deploy_vm.sh` 에 `BESIDE_DEPLOY_STANDBY=1`(build + `up --no-start`). SSH 는 `HostKeyAlias` 를 따로 | `scripts/test_failover_vm.sh`(가짜 gcloud·ssh), DRY_RUN 출력 검토 | 1일. 수동 절차 + 타임라인 기록만으로 줄이면 0.3일 |
| 1-5 훈련 D-1·D-3 | 결과 문서 `docs/ops/2026-11-xx_failover-drills.md` 와 데이터. RUNBOOK 'VM 다시 세우기' 3~4번 [계획] → [실험 결과] | 아래 DoD | 0.5일(창 1시간, 공지 [사람]) |

**DoD** (D-1 계획 전환 a→b, D-3 되돌리기 b→a 각각):
- GCP 밖에서 `scripts/fault/drill_client.py`(1 s healthz + 20 s 마다 작업)로 잰 외부 끊김 초
- 스크립트 시작부터 공개 첫 200 까지의 시간
- `deploy/test_caddy_routes.sh` 61/61
- 표지 작업 A(마지막 백업 전)·B(뒤)가 모두 남음(RPO 0)
- Tailscale 노드가 둘로 따로 보이고 중복 키 경고가 없음
- 진행 중 작업이 `INTERNAL_ERROR` → retry 로 성공
- Pod 를 켰다면 전환 뒤 1건 COMPLETED
- 목표 ≤ 3분 [제안값]. 못 맞춰도 잰 값을 [실험 결과]로 적습니다.

**비용**:
- 대기 VM 을 훈련 날만 켜면 약 $1~3 + 디스크 약 $0.22/일 [계산: e2-standard-2 $0.086/h, pd-balanced $0.13/GB·월, gcloud-compute.com 제3자, 공식 서울값 미확인].
- 12/04 까지 상시로 켜면 약 $19(D1 §5).
- 버킷 추가분은 $1 미만.

#### Phase G — 발표 GPU 대비 (11/26~12/04, backend 코드 0줄, D3 에서 접붙임)

- **G-a (= M12)**: 당일 절차와 리허설 1회. 잴 것은 Pod 기동부터 healthz UP 까지의 시간, 그리고 실패 때 새 Pod 를 세우는 시간입니다.
- **G-b (= M4)**: 시연 주간에 Pod 코드를 태그로 고정합니다. 리뷰 브랜치 병합(7c52918)부터 Pod 동작이 바뀌기 때문입니다.
- **G-c (could, 리허설에서 GPU 가 불안정할 때만)**: D3 P3 를 따릅니다.
  - `start_pod.sh:49·53` 의 `TS_DIR`·`LOG_DIR` 를 환경 변수로 바꿀 수 있게 하고, `deploy/runpod/test_start_pod_supervisor.sh` 에 경우를 더합니다(0.5일).
  - 시연 시간에만 같은 볼륨으로 Pod B 를 켭니다(`TS_HOSTNAME=beside-gpu2`, `TS_DIR`·`LOG_DIR`·`WORK_DIR`·`NORESTART_FILE` 를 따로). Runpod 은 허용하고, 동시 쓰기는 폴더를 나눠 피합니다(0절 3번).
  - Pod B 로 넘길 때는 `.env INFERENCE_BASE_URL` 을 바꾸고 `up -d backend` 합니다. 진행 중 작업은 실패합니다. 리허설에서 이 전환 시간을 한 번 잽니다.
  - 비용은 $0.59/h × 4 h ≈ $2.4 [계산]. Tailscale 로그인 승인 [사람]과 C2 가 필요합니다.
  - 두 Pod 가 같은 호스트에 놓일 위험은 [미확인]입니다.

#### Phase 2 — warm 대기 + 비계획 전환 (12/05~12/12, 발표 뒤. 보고서가 발표 뒤에 나가면 보고서 증거로)

| 조각 | 시간 |
|---|---|
| W6 `standby_pull.sh` + 5분 타이머. role 파일이 standby 가 아니거나 backend 가 돌고 있으면 거부(활성 DB 보호) | 1일 |
| W2-③ DB 객체를 `db/<hostname>/` 로 나누고 RUNBOOK 복구 절 갱신 | 0.3일 |
| `failover_vm.sh --unplanned`(원본 `stop --async` + `delete-access-config` 에 시간 제한. 떼지 못하면 DuckDNS 안내를 출력하고 멈춤) | 0.5일 |
| (선택) W7 `standby_watch.sh` 30 s, Discord 알림 | 0.5일 |
| 훈련 D-2(운영 VM 에서 `echo o > /proc/sysrq-trigger`)와 D-3′(옛 운영 VM 을 대기로 돌려 되돌리기) | 0.5일 |

- **DoD**: `last.pull` 기준 lag ≤ 10분이 24시간 유지됨(그림). D-2 에서 첫 감지 시각(R3 메일 / 대기 감시), 장애부터 공개 200 까지 ≤ 10분 [제안값], 잃은 표지는 마지막 업로드 뒤의 것뿐.
- **비용**: 대기 VM 상시 약 $2.4/일, 7일에 약 $17 [계산]. 12/21 정리 목록에 `beside-standby` 를 넣습니다.

#### Phase 3 — 프로젝트가 계속되면 (REDUNDANCY.md 4절 순서: GPU 먼저)

1. **G1**: D3 P2 를 "G1만"으로 줄인 것(약 300줄)으로, 리뷰 브랜치 병합(7a8a56c) 위에서 합니다.
   - 추론 주소를 목록으로 받고, 주소마다 감시와 `restartWatch` 를 둡니다.
   - 시도마다 주소를 고정해 GLB 받기와 `DELETE /artifacts` 를 같은 주소로 보냅니다.
   - 진행 중 작업은 "다른 주소가 UP 이고 45 s 넘게 DOWN 이면 포기"합니다.
   - 시험은 D3 §4 그대로입니다.
2. **6차 부하 시험으로 μ₂ 실측** → D3 §3 의 규칙 `N_eff = 19 × UP 대수`를 검증합니다. R 은 대수와 거의 무관하게 165~167 s 이고, μ₂ 를 선형으로 놓은 것은 [가설]입니다. 그다음 G2 슬롯 풀로 갑니다.
3. **D2-1**(조건부 상태 전이 + 펜싱)을 첫 코드로 합니다. 시험은 D2 설계대로 잠금을 나눈 `JobTransitions` 두 개로 start↔cancel 500번 경합입니다. 그 뒤 D2-2~6 을 공유 `jdbc:h2:mem` 두 컨텍스트 시험으로 합니다. 인프라 D2-7~10 은 1~2일 시연 창에서만 세웠다 지웁니다.
4. 자동 전환(막기 단계를 확실히 한 뒤), DuckDNS 전환 연습(D-4), 지역 디스크 평가.

#### 2·3위 안에서 접붙인 것

- **D3에서**:
  - Runpod 사실(볼륨 다중 연결 허용, 동시 쓰기 위험)과 상태 폴더 분리(G-c)
  - Serverless 예비 주소를 healthz 로 깨우지 말 것($1.22/h 가 상시로 나감)
  - `N_eff` 규칙과 "R 은 GPU 대수와 무관"을 멘토 '확장 기준' 답으로
  - 45 s 포기 규칙(G1 설계)
- **D2에서**:
  - 새 Spring 프로파일을 만들지 않습니다. healthz 의 `profile` 은 활성 프로파일을 그대로 내므로(openapi.yaml:1395) 대기 VM 도 `real,prod` 그대로 띄웁니다.
  - D2-1 시험 설계를 Phase 3 첫 코드로.
  - Caddy `:8090` readiness 헬스 체크, gcsfuse 메타데이터 캐시 TTL 0, Cloud SQL 비공개 IP 는 L3 메모로 남깁니다.
- **D1 교정**:
  - 제약 문서 L1 1번의 "복제본에서 `tailscale logout`" 제안은 철회합니다. 같은 노드 키를 만료시켜 운영 VM 까지 끊을 수 있습니다 [가설].
  - 파일 매 실행은 스크립트 수정 대신 systemd drop-in 으로 합니다(0절 5번).

### B-3. 하지 않기로 한 것과 이유

| 하지 않을 것 | 이유 |
|---|---|
| D2 전체(active-active, Cloud SQL, gcsfuse, NLB)를 12/04 전에 | 약 15인일. ERROR_CODES 문구가 바뀌고, PG 로 옮긴 데이터는 되돌릴 수 없음. 운영에서 넘어가는 장면은 R4·GCE 가 이미 17.8~145 s 에 처리함 |
| D3 P2(backend GPU 풀 코드)를 12/04 전에 | 시연 9일 전 핵심 경로 500~900줄이고, 리뷰 브랜치(7a8a56c)와 같은 파일 |
| 자동 전환 | 막기 단계 없이 넘어가면 두 VM 이 함께 활성일 수 있음. R3 는 DNS·TLS 장애에도 울리는데, 그건 전환으로 풀리지 않음(D1 §7) |
| 지역 영구 디스크, Async PD | 부팅 디스크를 옮기는 동안 중단이 생김. Async PD 는 다른 지역이라 고정 IP 를 옮길 수 없음(create_vm.sh:36·70) |
| 대기 VM 에서 backend 상시 실행 | 예약 작업 중복, GPU 알림 2번, 백업 줄기 섞임(제약 10·11·19) |
| 복제본에서 `tailscale logout` | 위 D1 교정 |
| Serverless 예비 + healthz 감시 | 일꾼이 계속 깨어 있어 $1.22/h(D3 get-gpu-type), Pod 보다 비쌈 |
| 같은 Pod 안 슬롯 2개 | 처리량 1.19배뿐(queue-cap.md:64)이고 느린 연결 문제가 있음 |
| backend 두 대가 GPU 하나 공유 | Python `MODEL_LOCK` 대기가 120 s 시간 초과 안에 들어감(제약 15) |
| e2-small 대기 VM | 전환 때 크기를 바꾸려면 정지·시작이 필요해 RTO 가 약 90 s 늘어남. 크레딧은 충분함 |
| 큐 상한을 운영에서 켜기 | QUEUE_FULL 을 아는 Unity 새 빌드가 기기에 깔리기 전(CHANGELOG.md:34) |
| 12/02 이후 운영을 건드리는 훈련 | 발표 직전 운영 서버 위험 |

### B-4. 사용자에게 물어야 할 결정

**Q1. 12/04 전 이중화 범위는?**
- (a) **[추천]** Phase 0 + Phase 1: 계획 전환·되돌리기 실측과 백업 주기 단축. 백업 계정에 버킷 읽기 권한 추가 승인이 포함되고, 대기 VM 은 훈련 날만 켭니다. BE 약 2.5일, GCP 약 $3.
- (b) Phase 0 + 1 + 2: warm 대기 VM 상시(약 $2.4/일)에 비계획 전환까지. BE 2.5일이 더 들고, 발표 직전에 운영 VM 전원을 끄는 훈련이 들어갑니다.
- (c) Phase 0 만: R3 + 기존 FD6 결과 + 설계 문서.

**Q2. 백업 주기 단축을 운영에 언제 넣을까?**
- (a) **[추천]** 리뷰 브랜치 배포와 같은 창에서 DB 5분 + 파일 매 실행. backend 재시작은 1번입니다.
- (b) 파일만 매 실행(systemd drop-in, backend 재시작 없음). DB 는 매시 그대로입니다.
- (c) 하지 않음. 지금처럼 DB 최대 약 75분, 파일 24 h 입니다.

**Q3. 발표 당일 GPU 대비 수준은?**
- (a) **[추천]** Pod 1대를 일찍(T-2h 이상) 켜고, 새 Pod 절차와 녹화 영상을 준비합니다. 추가 약 $1~2.
- (b) (a) + 같은 볼륨으로 Pod B 를 시연 시간에 대기(G-c). `start_pod.sh` 수정 0.5일, 약 $2.4, Tailscale 승인이 필요합니다.
- (c) (a) + VM 스텁 폴백을 실시간 대체 수단으로. 화면에서 "생성 결과 아님"을 밝혀야 합니다.

**Q4. 발표에서 이중화를 보여 주는 방식은?**
- (a) **[추천]** D-1·D-3 녹화와 RTO·RPO 표.
- (b) 리허설에서 두 번 성공하면 계획 전환을 라이브로.
- (c) 설계 문서와 FD 결과만.