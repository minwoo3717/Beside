# API v1 오류 코드 (`error.code`)

모든 v1 오류 응답은 `{ "error": { "code", "message", "jobId?" } }` 봉투를 쓴다([openapi.yaml](openapi.yaml)의 `ErrorResponse`). 앱은 **`code`로 문구를 고르고** `message`(개발자용 영문)는 로그에만 남긴다. 목록에 없는 코드를 받으면 "일반 오류" 문구를 쓰고 크래시하지 않는다. 아래 두 표의 코드는 backend `exception/ErrorCode`와 1:1이다(`ErrorCodesDocumentTest`). API v1.2(2026-11-21 #12)에서 `JOB_NOT_PENDING`·`JOB_IN_PROGRESS`·`SHARE_NOT_FOUND`·`PET_NOT_FOUND`·`CANCELED` 를 더했다. 맨 아래 '예약된 코드'(`RATE_LIMITED`)는 이름만 정한 것이라 따로 적었고 `ErrorCode` 에 없다. 추가는 자유·변경/삭제는 계약 회의 결정 사항이다([CLAUDE.md](../../CLAUDE.md) 계약 변경 규칙).

## HTTP 오류 코드 (`ErrorResponse.error.code`)

| code | HTTP | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | 사용자 재시도 |
|---|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 모든 엔드포인트 | 파라미터·본문 형식 오류(`base` 가 아닌 `variant`(2026-11-21 에 계약에서 뺀 `hair` 포함, 작업 상태와 상관없이), `limit`/`cursor`, 0바이트 파일, 깨진 multipart 등) | 요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요. | 무의미 |
| `NO_PHOTOS` | 400 | POST /jobs | `photos` 파트가 없거나 비어 있음 | 사진을 1장 이상 선택해 주세요. | 사진 선택 후 |
| `TOO_MANY_PHOTOS` | 400 | POST /jobs | 11장 이상 | 사진은 최대 10장까지 올릴 수 있어요. | 장수 줄인 후 |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | POST /jobs | 파트에 선언된 Content-Type 이 image/jpeg · png · webp 가 아님(모든 프로파일). prod 는 사진마다 파일 앞 바이트도 검사해 JPEG·PNG·WEBP 시그니처가 아니어도 이 코드(mock·real 은 내용을 보지 않음, 아래 '프로파일 차이') | JPG, PNG, WEBP 사진만 사용할 수 있어요. | 다른 사진으로 |
| `UNREADABLE_IMAGE` | 400 | POST /jobs (대표 사진 검사) | 대표 사진(첫 장)을 이미지로 읽을 수 없음: 디코딩 실패, 화소 수 초과(64 MP, WEBP 16 MP), 읽기 5초 초과. 선언된 형식이 jpeg·png·webp 가 아니면 이 코드가 아니라 `UNSUPPORTED_IMAGE_TYPE`. prod 는 시그니처 검사가 먼저라 시그니처가 없는 파일도 `UNSUPPORTED_IMAGE_TYPE` | 대표 사진을 읽을 수 없어요. 다른 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_SMALL` | 400 | POST /jobs (대표 사진 검사) | 대표 사진의 짧은 변이 기준(제안 320 px)보다 짧음 | 대표 사진의 해상도가 너무 낮아요. 더 큰 사진을 골라 주세요. | 다른 사진으로 |
| `NO_ANIMAL_DETECTED` | 400 | POST /jobs (대표 사진 검사) | 대표 사진에서 개·고양이를 찾지 못함(감지 점수가 기준 제안 0.40 이상인 것이 없음) | 대표 사진에서 강아지나 고양이를 찾지 못했어요. 반려동물이 잘 보이는 사진을 골라 주세요. | 다른 사진으로 |
| `TOO_MANY_ANIMALS` | 400 | POST /jobs (대표 사진 검사) | 대표 사진에 개·고양이가 2마리 이상(둘째도 첫째와 같은 점수 기준(제안 0.40) 이상이고 넓이가 첫째 박스의 제안 15% 이상. 2026-11-05 에 0.50 에서 낮춤 — 두 마리 시험 사진의 둘째 개(0.48)를 놓쳐서) | 대표 사진에 반려동물이 여러 마리 있어요. 한 마리만 나온 사진을 골라 주세요. | 다른 사진으로 |
| `ANIMAL_TOO_SMALL` | 400 | POST /jobs (대표 사진 검사) | 감지된 동물 박스의 긴 변이 원본 화소로 기준(제안 224 px)보다 짧음 | 반려동물이 너무 작게 나왔어요. 더 가까이에서 찍은 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_DARK` | 400 | POST /jobs (대표 사진 검사) | 동물 박스 안 밝기의 90백분위가 기준(제안 50/255)보다 낮음. 평균이 아니라 밝은 쪽을 봐서 검은 털은 걸리지 않게 함 | 대표 사진이 너무 어두워요. 밝은 곳에서 찍은 사진을 골라 주세요. | 다른 사진으로 |
| `IMAGE_TOO_BLURRY` | 400 | POST /jobs (대표 사진 검사) | 동물 박스(긴 변 512 px 이하로 줄임)의 라플라시안 분산이 기준(제안 50)보다 작음 | 대표 사진이 흔들렸어요. 선명한 사진을 골라 주세요. | 다른 사진으로 |
| `PAYLOAD_TOO_LARGE` | 413 | POST /jobs | 장당 5 MB 또는 요청 전체 20 MB 초과 | 사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요. | 용량 줄인 후 |
| `JOB_NOT_FOUND` | 404 | /jobs/{id}, /asset, /retry, /cancel, /pet, /shares(만들기), /ratings, /admin/jobs/{id} | 알 수 없는 jobId(서버 DB 를 지웠거나 다른 서버·프로파일에 연결한 경우 포함) | 작업을 찾을 수 없어요. 사진을 다시 올려 주세요. | 새 작업으로 |
| `ASSET_NOT_FOUND` | 404 | /asset, /shares(만들기) | 작업은 완료됐지만 GLB 파일이 서버에 없음(`variant` 는 `base` 하나뿐). 보존 기간이 지나 지운 작업도 이 코드로 답하기로 했다(2026-11-21, 자동 삭제는 [계획]) | 요청한 모델 파일이 없어요. | 새 작업으로 |
| `NOT_FOUND` | 404 | 그 외 경로 | 알 수 없는 v1 경로·리소스 | 요청한 정보를 찾을 수 없어요. | 무의미 |
| `SHARE_NOT_FOUND` | 404 | /shares/{token}, /shares/{token}/asset | 없는 토큰(형식이 다른 토큰 포함)·만료·취소된 공유 링크, 또는 공유한 작업이 지워짐. 이유를 구분하지 않으며 `jobId` 를 채우지 않는다 | 공유 링크가 없거나 만료됐어요. | 무의미 |
| `PET_NOT_FOUND` | 404 | /pets/{petId} (보관함 웹) | 알 수 없는 petId(형식이 다른 값·이미 지운 펫 포함). `jobId` 를 채우지 않는다 | 반려동물을 찾을 수 없어요. 목록을 새로고침해 주세요. | 무의미 |
| `JOB_NOT_COMPLETED` | 409 | /asset | PENDING·PROCESSING·FAILED 상태에서 에셋 요청 | 아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요. | 폴링 계속 |
| `JOB_NOT_FAILED` | 409 | /retry | FAILED 가 아닌 작업에 재시도 요청 | 실패한 작업만 다시 시도할 수 있어요. | 무의미 |
| `JOB_NOT_PENDING` | 409 | /cancel | PENDING 이 아닌 작업에 취소 요청. PROCESSING 은 이미 시작한 GPU 연산을 멈출 수 없어서, COMPLETED·FAILED(이미 취소한 작업 포함)는 이미 끝나서 | 이미 생성이 시작돼 취소할 수 없어요. | 무의미(GET 으로 상태 다시 읽기) |
| `JOB_IN_PROGRESS` | 409 | DELETE /pets/{petId}?withJobs=true (보관함 웹) | 펫과 함께 지울 작업 중 PENDING·PROCESSING 이 있음. 아무것도 지우지 않고 첫 진행 중 작업의 `jobId` 를 채운다. 대기 중이면 취소한 뒤, 생성 중이면 끝난 뒤 다시 지운다. 작업 하나를 지우는 공개 엔드포인트는 v1.2 에 없다(#11) | 아직 만들고 있는 작업은 지울 수 없어요. 취소하거나 끝난 뒤 지워 주세요. | 취소·완료 후 |
| `INTERNAL_ERROR` | 500 | 모든 엔드포인트 | 예기치 않은 서버 오류 | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `QUEUE_FULL` | 503 | POST /jobs, /retry | 대기 작업(PENDING)과 지금 접수 중인 요청을 더한 수가 서버 상한(`admission.max-pending`, 기본 꺼짐)에 이르러 작업을 만들지도, 다시 대기열에 넣지도 않음(세는 규칙은 아래 '대기열 상한'). `Retry-After` 헤더(초, 1 이상)가 붙는다. 2026-11-21 결정(안건 #21), `api-v1.1`. 운영 서버는 아직 꺼 둔다 | 지금 생성 요청이 많아요. 잠시 후 다시 시도해 주세요. | `Retry-After` 초 뒤 |

### 대표 사진 검사 (`UNREADABLE_IMAGE` ~ `IMAGE_TOO_BLURRY`)

- `POST /jobs` 는 202 를 돌려주기 전에 **첫 장(대표 사진)만** 위 표의 순서대로 검사하고, 처음 걸린 이유 하나로 400 을 돌려준다. 3D 생성이 첫 장만 쓰기 때문에 나머지 사진은 기존 검사(개수·타입·용량)만 한다. 거절된 요청은 사진·작업·`Idempotency-Key` 를 남기지 않는다.
- 동물은 **개·고양이만** 센다. 기준값은 모두 **제안값**이며 서버 설정 `preprocess.check.*` 로 바꾼다. 2026-11-21 결정으로 제안값을 그대로 두고 팀 사진 판정표가 나온 뒤 다시 본다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #19). 코드 7개와 동작은 같은 날 확정했다.
- 서버 설정 `preprocess.check.enabled` 로 켠다. real·prod 프로파일은 기본으로 켜고, mock 은 기본으로 끈다(앱의 Mock 개발 흐름이 동물이 없는 화면 캡처를 올리기 때문). 그래서 같은 사진이 mock 에서는 202, real·prod 에서는 400 일 수 있다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #19, 아래 '프로파일 차이').
- 서버가 검사를 돌리지 못하면(동시 검사 대기 초과·모델 오류) 거절하지 않고 접수한다. 이 경우는 측정 기록에만 남는다.
- 구현 상태: **[구현됨]**(2026-11-05, 브랜치 `feat/preprocess`: 코드·테스트). VM 에서의 시간·메모리는 **[계획]**. 실제 사진 판정 기록은 [docs/preprocess/2026-11-05_photo-check.md](../preprocess/2026-11-05_photo-check.md).

### 프로파일 차이 (`POST /jobs` 의 400, 2026-11-21 확정)

같은 요청이라도 서버 프로파일에 따라 202 와 400 이 갈린다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #17·#19). 공개 서버(VM)는 `real,prod` 로 돈다(healthz `profile`). 앱 개발 중 mock 에서 통과한 사진이 공개 서버에서 거절될 수 있다는 뜻이다.

| 검사 | mock | real | prod (`real,prod`) | 걸리면 |
|---|---|---|---|---|
| 파트에 선언된 Content-Type 이 image/jpeg · png · webp | 함 | 함 | 함 | 400 `UNSUPPORTED_IMAGE_TYPE` |
| 파일 시그니처: 사진마다 앞 바이트가 JPEG(`FF D8 FF`)·PNG(`89 50 4E 47 0D 0A 1A 0A`)·WEBP(`RIFF` … `WEBP`) 중 하나 (서버 설정 `upload.verify-magic`) | 안 함 | 안 함 | 함 | 400 `UNSUPPORTED_IMAGE_TYPE` |
| 대표 사진 검사, 첫 장만 (서버 설정 `preprocess.check.enabled`) | 안 함 | 함 | 함 (real 에서 물려받음) | 400 코드 7개(위) |

- 시그니처 검사는 셋 중 어느 형식이든 통과시키고, 선언된 Content-Type 과 같은 형식인지는 보지 않는다(예: `image/png` 로 선언한 JPEG 파일은 통과).
- 순서: 사진마다 빈 파일(`INVALID_REQUEST`) → Content-Type → 시그니처, 그다음 같은 `Idempotency-Key` 재요청이면 기존 작업(202), 그다음 대기열 상한(켠 경우 503, 아래 '대기열 상한'), 그다음 대표 사진 검사. 그래서 시그니처가 없는 첫 장은 prod 에서 `UNSUPPORTED_IMAGE_TYPE`, real 에서 `UNREADABLE_IMAGE` 다.
- 설정 값은 backend 의 `application-prod.properties`(`upload.verify-magic=true`)·`application-real.properties`(`preprocess.check.enabled=true`)에 있고 `ProfileContractSettingsTest` 가 지킨다. **[구현됨]**

### 대기열 상한 (`QUEUE_FULL`) — 2026-11-21 결정(안건 #21), `api-v1.1`

- 2026-11-21 에 계약 회의 대신 Backend 담당이 정했다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #21 결정 칸, 근거와 D1~D12 는 [docs/perf/load/2026-11-20_queue-cap.md](../perf/load/2026-11-20_queue-cap.md) 9절). 도입하고, 거절은 503 `QUEUE_FULL` + `Retry-After` 다. 이 절과 위 표의 `QUEUE_FULL` 행이 그 계약이고 `api-v1.1` 로 올린다([CHANGELOG.md](CHANGELOG.md)). 서버·웹 Mock UI 의 코드와 시험은 **[구현됨]**(PR #19). Unity 대응 코드도 같은 PR 에 있지만 컴파일만 확인했고 에디터·기기 확인은 **[계획]**([unity/README.md](../../unity/README.md) '대기열 상한').
- 값: 목표 최악 대기 W=180 s 규칙(c=1 이면 N = ⌊W/S⌋ − 1, R = N × 3600/μ)으로 N=19, `Retry-After` 165 s 로 정했다. 4차 부하 시험의 S_busy 8.67 s(μ 415건/h) 기준 **[계산]** 이다. 5차 부하 시험에서 S_busy 를 다시 재면(GLB 에 털 맵이 들어가 처리 시간이 바뀔 수 있다) 같은 규칙으로 N 과 R 을 다시 계산할 수 있다. 슬롯 수나 GPU 수를 바꿔도 다시 계산한다.
- 운영 서버(VM)는 **꺼 둔다**: 서버 기본값 `admission.max-pending=0` 그대로이고 VM 의 `deploy/.env` 에 `ADMISSION_MAX_PENDING` 을 두지 않는다. `QUEUE_FULL` 을 아는 Unity 새 빌드를 팀·시연 기기에 깔고 5차 부하 시험이 N 을 확인한 뒤 켠다 **[계획]**. 그 전까지 공개 서버는 503 `QUEUE_FULL` 을 보내지 않는다.
- 서버 설정 `admission.max-pending`(N)이 0 이면(기본) 상한이 없고 지금과 똑같다. N > 0 이면 PENDING 작업 수에 지금 접수 중인 요청 수를 더한 값이 N 이상일 때 거절한다(D5). 거절은 사진·작업·`Idempotency-Key` 를 남기지 않는다.
- 안전망(D12): `queuedAt` 이 서버 설정 `admission.stale-after-ms` 보다 오래된 PENDING 은 세지 않는다. 돌릴 워커가 없는 작업이 재시작 전까지 자리를 막지 않게 하려는 것이다. 기본값은 정상 작업이 PENDING 으로 기다릴 수 있는 가장 긴 시간 ⌈(N + c) / T⌉ × `inference.offline-wait-ms` + 2 × ⌈N / c⌉ × `inference.timeout-ms` + 10분이다(c 추론 슬롯 수 `inference.max-concurrency`, T 작업 스레드 수 기본 8, 2배는 업로드 모드의 업로드와 GLB 받기). prod·N 19·c 1 이면 3 × 30분 + 2 × 19 × 2분 + 10분 = 2시간 56분(10,560,000 ms)이다(`inference.timeout-ms` 120 s, 2026-11-21 #7. 600 s 였을 때는 8시간). GPU 가 꺼졌거나 `/infer` 가 멈춰 기다리는 작업은 이 시간 안이라 계속 센다. 세지 않는 행 수는 지표 `beside_jobs_pending_stale` 와 서버 WARN 로그로 보인다.
- `POST /jobs` 의 검사 순서: 입력 검사(400) → 같은 `Idempotency-Key` 재요청(상한과 상관없이 기존 작업 202) → 상한(503) → 대표 사진 검사(400). 그래서 상한이 차 있으면 사진 문제(400)보다 503 을 먼저 받는다(D6).
- 같은 `Idempotency-Key` 의 작업이 이미 있거나 같은 키의 요청이 아직 접수 중(202 전)이면 상한이 차 있어도 503 을 받지 않는다(D12). 작업이 이미 있으면 그 작업을 받고, 접수 중이면 그 요청과 함께 접수해 작업은 많아야 하나만 생기고 둘 다 그 작업을 받는다. 앞 요청이 503 으로 거절됐으면 키가 남지 않으므로, 같은 키로 다시 보내도 새 요청처럼 센다(상한이 아직 차 있으면 다시 503).
- `POST /retry`: 없는 작업 404 와 FAILED 가 아닌 작업 409 를 먼저 본다. 거절된 retry 의 작업은 FAILED 그대로다. 같은 작업의 retry 가 동시에 들어오면 202 는 많아야 하나다. 자리가 있으면 나머지는 409 `JOB_NOT_FAILED` 이고, 대기열이 차 있으면 모두 503 `QUEUE_FULL` 이다.
- `Retry-After` 는 서버 설정 `admission.retry-after-seconds`(초, 기본 165)다. 앱 문구는 위 표의 것(D8)이고 GPU 가 꺼져 대기열이 찬 때도 같다. 앱은 그 시간 전에는 `POST /jobs`(새 사진으로 처음부터 시작하는 것도)도 `/retry` 도 보내지 않고, 시간이 지나도 자동으로 다시 보내지 않는다(사용자가 다시 누른다). 기다릴 시각은 앱 메모리에만 두므로 앱을 다시 켜거나 웹 페이지를 새로 고치면 잊는다. 그때 대기열이 아직 차 있으면 서버가 다시 503 으로 거절한다.

## Job 실패 코드 (`JobResponse.error.code`, `status=FAILED` 일 때)

| code | 발생 지점 | 의미 | 앱 표시 문구 (한국어) | retry 권장 |
|---|---|---|---|---|
| `INFERENCE_FAILED` | 워커 | 3D 생성 실패(모델 오류, 입력 사진 품질). real: 추론 서비스가 이 코드나 코드 없는 4xx·500 으로 답함. Mock 은 파일명에 `fail` 이 있으면 이 코드로 실패한다 | 3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요. | 예 |
| `INFERENCE_TIMEOUT` | real 워커 | 추론 서비스 응답이 `inference.timeout-ms`(real·prod 120 s, 2026-11-21 에 600 s 에서 줄임)를 넘김. PENDING 으로 기다린 시간은 들어가지 않는다 | 생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요. | 예 |
| `INFERENCE_UNAVAILABLE` | real 워커 | 추론 서비스에 연결할 수 없음(꺼짐·연결 끊김), 또는 모델이 없거나 준비되지 않음(501~504). prod 프로파일은 서비스가 준비될 때까지 최대 `inference.offline-wait-ms`(30분) PENDING 으로 기다린 뒤에 이 코드로 끝낸다 | 생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요. | 잠시 후 |
| `CONVERSION_FAILED` | 추론 서비스·워커 | OBJ/PNG → GLB 변환 실패 또는 GLB_SPEC 검증 실패. real 워커가 받은 GLB 가 없거나 glTF 2.0 바이너리가 아닐 때도 | 모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요. | 예 |
| `INTERNAL_ERROR` | 서버 | 서버 재시작·워커 중단으로 작업이 끊김(재시작 때 PENDING/PROCESSING 이던 작업), 워커(mock·real)의 예기치 못한 오류, 또는 이 서버가 받은 GLB 를 디스크에 쓰거나 결과 폴더로 복사하지 못함(디스크 가득 참 등, 장애 훈련 FD4 — 전에는 `CONVERSION_FAILED`) (HTTP 오류 코드와 같은 이름) | 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | 예 |
| `CANCELED` | 사용자(POST /cancel) | PENDING 이던 작업을 사용자가 취소함. 워커는 시작하지 않고 추론 서비스를 부르지 않는다. 실행 기록(`job_runs`·`events.jsonl`)에는 `FAILED:CANCELED` 로 남으므로 실패율 통계에서는 뺀다 | 취소한 작업이에요. 다시 시도하면 처음부터 만들어요. | 예(처음부터) |

## 예약된 코드 (구현 없음)

아래 이름은 2026-11-21 에 정해 두기만 했다([REVIEW_CHECKLIST.md](REVIEW_CHECKLIST.md) #22). 서버는 아직 이 응답을 보내지 않고 `exception/ErrorCode` 에도 없다 **[계획]**. 위 HTTP 표에 올리는 것은 구현할 때다.

- **429 `RATE_LIMITED` + `Retry-After` 헤더(초)**: 클라이언트별 요청 제한. 대상은 `POST /jobs`·`POST /jobs/{jobId}/retry`. 키(클라이언트를 무엇으로 구분할지)·한도·구현은 작업 대기열 상한을 운영에 켜고 5차 부하 시험을 한 뒤 정한다. 앱은 지금도 모르는 코드를 일반 문구로 처리하므로 깨지지 않지만, 받게 되면 `Retry-After` 초 뒤에 다시 보내는 것을 권한다.

## 규칙

- `message` 는 개발자용 설명이다. 앱은 표시하지 않으며, 서버 경로·내부 주소·추론 서비스의 원문 메시지를 담지 않는다(그런 내용은 서버 로그에만).
- `jobId` 는 오류가 특정 작업에 관한 것일 때만 채운다(`JOB_NOT_FOUND`, `ASSET_NOT_FOUND`, `JOB_NOT_COMPLETED`, `JOB_NOT_FAILED`, `JOB_NOT_PENDING`, `JOB_IN_PROGRESS`, 그리고 `/retry` 를 거절한 `QUEUE_FULL` — 2026-11-21 #21 결정(D7), `api-v1.1`. 생성을 거절한 `QUEUE_FULL` 은 작업이 없으므로 null).
- v0 `/api/jobs` 의 오류는 평면 `{ "error": "문자열" }` 이며 이 표를 따르지 않는다(동결).
- 상태 코드는 400 / 404 / 409 / 413 / 500 / 503 여섯 가지만 계약에 둔다. 503 은 `QUEUE_FULL` 전용이다(2026-11-21 #21 결정, `api-v1.1` 에서 더함. 상한을 켠 서버만 보낸다). `api-v1.2` 의 새 코드는 모두 기존 상태 코드(404·409)를 쓰고 상태 코드를 더하지 않는다. 성공 응답은 엔드포인트마다 openapi 그대로다(취소 200, 공유 링크 만들기·펫 추가 201, 공유 취소·펫 삭제 204). 429 는 위 '예약된 코드'로 이름만 정했고 아직 보내지 않는다. 프레임워크 수준 응답(405, 415 등)은 v1 봉투로 감싸되 `INVALID_REQUEST` 로 분류한다.
- 문구는 제안이다. UX 검토 후 바뀔 수 있으나 코드 이름은 유지한다.
- 보관함(tag `pets`)은 경로의 리소스가 없으면 404(`PET_NOT_FOUND`·`JOB_NOT_FOUND`), 본문·파트가 가리키는 펫이나 작업이 없거나 규칙에 맞지 않으면(없는 `petId` 로 업로드·작업 넣기, 다른 펫의 작업이나 COMPLETED 가 아닌 작업을 대표로 고르기, 이름 1~40자·메모 500자 위반, JSON 이 아닌 본문) 400 `INVALID_REQUEST` 다. 펫과 함께 지울 작업이 진행 중이면 `JOB_IN_PROGRESS`(`jobId` 를 채운다).
- 공유 링크 만들기(`POST /jobs/{id}/shares`)는 새 코드 없이 기존 `INVALID_REQUEST`(ttlDays 범위)·`JOB_NOT_FOUND`·`ASSET_NOT_FOUND`·`JOB_NOT_COMPLETED` 를 쓴다. 공개 쪽(`/shares/{token}`)은 `SHARE_NOT_FOUND` 하나만 쓰고 `jobId` 를 비운다(공유 페이지에 작업 id 를 드러내지 않는다).
- 작업 상세 웹 페이지의 API(태그 quality: `GET /jobs/{id}/ratings`, `PUT /jobs/{id}/ratings/{rater}`)와 관리 API 의 평가·앱 측정(`/admin/ratings*`, `/admin/client-metrics`, `/admin/e2e`)도 새 코드 없이 쓴다: 평가자 이름·점수 범위·comment 길이·JSON 이 아닌 본문·`metrics.csv` 헤더 불일치는 `INVALID_REQUEST`, 없는 작업은 `JOB_NOT_FOUND`, 완료되지 않은 작업의 평가는 `JOB_NOT_COMPLETED`. `metrics.csv` 의 잘못된 데이터 줄은 오류 응답이 아니라 200 결과의 `errors[]` 에 줄 번호와 함께 담는다. `client-metrics` 파트 `file` 이 없을 때도 `NO_PHOTOS` 가 아니라 `INVALID_REQUEST` 다.
- 받지 않은 엔드포인트(2026-11-21 #11): 검토 브랜치의 `DELETE /jobs/{id}`·`GET /jobs/{id}/thumbnail`·`GET /jobs/{id}/photos/{index}` 는 v1.2 서버에 없다. 요청하면 프레임워크 응답 그대로 `DELETE /jobs/{id}` 는 405 `INVALID_REQUEST`, 나머지 둘은 404 `NOT_FOUND` 다.
