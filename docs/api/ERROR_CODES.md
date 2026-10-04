# API v1 오류 코드 (`error.code`)

모든 v1 오류 응답은 `{ "error": { "code", "message", "jobId?" } }` 봉투를 쓴다([openapi.yaml](openapi.yaml)의 `ErrorResponse`). 앱은 **`code`로 문구를 고르고** `message`(개발자용 영문)는 로그에만 남긴다. 목록에 없는 코드를 받으면 "일반 오류" 문구를 쓰고 크래시하지 않는다. 코드는 backend `exception/ErrorCode`와 1:1이며, 추가는 자유·변경/삭제는 계약 회의 결정 사항이다([CLAUDE.md](../../CLAUDE.md) 계약 변경 규칙).

## HTTP 오류 코드 (`ErrorResponse.error.code`)

| code | HTTP | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | 사용자 재시도 |
|---|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 모든 엔드포인트 | 파라미터·본문 형식 오류(잘못된 `variant`, `limit`/`cursor`, 0바이트 파일, 깨진 multipart 등) | 요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요. | 무의미 |
| `NO_PHOTOS` | 400 | POST /jobs | `photos` 파트가 없거나 비어 있음 | 사진을 1장 이상 선택해 주세요. | 사진 선택 후 |
| `TOO_MANY_PHOTOS` | 400 | POST /jobs | 11장 이상 | 사진은 최대 10장까지 올릴 수 있어요. | 장수 줄인 후 |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | POST /jobs | image/jpeg · png · webp 외 Content-Type | JPG, PNG, WEBP 사진만 사용할 수 있어요. | 다른 사진으로 |
| `PAYLOAD_TOO_LARGE` | 413 | POST /jobs | 장당 5 MB 또는 요청 전체 20 MB 초과 | 사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요. | 용량 줄인 후 |
| `JOB_NOT_FOUND` | 404 | /jobs/{id}, /asset, /retry | 알 수 없는 jobId(Mock 서버 재시작 포함) | 작업을 찾을 수 없어요. 사진을 다시 올려 주세요. | 새 작업으로 |
| `ASSET_NOT_FOUND` | 404 | /asset | 작업은 완료됐지만 요청한 `variant` 파일이 없음(Mock 은 `hair` 없음) | 요청한 모델 파일이 없어요. | base 로 |
| `NOT_FOUND` | 404 | 그 외 경로 | 알 수 없는 v1 경로·리소스 | 요청한 정보를 찾을 수 없어요. | 무의미 |
| `JOB_NOT_COMPLETED` | 409 | /asset | PENDING·PROCESSING·FAILED 상태에서 에셋 요청 | 아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요. | 폴링 계속 |
| `JOB_NOT_FAILED` | 409 | /retry | FAILED 가 아닌 작업에 재시도 요청 | 실패한 작업만 다시 시도할 수 있어요. | 무의미 |
| `INTERNAL_ERROR` | 500 | 모든 엔드포인트 | 예기치 않은 서버 오류 | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |

## Job 실패 코드 (`JobResponse.error.code`, `status=FAILED` 일 때)

| code | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | retry 권장 |
|---|---|---|---|---|
| `INFERENCE_FAILED` | 워커 | 3D 생성 실패(모델 오류, 입력 사진 품질). Mock 은 파일명에 `fail` 이 있으면 이 코드로 실패한다 | 3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요. | 예 |
| `INFERENCE_TIMEOUT` | real 워커 | 추론 서비스 응답이 `inference.timeout-ms` 를 넘김 | 생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요. | 예 |
| `INFERENCE_UNAVAILABLE` | real 워커 | 추론 서비스에 연결할 수 없음, 또는 real 워커가 아직 구현되지 않음(현재 스텁 기본값) | 생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `CONVERSION_FAILED` | 추론 서비스·워커 | OBJ/PNG → GLB 변환 실패 또는 GLB_SPEC 검증 실패 | 모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요. | 예 |

## 규칙

- `jobId` 는 오류가 특정 작업에 관한 것일 때만 채운다(`JOB_NOT_FOUND`, `ASSET_NOT_FOUND`, `JOB_NOT_COMPLETED`, `JOB_NOT_FAILED`).
- v0 `/api/jobs` 의 오류는 평면 `{ "error": "문자열" }` 이며 이 표를 따르지 않는다(동결).
- 상태 코드는 400 / 404 / 409 / 413 / 500 다섯 가지만 계약에 둔다. 프레임워크 수준 응답(405, 415 등)은 v1 봉투로 감싸되 `INVALID_REQUEST` 로 분류한다.
- 문구는 제안이다. UX 검토 후 바뀔 수 있으나 코드 이름은 유지한다.
