# 큐 상한 로컬 동작 확인 (안건 #21, 결정 전 2026-11-20 기록)

작성 2026-11-20. 검토 브랜치 `review/queue-cap-2` 의 큐 상한이 의도대로 거절·회복하는지 개발 PC 에서 본 기록이다. 안건 #21 은 다음 날(2026-11-21) 도입·N 19·`Retry-After` 165 s 로 정했다([2026-11-20_queue-cap.md](2026-11-20_queue-cap.md) 9절). 이 기록은 작성 때 그대로 둔다.

**[실험 결과] 로컬 — 동작 확인, 용량 아님.** mock 워커(GPU 없음)와 루프백만 썼다. 처리량·대기 시간·상한 값의 근거로 인용하지 않는다. 그 숫자는 [회의 자료](2026-11-20_queue-cap.md) 의 [계산] 과 5차 부하 시험 [계획] 몫이다. k6 는 쓰지 않았다.

## 조건

| 항목 | 값 |
|---|---|
| 코드 | `review/queue-cap-2` `6be749c`(통합 검토 반영 뒤), `gradlew bootJar` 로 만든 jar |
| JDK | Microsoft OpenJDK 17.0.17 |
| 서버 | `--spring.profiles.active=mock --server.port=18080 --admission.max-pending=3 --admission.retry-after-seconds=30 --mock.worker.pending-ms=20000 --mock.worker.processing-ms=1000`, 저장소는 임시 폴더 |
| 시작 줄 | `Queue admission: on, max-pending=3 retry-after-seconds=30 stale-after-ms=4200000`(mock 은 오프라인 대기 0 이라 기본식이 2 × ⌈3/1⌉ × 10분 + 10분 = 70분. 2026-11-21 뒤 코드는 `inference.timeout-ms` 를 정하지 않은 프로파일에서도 120 s 로 계산해 같은 설정이면 2 × 3 × 2분 + 10분 = 22분(1320000) [계산]) |
| 확인 스크립트 | [scripts/queue_cap_local.ps1](../../../scripts/queue_cap_local.ps1)(curl.exe, PowerShell 5.1) |
| 사진 | `backend/src/test/resources/photos/dog.jpg`, 실패 작업은 같은 파일을 `fail-dog.jpg` 로(mock 의 실패 이름) |

## 결과 — 19개 모두 통과

원본: [data/2026-11-20_queue-cap-local/](data/2026-11-20_queue-cap-local/) 의 `checks.json`(확인 19개), `server-admission.log`(서버의 상한 줄), `metrics.txt`(실행 끝의 거절 지표), `commit.txt`.

| 확인 | 결과 |
|---|---|
| 상한 3 까지 생성 | 202 세 번 |
| 넷째 생성 | 503, `error.code` `QUEUE_FULL`, `Retry-After: 30`, `jobId` 없음 |
| 같은 `Idempotency-Key` 로 다시 | 503(거절은 키를 남기지 않음) |
| 상한이 찬 동안 FAILED 작업의 retry | 503 `QUEUE_FULL` + `Retry-After: 30`, `jobId` 는 그 작업. 작업은 FAILED·같은 오류·같은 `updatedAt` 그대로 |
| 다른 v1 오류(404) | `Retry-After` 없음 |
| v0 `POST /api/jobs` | 202(상한을 받지 않음) |
| 앞 작업들이 PENDING 을 벗어난 뒤 | 생성 202, retry 202(작업이 PENDING 으로) |

서버 쪽:
- 거절마다 한 줄이 남았다: `Queue full: refused op=create jobId=- pending=3 reserved=0 cap=3 retryAfter=30s` 두 줄, `op=retry` 한 줄.
- 실행 끝 지표: `beside_jobs_refused_total{op="create"} 2`, `{op="retry"} 1`, `beside_jobs_pending_stale 0`. 스크립트가 받은 503 수(생성 2, retry 1)와 같다.

## 한계와 메모

- 첫 실행(같은 날 `c51e742`)은 19개 중 2개가 실패했다. 스크립트가 v1 응답에 없는 `attempt` 필드를 읽은 탓이었다(v1 `Job` 에는 시도 번호가 없다). 서버 응답은 그때도 맞았다(거절된 retry 뒤 FAILED, 받아들인 retry 뒤 PENDING). 확인을 `status`·`error.code`·`updatedAt` 으로 바꾼 뒤 19개 모두 통과했고, 그 뒤 통합 검토 반영(`6be749c`)에서 다시 돌려 같은 결과를 얻었다.
- 동시 요청·경합·쓰기 순서는 이 스크립트가 아니라 backend 시험(`AdmissionGateTest`, `JobServiceAdmissionTest`, `JobServiceAdmissionOrderTest`, `JobServiceKeyOverlapTest`, `JobServiceWriteFailureTest`)이 본다.
- 앱(Unity) 화면은 보지 않았다. Unity 는 컴파일만 확인했다 **[계획]**: 에디터 Play·기기.
- 실제 GPU·VM·Caddy 를 거친 상한 동작과 값(N 19, R 165 s)은 5차 부하 시험 **[계획]** 이다.

## 다시 하기

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\minwo\.jdks\ms-17.0.17'
.\gradlew.bat bootJar
java -jar build\libs\mock-backend-0.0.1-SNAPSHOT.jar --server.port=18080 --spring.profiles.active=mock --admission.max-pending=3 --admission.retry-after-seconds=30 --mock.worker.pending-ms=20000 --mock.worker.processing-ms=1000
# 다른 창에서
.\scripts\queue_cap_local.ps1 -BaseUrl http://localhost:18080 -Cap 3 -RetryAfter 30 -PendingSeconds 20
```

서버를 `backend/` 에서 띄우면 `backend/storage/` 에 산출물이 남는다(gitignore). 끝나면 지운다.
