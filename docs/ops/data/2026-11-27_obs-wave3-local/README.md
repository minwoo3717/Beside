# 2026-11-27 관측 보강(obs-wave3) E1 — 로컬 mock 서버에서 앱 흉내로 확인

**[실험 결과]** 브랜치 `feat/obs-wave3`(A1~A4·D1·D2 커밋 뒤), 개발 PC(Windows), JDK `ms-17.0.17`, `gradlew bootRun`(profile mock).
8080 은 다른 프로세스가 쓰고 있어 `--server.port=18080 --management.server.port=18081` 로 띄웠다.
DB·업로드·events.jsonl 은 리포 밖 임시 폴더(`%TEMP%\beside-e1`)에 두었다. Pod·GPU·Docker(Loki·Tempo)는 쓰지 않았다.
그래서 이 기록은 **서버 로그와 API 응답**에서 본 것이고, Tempo 화면에서 trace 를 본 것은 아니다(E4 [계획]).

## 무엇을 했나

앱(`BesideApiClient`)처럼 curl 로 보냈다.

1. 업로드 `POST /api/v1/jobs`.
   - 헤더 `X-Request-Id: 32f0b9d2423444528f9fb838d9f92194`(32자 소문자 hex, 앱의 흐름 id 형식)와
     `traceparent: 00-32f0b9d2423444528f9fb838d9f92194-<16 hex>-01`(trace id = 흐름 id).
   - 사진 1장(PNG 아이콘).
   - 그 뒤 `GET /api/v1/jobs/{id}` 를 1초마다 COMPLETED 까지(같은 `X-Request-Id`).
2. 제품 이벤트 묶음 `POST /api/v1/events` 1개, 이벤트 15개([events-batch.json](events-batch.json)).
   - 화면: `library`·`camera` 를 넣었다.
   - 새 이벤트: `saved_dog_open`(ok, from, count, ageDays, loadMs), `saved_dog_change`(saved), `app_error`(kind, sig).
   - `model_placed` 3번:
     - `source=job` 첫 배치(`again=false`)
     - `source=job` 다시 배치(`again=true`)
     - `source=library` 첫 배치(`again=false`)
3. `GET /api/v1/admin/funnel`([funnel.json](funnel.json))과 관리 포트 `GET /actuator/prometheus`.

## 결과

### 한 ID 로 이어짐 (멘토 2번) — 확인

서버 텍스트 로그(`[rid=… job=… trace=…]`, 로그 시각 KST)에서 세 줄이 같은 trace 로 이어졌다.

```
22:42:13.665 INFO [rid=32f0b9d2423444528f9fb838d9f92194 job= trace=32f0b9d2423444528f9fb838d9f92194] [io-18080-exec-3] JobServiceImpl : Job created: c3c4d69b-016b-4282-bf98-bf711797aa4d status=PENDING worker=mock
22:42:15.689 INFO [rid= job=c3c4d69b-… trace=32f0b9d2423444528f9fb838d9f92194] [task-1] MockJobWorker : jobId=c3c4d69b-… stage=PROCESSING traceId=32f0b9d2423444528f9fb838d9f92194
22:42:18.706 INFO [rid= job=c3c4d69b-… trace=32f0b9d2423444528f9fb838d9f92194] [task-1] MockJobWorker : jobId=c3c4d69b-… stage=COMPLETED durationMs=5024
```

- 업로드 요청 줄: `rid`(앱의 `X-Request-Id`)와 `trace`(앱의 `traceparent` trace id)가 같은 값이다.
- 응답 헤더: `X-Request-Id: 32f0b9d2423444528f9fb838d9f92194`(202).
- 워커 스레드(`task-1`)의 작업 실행 줄: 같은 trace id 를 쓴다.
  - 2026-11-26 구조에서는 작업 실행이 새 trace(링크로만 이음)였다.
  - 2026-11-27 A2 부터는 업로드 요청 span 아래에서 돈다.
  - 그래서 앱 오류 화면의 문의 번호(흐름 id 앞 8자) 하나로 업로드·작업 줄을 모두 찾는다.
- 추론 서비스 호출 span(real 프로파일)과 Python 쪽 `trace=` 는 이 실행(mock)에 없다.
  - 자동 시험 `TracingPropagationTest` 가 대신 확인한다: `POST /infer/upload` 의 `traceparent` trace id = 앱 흐름 id, flags `00` 이면 따로 된 trace.

### 새 이벤트·화면·props — 확인

- `{"accepted":15,"dropped":0}`.
- Prometheus 지표(관리 포트):
  - `beside_app_events_dropped_total{reason="bad_prop"} 0`: 새 props 가 하나도 버려지지 않았다.
  - `beside_app_events_total{platform="android"}`: `model_placed` 3, `saved_dog_open` 1, `saved_dog_change` 1, `app_error` 1, `screen_view` 6.
  - `screen_view` 6 은 `library`·`camera` 를 포함한 값이다(11-26 서버는 이 둘을 버렸다).

### 퍼널 새 필드 — 확인 ([funnel.json](funnel.json))

- **본 퍼널 6단계**: 모두 1 세션, `droppedSessions` 0.
  - 마지막 단계 `event` 는 `model_placed(source!=library)` 다.
  - `source=job` 배치가 이 단계를 끝냈다. `source=library` 배치는 세지 않았다.
- **`librarySteps`**: home → library → saved_dog_open(ok=true) → ar → model_placed(source=library), 모두 1 세션.
  - library 단계까지 8초, 그 뒤 1·2·1초.
- **`lastScreens`·`dropReasons`**: `[]`. 이 세션은 퍼널을 끝냈고 방금 받은 세션이기도 하다.
  - 이탈 분석은 마지막 이벤트가 30분 넘게 지난 세션만 본다.
  - 이탈 세션의 화면·사유 집계는 `ProductEventsAdminApiTest` 가 확인한다.
- **`jobs`**: `{"created":1,"completed":1,"failed":0,"canceled":0,"inProgress":0,"completionPct":100.0}`.

### p50 버킷 — 확인

- 스크레이프에 `http_server_requests_seconds_bucket{…uri="/api/v1/jobs",method="POST",…le="0.005"}`·`le="0.01"` 가 있다(A1).
- 이 업로드 한 건은 25 ms 를 넘었다. 첫 요청이라 세 버킷 모두 0 이다.

## 하지 못한 것

- **flags `00`·`traceparent` 없는 업로드의 대조 실행**: 이번 세션의 권한 판정이 그 명령을 막았다.
  - 두 경우는 자동 시험 `TracingPropagationTest`(flags `00` → 따로 된 trace + 링크)와 첫 시험(`traceparent` 없음 → 업로드 trace 안)이 다룬다.
- **`scripts/e2e_mock.ps1`**: 같은 이유로 돌리지 못했다. v1 전체 흐름은 backend 전체 시험과 위 1번 흐름으로 대신 확인했다.
- **Tempo·Loki·Grafana 화면 확인**: 이 PC 에 Docker 가 없다. 운영(E3 배포 창 3)과 Pod 세션(E4)은 [계획].
- **앱 실제 전송(E2, 에디터·기기)**: [계획], 사람.
