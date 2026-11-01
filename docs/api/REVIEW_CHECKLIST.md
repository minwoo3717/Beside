# 계약 회의 안건 — API v1.0 검토 체크리스트

대상: Backend · Unity · 3D Generation 담당자. 회의 전에 [openapi.yaml](openapi.yaml), [ERROR_CODES.md](ERROR_CODES.md), [../asset/GLB_SPEC.md](../asset/GLB_SPEC.md) 를 읽고 온다. 결정은 이 표의 "결정" 칸에 적고 [CHANGELOG.md](CHANGELOG.md) 에 반영한 뒤 `git tag api-v1.0` 을 올린다. 결정하지 않은 항목은 현재 문서 값(제안)이 그대로 유효하다.

| # | 안건 | 현재 문서 값 (제안) | 선택지 | 영향 트랙 | 결정 |
|---|---|---|---|---|---|
| 1 | `asset.url` 형식 | 서버 루트 기준 **상대 경로** `/api/v1/jobs/{id}/asset?variant=base`, 앱이 baseUrl 과 결합 | (a) 상대 경로 유지 (b) 절대 URL (서버가 호스트를 알아야 함, 폰↔PC IP 환경에서 불안정) | Unity, Backend | |
| 2 | 허용 이미지 타입 | `image/jpeg, image/png, image/webp` (v0 는 GIF 허용) | (a) 유지 (b) GIF 포함 (c) HEIC/HEIF 추가 — 앱이 JPEG 로 변환해서 올리면 불필요 | Unity, Generation(입력 품질) | |
| 3 | 숫자 상한 확정 | 사진 1~10장, 장당 5MB, 전체 20MB · 삼각형 base ≤ 20k / hair ≤ 100k · 텍스처 ≤ 2048² · GLB ≤ 10MB | 각 값 유지 / 조정. 근거: 기기 FPS·다운로드 시간·AnimalLift 출력 크기(3단계 exp-02 수치가 나오면 재검토) | 전 트랙 | |
| 4 | `hair` variant 의 v1.0 범위 | 계약에 포함, Mock 은 404(`ASSET_NOT_FOUND`). `JobResponse.asset` 은 base 만 설명 | (a) 유지 (b) `assets: []` 배열로 가용 variant 노출(추가 필드, 자유) (c) v1.0 에서 hair 제외 | Generation, Unity | |
| 5 | `Idempotency-Key` | 선택 헤더, 1~128자, 서버 DB(H2)에 저장돼 재시작 후에도 유지 | (a) 유지 (b) 제거(앱이 재시도 시 중복 작업 허용) · 키 생성 규칙: 앱이 작업마다 UUID · 보관 기간(현재 무기한) | Unity, Backend | |
| 6 | Spring ↔ Python 파일 전달 | 같은 호스트/공유 볼륨 가정, `/infer` 가 경로를 주고받음. Spring 쪽은 이 가정으로 구현됨(2026-10-04). 실제 E2E 는 팀원 GPU PC 한 대에 Spring 과 Python 을 함께 띄우기로 함(2026-10-05). 2026-10-30: 백엔드를 VM 으로 옮기기로 해 **(b) 제안**으로 바꿈 — 경로 모드(같은 PC)는 유지하고 업로드 모드(`POST /infer/upload` + `GET/DELETE /artifacts/{jobId}`, `X-Inference-Token`)를 추가(PLAN 4b단계 S2) | (a) 유지 (b) 멀티파트 업로드 + GLB 바이너리 응답 (분리 배포 가능, 구현량 증가) — 4단계 전 확정 | Backend, Generation | |
| 7 | 폴링·재시도 정책 | 폴링 1~2초, 최대 대기 Mock 60초 / real 600초(제안), retry 는 FAILED 에서만, 횟수 상한 없음 | retry 상한(예 3회) 도입 여부 · `progress` 를 real 에서 실제로 채울 수 있는지(현재 real 은 PROCESSING 동안 null → 앱은 경과 시간 표시) · 앱의 최대 대기는 큐 대기(PENDING, 서버는 `/infer` 1건씩)를 포함하므로 동시 사용자가 있으면 600초를 넘길 수 있음 — 상한을 "큐 대기 제외"로 정의할지, `inference.timeout-ms` 는 exp-01 의 1건 시간 × 3(제안) · 서버 재시작 때 진행 중이던 작업: 현재 FAILED(`INTERNAL_ERROR`) → 앱 retry / 대안: 서버가 자동 재개 | Unity, Backend, Generation | |
| 8 | `healthz` | 경로 `/api/v1/healthz`, 필드 `status, profile, workerType` | (a) 유지 (b) 루트 `/healthz` 로 이동(변경 사항) (c) `apiVersion` 필드 추가(자유 추가) | Unity, Backend | |
| 9 | 목록 API `GET /api/v1/jobs` | 개발·디버그용, 인증 없음, 최신순 cursor | (a) 유지 (b) 운영 빌드에서 비활성 프로파일 (c) 앱 "내 작업 기록" 화면에 사용 → 그러면 정식 기능으로 승격 | Backend, Unity | |
| 10 | v0 `/api/jobs` 제거 시점 | 동결 유지, Unity v1 전환(1단계, ~10-26) 후 결정. 브라우저 Mock UI(app.js)는 2026-10-04 v1 으로 전환 완료 → v0 의 리포 안 사용처는 보호 테스트뿐 | 제거 날짜 확정 / 브라우저 UI 유지 여부 | Backend | |
| 11 | v1.1 추가 (PLAN 4b단계) | 응답 필드 JobResponse `queue`·`estimate`·`petId`·`thumbnailUrl`, HealthResponse `inference`. 엔드포인트 cancel·shares·pets·ratings·admin/*·DELETE job·thumbnail. 요청 `POST /jobs` 의 선택 `petId`. 회의 전에는 main 에 넣지 않고 검토 브랜치에 둔다 | (a) 추가로 받아들임 (b) 항목별 조정 | 전 트랙 | |
| 12 | 새 오류 코드 | 작업 실패 `CANCELED`, HTTP 409 `JOB_NOT_PENDING`, 404 `SHARE_NOT_FOUND` | 이름·상태 코드 확정 (앱은 모르는 코드를 일반 문구로 표시) | Unity, Backend | |
| 13 | 공개 서버에서의 기존 경로 | v0 `/api/jobs*`·`/h2-console`·Swagger 는 prod 의 Caddy 에서 404, 목록 API `GET /api/v1/jobs` 는 Basic 인증 뒤. 코드는 그대로(#9·#10 과 함께 결정) | 유지 / 코드에서 제거 | Backend | |
| 14 | GLB_SPEC 과 실제 출력 차이 | #1 머지 뒤 실제 GLB 는 스킨(36 joints)·애니메이션 5종(Idle/Walk/Sit/TailWag/HeadTilt)이 있고, `hair` variant 는 만들지 않으며, 털 맵은 별도 PNG 다(앱은 GLB 안 `extras.beside` 를 기대). GLB_SPEC 은 "애니메이션/스킨 없음, 단일 노드" | GLB_SPEC 갱신 / 털 맵을 GLB 에 넣을지 | Generation, Unity | |
| 15 | 백엔드 벤치마크 이름 | 로컬 벤치마크(`scripts/perf`, [docs/perf/README.md](../perf/README.md))의 값은 `bench.` 접두사로 그 문서에만 정의했다(METRICS 미등록). `queuedMs` 등 METRICS 이름은 METRICS 뜻 그대로 쓴다 | (a) 벤치 전용으로 유지 (b) METRICS 에 '백엔드 벤치마크' 절로 등록 | Backend | |
| 16 | 계약 문서의 상태 표기 | METRICS(추론 수치·앱 `metrics.csv`), CONTRACT §5·§6, GLB_SPEC(변환기 '리포에 없음')이 [계획]으로 적혀 있지만 #1 로 코드가 들어와 있다([CHECKLIST.md](../CHECKLIST.md) 6절) | 표기를 [구현됨]·[실험 결과 없음]으로 갱신 / 측정 정의 차이(`convertMs` 에 리깅 포함 등)도 함께 결정 | 전원 | |
| 17 | 업로드 사진 내용 검사 | openapi.yaml createJob 과 ERROR_CODES `UNSUPPORTED_IMAGE_TYPE` 은 파트의 선언 Content-Type 만 본다고 적혀 있지만, prod 는 `upload.verify-magic=true` 로 파일 앞 바이트(JPEG·PNG·WEBP 시그니처)도 검사한다. 같은 요청이 mock 에서는 202, VM 에서는 400 (전체 코드 리뷰 2026-10-31) | (a) 계약 문서에 'prod 는 내용도 검사' 를 적는다 (b) 모든 프로파일에서 검사한다 | Backend·Unity | |
| 18 | `modelVersion` 의 `+rig` | 추론 서비스는 `/healthz` 에는 리깅 템플릿이 설정되면 `+rig` 를 붙이고, `/infer` 에는 그 작업의 리깅이 성공했을 때만 붙인다. v1.1(검토 브랜치)은 실패한 실행에 `/healthz` 값을 남기므로 리깅이 계속 실패하면 성공(+rig 없음)과 실패(+rig)가 다른 모델로 집계된다 (전체 코드 리뷰 2026-10-31) | (a) `+rig` = 템플릿 설정(두 엔드포인트 같게), 작업별 리깅 성공은 별도 필드 (b) `+rig` 를 빼고 리깅 여부를 METRICS 필드로 | 3D·Backend | |

회의 후 할 일: 결정 반영 → `openapi.yaml` 수정 → `JobV1ContractTest` 통과 → `CHANGELOG.md` → `git tag api-v1.0` → Unity DTO·FastAPI 스키마 동기화.
