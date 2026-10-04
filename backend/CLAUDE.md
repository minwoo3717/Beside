# backend — Spring Boot 3.2 / Java 17 (Backend 트랙)

공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md). 여기에는 이 트랙의 규칙·실행·테스트 명령만 적는다.

## 규칙

- `/api/jobs`(v0)는 **동결**이다. `controller/JobController`, `controller/LegacyJobResponse`, 브라우저 Mock UI(`static/`)는 Unity가 v1으로 옮길 때까지 바꾸지 않는다.
- 새 기능은 `/api/v1`(`api/v1/JobV1Controller`)에만 추가한다. 응답 DTO는 `api/v1/dto/`, 클래스 이름은 [docs/api/openapi.yaml](../docs/api/openapi.yaml)의 컴포넌트 스키마 이름과 같아야 한다(계약 테스트가 비교).
- 계약을 바꿀 때 순서: `docs/api/openapi.yaml` 수정 → 코드 수정 → `JobV1ContractTest` 통과 → `docs/api/CHANGELOG.md` 기록. 계약 테스트가 실패하면 코드가 아니라 문서가 맞는지 먼저 확인한다.
- 오류 응답: v1은 `{ "error": { "code", "message", "jobId?" } }`, 코드는 `exception/ErrorCode`와 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md)가 1:1. v0는 기존 평면 `{ "error": "문자열" }` 유지. 분기는 `GlobalExceptionHandler`가 요청 경로(`/api/v1/` 접두사)로 한다.
- 서버 내부 경로(`resultPath`, 업로드 저장 경로)는 v1 응답에 넣지 않는다.
- 워커는 `service/JobWorker` 인터페이스로만 호출한다. `MockJobWorker`는 profile `mock`, `RealJobWorker`는 profile `real`. 기본 profile은 `mock`(`spring.profiles.default`).
- Job 상태 전이는 `PENDING → PROCESSING → COMPLETED | FAILED`, `FAILED → (retry) → PENDING`뿐이다. 결과 메타데이터를 먼저 채우고 `status`(volatile)를 마지막에 바꾼다.
- 저장소는 메모리(`MemoryJobRepository`)다. H2 파일 DB 전환은 [docs/PLAN.md](../docs/PLAN.md) 0단계 항목이며 아직 하지 않았다.

## 실행

```powershell
cd backend
.\gradlew.bat bootRun                                            # profile mock (기본)
.\gradlew.bat bootRun --args="--spring.profiles.active=real"      # real: inference.base-url 필요, 워커는 TODO 스텁(INFERENCE_UNAVAILABLE 로 FAILED)
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html · 생성 스펙: http://localhost:8080/v3/api-docs
- Mock 실패 재현: 업로드 파일명에 `fail`이 들어가면 FAILED(`error.code=INFERENCE_FAILED`). 토큰은 `mock.worker.fail-when-filename-contains`, 빈 값이면 비활성.
- Mock 지연: `mock.worker.pending-ms`(기본 2000), `mock.worker.processing-ms`(기본 3000).

## 테스트

```powershell
cd backend
.\gradlew.bat test                                               # Java 전체: v0 통합, v1 API, 계약 테스트, 예외 핸들러
.\gradlew.bat test --tests "com.example.mockbackend.JobV1ContractTest"
node --test src/test/js/mock-mvp.test.cjs                        # 브라우저 Mock UI(v0) JS 흐름
$env:BESIDE_MVP_URL = 'http://localhost:8080'                    # 실행 중 서버와 함께 돌릴 때
node --test src/test/js/mock-mvp.test.cjs
Remove-Item Env:BESIDE_MVP_URL
..\scripts\e2e_mock.ps1                                          # 실행 중 서버에 v1 전체 흐름
```

## 직접 할 일 (코드로 해결되지 않음)

- `storage/results/sample-dog.glb`는 **0바이트**다. [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md)를 만족하는 유효한 GLB로 교체해야 Unity 로드 검증이 가능하다.
