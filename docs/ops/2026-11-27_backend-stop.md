# 2026-11-27 backend 가 정지 때 SIGKILL(137)로 죽던 원인과 고침

- **왜**: 2026-11-26 계획 전환 훈련(D-3)에서 대기 VM 의 backend 가 `docker compose stop backend` 뒤 10 s 안에 끝나지 않아 SIGKILL(종료 코드 137, `OOMKilled` false)로 죽었다. 정지 단계는 두 번 다 약 11 s 였고, 멈추는 동안 backend 로그는 한 줄도 없었다. H2 파일 DB 가 정상으로 닫히지 않으니 배포·재시작·전환 때마다 같은 일이 날 수 있었다.
  - 원 기록: `docs/ops/2026-11-26_failover-drills.md` '찾은 것 4', `docs/ops/data/2026-11-26_failover-drills/observations.txt` '=== Backend stop'(이 글을 쓸 때는 브랜치 `ops/standby-tailnet-2026-11-26` 의 커밋 `b005f1a` 에만 있다).
- **표기**: [실험 결과] 아래에 조건과 값이 있다. [구현됨] 코드와 시험이 이 리포에 있다. [가설] 확인하지 않았다. [계획] 아직 하지 않았다.

## 요약

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| 기다리는 작업 1건(GPU 꺼짐)이 있을 때 정지 | 30.44~30.47 s (3회, 개발 PC, Ctrl+C) [실험 결과] | 0.66~0.72 s (3회) [실험 결과] |
| 작업 12건(GPU 대기·슬롯 줄·실행기 큐) | 30.65 s (1회) [실험 결과] | 0.71 s (1회) [실험 결과] |
| 같은 상황의 컨텍스트 닫기(시험 3가지) | 30.26 s · 30.04 s · 30.03 s [실험 결과] | 0.22~0.39 s [실험 결과] |
| 기다리던 작업 | DB 가 닫힌 뒤에야 interrupt → 기록 못 함, 다음 시작 때 `InterruptedJobRecovery` 가 실패 처리 | DB 가 열려 있을 때 `FAILED` `INTERNAL_ERROR` "… interrupted (server shutting down)" |
| 정지 중 로그 | 30 s 뒤 한 줄뿐(SIGKILL 이 먼저) | 시작에 "Server stopping: …" 한 줄 |

## 재현 [실험 결과]

- **조건**: 개발 PC(Windows 11, MS JDK 17.0.17), `main` `32c5e4e` 로 만든 bootJar, 프로파일 `real,prod`(prod 와 같은 JSON 로그·`inference.offline-wait-ms` 30분), 추론 주소는 응답하지 않는 `http://10.255.255.1:8001`(꺼진 Pod 처럼 연결이 시간 초과), 사진 검사·크롭 끔, 업로드 모드, 웹훅 없음.
- **신호**: Windows 에는 SIGTERM 이 없어 Ctrl+C(`GenerateConsoleCtrlEvent`)를 보냈다. JVM 은 SIGINT 로 받아 SIGTERM 과 같은 종료 훅(Spring 의 `SpringApplicationShutdownHook` → 컨텍스트 닫기)을 돌린다. 그래서 종료 코드는 143 이 아니라 130 이다. 도구: 스크래치 `stopdrill.py`·`ctrlc.py`(리포 밖).
- **값**:
  - 작업 없음: 0.55 s 에 종료(130).
  - PENDING 작업 1건(GPU 를 기다림): **30.47 s** 에 종료(130). Docker 였다면 10 s 에 SIGKILL 이다.
- **스레드 덤프**(Ctrl+C 2 s 뒤, `jstack`):
  - `SpringApplicationShutdownHook` 이 `DefaultLifecycleProcessor$LifecycleGroup.stop` 의 `CountDownLatch.await` 에서 기다린다.
  - 작업 스레드 `task-1` 은 `RealJobWorker.waitUntilUp` → `InferenceHealthMonitor.refresh` 에 있다(GPU 를 기다리는 루프).
- **종료 로그**(Spring 종료 단계 DEBUG 를 켜고):
  - Ctrl+C 직후 관리 포트 컨텍스트의 두 단계가 끝나고, 본 컨텍스트의 phase `2147483647` 에서 멈춘다.
  - 30 s 뒤 처음이자 마지막 INFO: `Failed to shut down 1 bean with phase value 2147483647 within timeout of 30000ms: [applicationTaskExecutor]`.
  - 그 뒤 JPA·Hikari 가 먼저 닫히고, 그다음에야 작업 스레드가 interrupt 되어 `interrupted during shutdown; InterruptedJobRecovery fails it at the next startup` 를 남긴다. 작업을 끝내지 못한다.

## 원인

- Spring Framework 6.1(Spring Boot 3.2)부터 `@Async` 실행기(`applicationTaskExecutor`)가 수명 주기 정지에 참여해, 돌고 있는 작업이 끝날 때까지 기다린다. 한 단계당 상한은 `spring.lifecycle.timeout-per-shutdown-phase`, 기본 30 s 다 [실험 결과: 위 로그].
- real 워커의 작업은 정지 신호를 모른다. GPU 를 기다리는 잠(`inference.offline-wait-ms`, prod 30분), 추론 슬롯 줄, `/infer` 응답(`inference.timeout-ms` 120 s), mock 의 잠 중 어디에 있든 30 s 안에 끝나지 않는다.
- 그래서 그런 작업이 하나라도 있으면 정지는 30 s 를 기다리고, Docker 는 10 s 에 SIGKILL 을 보낸다. 상한에 걸렸다는 로그도 30 s 에 나오므로 아무 줄도 남지 않는다.
- 훈련과 맞춰 보면:
  - D-3 정지(15:58:57.3) 때 대기 VM 에는 표지 작업 A2(15:53:20.3)·B2(15:58:14.9)가 GPU 를 기다리는 PENDING 이었다(GPU 꺼짐, 다음 시작 15:59:57 에 `InterruptedJobRecovery` 가 실패 처리). 슬롯이 1개라 하나는 GPU 대기 잠, 하나는 슬롯 줄에 있었다.
  - 종료 15:59:09.08 = 정지 시작 + 약 11.8 s(Docker 10 s + 죽이기). 작업이 없으면 0.55 s 에 끝난다(위).
  - D-1 의 원본 VM 에도 표지 작업 A·B 가 PENDING 이었다(대상이 켜진 15:50:00 에 실패 처리). 정지 단계가 같은 약 11 s 였다.
  - 그러므로 훈련의 137 은 이 원인으로 본다 [가설: 재현과 같은 조건·같은 증상. VM 에서 덤프를 뜨지는 않았다].

## 고친 것 [구현됨]

1. **`service/ShutdownSignal`**(새 클래스): 컨텍스트가 닫히기 시작하는 `ContextClosedEvent` 에 신호를 올린다. Spring 은 이 사건을 수명 주기 정지와 빈 소멸 전에 보내므로 DB 는 아직 열려 있다. 정지 시작에 INFO 한 줄을 남긴다. 관리 포트의 자식 컨텍스트가 보내는 닫힘은 무시한다.
2. **워커의 기다리는 곳이 신호를 본다**:
   - `RealJobWorker`: 실행 시작, 슬롯 줄(신호에 바로 깨움), GPU 대기 잠(`ShutdownSignal.sleep`), 진행 중 요청(`InferenceClient.Watch` 가 250 ms 마다 보다가 `InterruptedException` 을 던지면 요청을 취소하고 그대로 올린다).
   - `CutoutStage`: 정지 뒤에 permit 을 받은 작업은 크롭을 돌리지 않고 돌려준다(permit 을 기다리던 작업이 하나씩 크롭을 돌며 정지를 늦추지 않게).
   - `MockJobWorker`: 두 잠.
   - 그 결과 작업은 지금까지의 interrupt 처리 길로 `FAILED` `INTERNAL_ERROR`("Real/Mock worker interrupted (server shutting down)")가 된다. 이제는 DB 가 열려 있을 때다. 앱은 다시 시도하면 된다(다음 시작의 `InterruptedJobRecovery` 와 같은 결과, 더 일찍).
3. **스레드를 interrupt 하지 않는다**: H2 호출 중인 스레드에 interrupt 가 닿으면 DB 파일 채널이 닫힐 수 있다(`RealJobWorker` 의 'JDBC calls can fail while it is set' 주석). 그래서 신호는 기다림만 깨우고, 자기 예외(`ShutdownSignal.Stopping`)를 던진다. 이 예외 뒤에는 interrupt 표시를 되살리지 않는다.
4. **`spring.lifecycle.timeout-per-shutdown-phase=5s`**(`application.properties`): 위 고침으로 정지는 1 s 안쪽이다. 5 s 는 신호를 보지 않는 다른 작업(앞으로 넣을 대기, 예약 작업의 백업 등)이 있어도 DB 닫기가 Docker 의 대기 안에서 시작되게 하는 상한이다. 웹 서버는 즉시 정지(`server.shutdown` 기본)라 이 값에 걸리지 않는다.
5. **compose `stop_grace_period: 30s`**(`deploy/docker-compose.yml` backend): 정상 정지는 이 값만큼 기다리지 않는다(약 1 s). 앱 안 상한 5 s 뒤의 빈 닫기(전처리 모델 닫기는 쓰는 중인 크롭을 기다린다, H2 닫기, 트레이스 내보내기)가 늦어져도 SIGKILL 로 H2 를 끊지 않게 둔 여유다. 대가: JVM 이 정말 멈춘 경우에만 정지가 10 s 대신 30 s 걸린다. 계획 전환은 멈춘 원본 디스크를 그대로 복사하므로, 원본 H2 가 정상으로 닫히는 쪽을 골랐다.

시험 [구현됨]:

- `ShutdownTimeTest`: 파일 H2·Tomcat·관리 포트가 있는 실제 컨텍스트를 띄우고, (1) GPU 를 기다리는 PENDING 작업, (2) `/infer` 응답을 기다리는 PROCESSING 작업, (3) mock 의 PENDING 작업이 있을 때 `close()` 가 3 s 안에 끝나는지와, 닫힌 DB 를 다시 열어 작업이 `FAILED` `INTERNAL_ERROR` 와 워커의 메시지로 남았는지 본다. 고치기 전에는 세 경우 모두 30 s 로 실패했다.
- `ShutdownSignalTest`: 잠·확인·깨우기·자식 컨텍스트 무시·interrupt 표시.
- `InferenceClientTest.aWatchThatThrowsInterruptedGivesTheRequestUpAtOnce`: POST·GLB 받기 중 Watch 가 `InterruptedException` 을 던지면 5 s 안에 같은 예외로 끝나고 임시 GLB 가 남지 않는다.

## 고친 뒤 잰 값 [실험 결과]

- **조건**: '재현' 과 같다. 고치기 전 jar 는 `32c5e4e`, 고친 뒤 jar 는 이 브랜치(`fix/backend-stop-sigkill`)로 만든 bootJar. 2026-11-26 17:50~17:55 UTC 에 차례로 돌렸다.
- **정지 시간**은 Ctrl+C 를 보내기 직전부터 프로세스가 끝날 때까지다. 보내는 도구가 0.5 s 쉬고 나서 재기 시작하므로 약 0.55 s 보다 짧은 값은 0.55 s 근처로 나온다. 그래서 로그의 "Server stopping"(또는 상한 줄)부터 "HikariPool-1 - Shutdown completed." 까지도 함께 적었다.
- **DB**: 끝난 뒤 H2 파일을 읽기 전용으로 열어 작업 상태를 셌다(H2 2.2.224 Shell).

| jar | 작업 | 정지 (s) | 종료 코드 | 로그: 시작 → Hikari 닫힘 | 끝난 뒤 DB 의 작업 |
|---|---|---|---|---|---|
| 고치기 전 | 0 | 0.55 | 130 | (줄 없음) | — |
| 고치기 전 | 1 | 30.47 · 30.45 · 30.44 | 130 | 30 s 뒤 상한 줄 → 0.02 s | PENDING 1 (다음 시작의 `InterruptedJobRecovery` 몫) |
| 고치기 전 | 12 | 30.65 | 130 | 30 s 뒤 상한 줄 → 0.02 s | PENDING 12 |
| 고친 뒤 | 0 | 0.56 | 130 | 0.02 s | — |
| 고친 뒤 | 1 | 0.66 · 0.66 · 0.72 | 130 | 0.24 · 0.24 · 0.27 s | FAILED `INTERNAL_ERROR` "Real worker interrupted (server shutting down)" 1 |
| 고친 뒤 | 12 | 0.71 | 130 | 0.28 s | 같은 FAILED 12 |

- 작업 12건은 하나가 슬롯을 잡고 GPU 를 기다리고, 7건은 슬롯 줄에서, 4건은 실행기 큐에서 기다리는 상태다(스레드 8개). 큐에 있던 4건도 정지 중에 시작되자마자 끝났다.
- 종료 코드 130 은 SIGINT(Ctrl+C)라서다. 리눅스의 SIGTERM 이면 같은 종료 훅을 거쳐 143 이 된다(JVM 동작, 여기서는 재지 않음).
- 자바 시험(`gradlew test`, 2026-11-27): 496개 중 실패 0, 건너뜀 12. 그 전 한 번의 전체 실행에서 `ShareApiTest` 1건이 mock 작업 완료를 5 s 안에 보지 못해 실패했는데, 혼자 3번·전체 다시 1번 모두 통과했다 [가설: 시간에 민감한 시험의 흔들림. 이 변경은 mock 의 잠을 같은 길이의 `CountDownLatch.await` 로 바꾼 것뿐이다]. 브라우저 JS 시험(`node --test backend/src/test/js/*.cjs`): 75개 중 통과 74, 건너뜀 1(실행 중 서버가 필요한 것).

## 남은 것 [계획]

- 실제 SIGTERM·종료 코드 143 은 리눅스에서만 잴 수 있다. 개발 PC 에는 리눅스 JDK·Docker 가 없어 재지 않았다. 배포 뒤 VM 에서 PENDING 작업을 하나 둔 채 `docker compose stop backend` 를 하고 `docker inspect` 의 종료 코드(143 기대)와 정지 시간, 로그의 "Server stopping" 줄을 본다.
- 운영 VM 배포는 하지 않았다(배포 동결 12/01 전, 사용자가 정한다). 배포 전까지 운영 backend 의 정지는 여전히 작업이 있으면 SIGKILL 이다.
- 전환 훈련 문서(`2026-11-26_failover-drills.md`)의 '찾은 것 4' 를 이 글로 잇는 것은 그 브랜치를 main 에 넣을 때 한다.
