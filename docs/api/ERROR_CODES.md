# API v1 오류 코드 (`error.code`)

모든 v1 오류 응답은 `{ "error": { "code", "message", "jobId?" } }` 봉투를 쓴다([openapi.yaml](openapi.yaml)의 `ErrorResponse`). 앱은 **`code`로 문구를 고르고** `message`(개발자용 영문)는 로그에만 남긴다. 목록에 없는 코드를 받으면 "일반 오류" 문구를 쓰고 크래시하지 않는다. 코드는 backend `exception/ErrorCode`와 1:1이며, 추가는 자유·변경/삭제는 계약 회의 결정 사항이다([CLAUDE.md](../../CLAUDE.md) 계약 변경 규칙).

## HTTP 오류 코드 (`ErrorResponse.error.code`)

| code | HTTP | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | 사용자 재시도 |
|---|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 모든 엔드포인트 | 파라미터·본문 형식 오류(잘못된 `variant`, `limit`/`cursor`, 0바이트 파일, 깨진 multipart 등) | 요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요. | 무의미 |
| `NO_PHOTOS` | 400 | POST /jobs | `photos` 파트가 없거나 비어 있음 | 사진을 1장 이상 선택해 주세요. | 사진 선택 후 |
| `TOO_MANY_PHOTOS` | 400 | POST /jobs | 11장 이상 | 사진은 최대 10장까지 올릴 수 있어요. | 장수 줄인 후 |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | POST /jobs | image/jpeg · png · webp 외 Content-Type | JPG, PNG, WEBP 사진만 사용할 수 있어요. | 다른 사진으로 |
| `UNREADABLE_IMAGE` | 400 | POST /jobs (대표 사진 검사) | 대표 사진(첫 장)을 이미지로 읽을 수 없음: 디코딩 실패, 화소 수 초과(64 MP, WEBP 16 MP), 읽기 5초 초과. 선언된 형식이 jpeg·png·webp 가 아니면 이 코드가 아니라 `UNSUPPORTED_IMAGE_TYPE` | 대표 사진을 읽을 수 없어요. 다른 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_SMALL` | 400 | POST /jobs (대표 사진 검사) | 대표 사진의 짧은 변이 기준(제안 320 px)보다 짧음 | 대표 사진의 해상도가 너무 낮아요. 더 큰 사진을 골라 주세요. | 다른 사진으로 |
| `NO_ANIMAL_DETECTED` | 400 | POST /jobs (대표 사진 검사) | 대표 사진에서 개·고양이를 찾지 못함(감지 점수가 기준 제안 0.40 이상인 것이 없음) | 대표 사진에서 강아지나 고양이를 찾지 못했어요. 반려동물이 잘 보이는 사진을 골라 주세요. | 다른 사진으로 |
| `TOO_MANY_ANIMALS` | 400 | POST /jobs (대표 사진 검사) | 대표 사진에 개·고양이가 2마리 이상(둘째의 점수가 제안 0.50 이상이고 넓이가 첫째 박스의 제안 15% 이상) | 대표 사진에 반려동물이 여러 마리 있어요. 한 마리만 나온 사진을 골라 주세요. | 다른 사진으로 |
| `ANIMAL_TOO_SMALL` | 400 | POST /jobs (대표 사진 검사) | 감지된 동물 박스의 긴 변이 원본 화소로 기준(제안 224 px)보다 짧음 | 반려동물이 너무 작게 나왔어요. 더 가까이에서 찍은 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_DARK` | 400 | POST /jobs (대표 사진 검사) | 동물 박스 안 밝기의 90백분위가 기준(제안 50/255)보다 낮음. 평균이 아니라 밝은 쪽을 봐서 검은 털은 걸리지 않게 함 | 대표 사진이 너무 어두워요. 밝은 곳에서 찍은 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_BLURRY` | 400 | POST /jobs (대표 사진 검사) | 동물 박스(긴 변 512 px 이하로 줄임)의 라플라시안 분산이 기준(제안 50)보다 작음 | 대표 사진이 흔들렸어요. 선명한 사진을 골라 주세요. | 다른 사진으로 |
| `PAYLOAD_TOO_LARGE` | 413 | POST /jobs | 장당 5 MB 또는 요청 전체 20 MB 초과 | 사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요. | 용량 줄인 후 |
| `JOB_NOT_FOUND` | 404 | /jobs/{id}, /asset, /retry | 알 수 없는 jobId(서버 DB 를 지웠거나 다른 서버·프로파일에 연결한 경우 포함) | 작업을 찾을 수 없어요. 사진을 다시 올려 주세요. | 새 작업으로 |
| `ASSET_NOT_FOUND` | 404 | /asset | 작업은 완료됐지만 요청한 `variant` 파일이 없음(Mock 은 `hair` 없음) | 요청한 모델 파일이 없어요. | base 로 |
| `NOT_FOUND` | 404 | 그 외 경로 | 알 수 없는 v1 경로·리소스 | 요청한 정보를 찾을 수 없어요. | 무의미 |
| `JOB_NOT_COMPLETED` | 409 | /asset | PENDING·PROCESSING·FAILED 상태에서 에셋 요청 | 아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요. | 폴링 계속 |
| `JOB_NOT_FAILED` | 409 | /retry | FAILED 가 아닌 작업에 재시도 요청 | 실패한 작업만 다시 시도할 수 있어요. | 무의미 |
| `INTERNAL_ERROR` | 500 | 모든 엔드포인트 | 예기치 않은 서버 오류 | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |

### 대표 사진 검사 (`UNREADABLE_IMAGE` ~ `IMAGE_TOO_BLURRY`)

- `POST /jobs` 는 202 를 돌려주기 전에 **첫 장(대표 사진)만** 위 표의 순서대로 검사하고, 처음 걸린 이유 하나로 400 을 돌려준다. 3D 생성이 첫 장만 쓰기 때문에 나머지 사진은 기존 검사(개수·타입·용량)만 한다. 거절된 요청은 사진·작업·`Idempotency-Key` 를 남기지 않는다.
- 동물은 **개·고양이만** 센다. 기준값은 모두 **제안값**이며 서버 설정 `preprocess.check.*` 로 바꾼다(계약 회의에서 확정).
- 서버 설정 `preprocess.check.enabled` 로 켠다. real·prod 프로파일은 기본으로 켜고, mock 은 기본으로 끈다(앱의 Mock 개발 흐름이 동물이 없는 화면 캡처를 올리기 때문). 그래서 같은 사진이 mock 에서는 202, real·prod 에서는 400 일 수 있다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #19).
- 서버가 검사를 돌리지 못하면(동시 검사 대기 초과·모델 오류) 거절하지 않고 접수한다. 이 경우는 측정 기록에만 남는다.
- 구현 상태: **[계획]** (브랜치 `feat/preprocess`).

## Job 실패 코드 (`JobResponse.error.code`, `status=FAILED` 일 때)

| code | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | retry 권장 |
|---|---|---|---|---|
| `INFERENCE_FAILED` | 워커 | 3D 생성 실패(모델 오류, 입력 사진 품질). real: 추론 서비스가 이 코드나 코드 없는 4xx·500 으로 답함. Mock 은 파일명에 `fail` 이 있으면 이 코드로 실패한다 | 3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요. | 예 |
| `INFERENCE_TIMEOUT` | real 워커 | 추론 서비스 응답이 `inference.timeout-ms` 를 넘김 | 생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요. | 예 |
| `INFERENCE_UNAVAILABLE` | real 워커 | 추론 서비스에 연결할 수 없음(꺼짐·연결 끊김), 또는 모델이 없거나 준비되지 않음(501~504) | 생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `CONVERSION_FAILED` | 추론 서비스·워커 | OBJ/PNG → GLB 변환 실패 또는 GLB_SPEC 검증 실패. real 워커가 받은 GLB 가 없거나 glTF 2.0 바이너리가 아닐 때도 | 모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요. | 예 |
| `INTERNAL_ERROR` | 서버 | 서버 재시작·워커 중단으로 작업이 끊김(재시작 때 PENDING/PROCESSING 이던 작업), 또는 real 워커의 예기치 못한 오류 (HTTP 오류 코드와 같은 이름) | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 예 |

## 규칙

- `message` 는 개발자용 설명이다. 앱은 표시하지 않으며, 서버 경로·내부 주소·추론 서비스의 원문 메시지를 담지 않는다(그런 내용은 서버 로그에만).
- `jobId` 는 오류가 특정 작업에 관한 것일 때만 채운다(`JOB_NOT_FOUND`, `ASSET_NOT_FOUND`, `JOB_NOT_COMPLETED`, `JOB_NOT_FAILED`).
- v0 `/api/jobs` 의 오류는 평면 `{ "error": "문자열" }` 이며 이 표를 따르지 않는다(동결).
- 상태 코드는 400 / 404 / 409 / 413 / 500 다섯 가지만 계약에 둔다. 프레임워크 수준 응답(405, 415 등)은 v1 봉투로 감싸되 `INVALID_REQUEST` 로 분류한다.
- 문구는 제안이다. UX 검토 후 바뀔 수 있으나 코드 이름은 유지한다.
