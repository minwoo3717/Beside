# 계약 회의 안건 — API v1.0 검토 체크리스트

대상: Backend · Unity · 3D Generation 담당자. 회의 전에 [openapi.yaml](openapi.yaml), [ERROR_CODES.md](ERROR_CODES.md), [../asset/GLB_SPEC.md](../asset/GLB_SPEC.md) 를 읽고 온다. 결정은 이 표의 "결정" 칸에 적고 [CHANGELOG.md](CHANGELOG.md) 에 반영한 뒤 `git tag api-v1.0` 을 올린다. 결정하지 않은 항목은 현재 문서 값(제안)이 그대로 유효하다.

| # | 안건 | 현재 문서 값 (제안) | 선택지 | 영향 트랙 | 결정 |
|---|---|---|---|---|---|
| 1 | `asset.url` 형식 | 서버 루트 기준 **상대 경로** `/api/v1/jobs/{id}/asset?variant=base`, 앱이 baseUrl 과 결합 | (a) 상대 경로 유지 (b) 절대 URL (서버가 호스트를 알아야 함, 폰↔PC IP 환경에서 불안정) | Unity, Backend | |
| 2 | 허용 이미지 타입 | `image/jpeg, image/png, image/webp` (v0 는 GIF 허용) | (a) 유지 (b) GIF 포함 (c) HEIC/HEIF 추가 — 앱이 JPEG 로 변환해서 올리면 불필요 | Unity, Generation(입력 품질) | |
| 3 | 숫자 상한 확정 | 사진 1~10장, 장당 5MB, 전체 20MB · 삼각형 base ≤ 20k / hair ≤ 100k · 텍스처 ≤ 2048² · GLB ≤ 10MB | 각 값 유지 / 조정. 근거: 기기 FPS·다운로드 시간·AnimalLift 출력 크기(3단계 exp-02 수치가 나오면 재검토) | 전 트랙 | |
| 4 | `hair` variant 의 v1.0 범위 | 계약에 포함, Mock 은 404(`ASSET_NOT_FOUND`). `JobResponse.asset` 은 base 만 설명 | (a) 유지 (b) `assets: []` 배열로 가용 variant 노출(추가 필드, 자유) (c) v1.0 에서 hair 제외 | Generation, Unity | |
| 5 | `Idempotency-Key` | 선택 헤더, 1~128자, 서버 DB(H2)에 저장돼 재시작 후에도 유지 | (a) 유지 (b) 제거(앱이 재시도 시 중복 작업 허용) · 키 생성 규칙: 앱이 작업마다 UUID · 보관 기간(현재 무기한) | Unity, Backend | |
| 6 | Spring ↔ Python 파일 전달 | 같은 호스트/공유 볼륨 가정, `/infer` 가 경로를 주고받음 | (a) 유지 (b) 멀티파트 업로드 + GLB 바이너리 응답 (분리 배포 가능, 구현량 증가) — 4단계 전 확정 | Backend, Generation | |
| 7 | 폴링·재시도 정책 | 폴링 1~2초, 최대 대기 Mock 60초 / real 600초(제안), retry 는 FAILED 에서만, 횟수 상한 없음 | retry 상한(예 3회) 도입 여부 · `progress` 를 real 에서 실제로 채울 수 있는지(없으면 null 유지) · 서버 재시작 때 진행 중이던 작업: 현재 FAILED(`INTERNAL_ERROR`) → 앱 retry / 대안: 서버가 자동 재개 | Unity, Backend, Generation | |
| 8 | `healthz` | 경로 `/api/v1/healthz`, 필드 `status, profile, workerType` | (a) 유지 (b) 루트 `/healthz` 로 이동(변경 사항) (c) `apiVersion` 필드 추가(자유 추가) | Unity, Backend | |
| 9 | 목록 API `GET /api/v1/jobs` | 개발·디버그용, 인증 없음, 최신순 cursor | (a) 유지 (b) 운영 빌드에서 비활성 프로파일 (c) 앱 "내 작업 기록" 화면에 사용 → 그러면 정식 기능으로 승격 | Backend, Unity | |
| 10 | v0 `/api/jobs` 제거 시점 | 동결 유지, Unity v1 전환(1단계, ~10-26) 후 결정. 브라우저 Mock UI(app.js)는 0단계에서 v1 로 전환 예정 | 제거 날짜 확정 / 브라우저 UI 유지 여부 | Backend | |

회의 후 할 일: 결정 반영 → `openapi.yaml` 수정 → `JobV1ContractTest` 통과 → `CHANGELOG.md` → `git tag api-v1.0` → Unity DTO·FastAPI 스키마 동기화.
