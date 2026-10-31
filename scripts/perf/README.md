# scripts/perf — 백엔드 성능 측정과 보고서

백엔드 버전(커밋)마다 jar 를 만들어 같은 PC 에서 같은 시나리오로 돌리고, 결과 JSON 에서 A4 PDF 보고서(한국어)를 만든다. Python 표준 라이브러리만 쓴다(3.10 이상, Windows·Linux·macOS).

상태: 도구는 **[구현됨]**(단위 테스트 + 이 PC 에서 `--quick` 스모크 실행 확인). 보고서에 인용할 측정값은 아직 없다 — `--quick` 은 동작 확인용이며 **[실험 결과]** 가 아니다. 보고용 3회 반복 측정, `docs/perf/versions.json` 배치, 서술 노트 작성은 **[계획]**.

## 무엇을 재는가

한 번의 "스위트" = 버전 하나 × 반복 하나. 시나리오는 아래 순서로 돈다.

| 서버 | 시나리오 | 내용 | 기록하는 값 |
|---|---|---|---|
| A (mock, 지연 0, 새 저장소) | `startup` | Popen → 첫 healthz 200, Spring `Started ... in X seconds`, healthz 3초 뒤 RSS | `bench.startupMs`, `bench.springStartedMs`, `bench.idleRssMB` |
| | `healthz` | GET /api/v1/healthz 워밍업 100 + 500 순차 | `healthzMs` |
| | `upload_small` | 64×64 PNG 1장 POST, 워밍업 10 + 100 | `postMs` |
| | `upload_large` | 약 4.5 MB 노이즈 PNG 4장(요청당 약 18 MB), 워밍업 2 + 20 | `postMs`, `bench.uploadMBps` |
| | `upload_12mp` | 4000×3000 PNG(약 1.1 MB, 디코딩 부담) 워밍업 2 + 20, 이어서 4스레드 × 5 동시 | `postMs`, `postConcurrentMs` |
| | `e2e_mock` | POST → 10 ms 폴링 → COMPLETED → GLB(크기 확인), 워밍업 5 + 50 | `flowMs`, `completeMs`, `assetMs`, `bench.pollsPerJob` |
| | `concurrent_e2e` | 16스레드 × 10흐름, 폴링 50 ms | `postMs`, `pollMs`, `assetMs`, `flowMs`, `bench.jobsPerSec`, `bench.errors` |
| | `poll_load` | COMPLETED 작업 하나를 4프로세스 × 4스레드 × 250 GET | `pollMs`, `bench.pollReqPerSec` |
| | `scale` | 8스레드로 작업을 N = 200·1000·2000 까지 쌓고(앞 시나리오 작업 포함) 목록 첫 페이지 ×100, 중간 페이지 ×50, 단건 ×200, 업로드 ×30 | `listFirstMs@N`, `listDeepMs@N`, `getMs@N`, `postMs@N`, `bench.actualN@N`, `bench.serverTotal` |
| | `resources` | mock 시나리오를 마친 서버 A 의 최대 RSS, jar 크기 | `bench.peakRssMB`, `bench.jarBytes` |
| B (A 의 저장소, `pending-ms=600000`) | `pending_poll` | 재기동 시간, PENDING 작업 5개 중 5번째를 워밍업 20 + 200 조회 | `bench.restartStartupMs`, `pendingPollMs` |
| C (real, 스텁 패스스루) | `real_path` | 4.5 MB PNG 1장 E2E 워밍업 3 + 20(폴링 20 ms), 이어서 5건 동시 접수 | `flowMs`, `completeMs`, `assetMs`, `postMs`, `serverQueuedMs`, `serverProcessingMs`, `serverTotalMs`, `queueGapMs` |
| | `real_upload` | 같은 흐름, `inference.transfer=upload` | 위와 같음 |
| | `real_upload_prod` | 같은 흐름, 프로파일 `real,prod` (사진 시그니처 검사, 작업마다 healthz 확인) | 위와 같음 |

- 서버마다 `events.jsonl` 을 서버가 멈춘 뒤 읽어 작업 ID 별로 시나리오에 붙인다(`server` 항목: 모든 줄의 결과별 개수, 그리고 COMPLETED 실행만의 METRICS §1 `uploadMs`·`queuedMs`·`processingMs`·`totalMs` 원시값). FAILED 줄은 세기만 하고 표본에서 뺀다 — 재시작으로 끊긴 `FAILED:INTERNAL_ERROR` 는 서버가 꺼져 있던 시간을 담는다(METRICS §1). 재시도가 끝내 COMPLETED 면 마지막 attempt 를 쓴다(`measured`·`excluded` 에 쓴 줄과 뺀 줄 수).
- real 의 `server*` 표본과 `queueGapMs` 도 여기서 나온다: `start = finishedAt − processingMs`, `queuedAt = finishedAt − totalMs`, 시작 순으로 `gap = 다음 start − 이전 finishedAt`. 다음 작업이 이전 작업이 끝날 때 **이미 기다리고 있던** 쌍(`queuedAt ≤ 이전 finishedAt`)만 쓴다. 아니면 그 간격은 서버의 슬롯 넘김이 아니라 클라이언트가 다음 요청을 보낸 시각이다. 뺀 쌍 수는 `bench.queueGapExcluded`.
- 워밍업 요청은 기록하지 않는다. `poll_load` 는 스레드마다 연결용 워밍업 5회, `scale` 은 측정 묶음마다 워밍업 5회(업로드 제외)를 더 둔다.
- **서버가 쉴 때까지 기다린다**(`scenarios.SETTLE`): 서버 프로세스가 한 창 동안 CPU 를 한 코어의 5 % 이하로 쓰면 쉬는 것으로 본다(`procs.wait_idle`). 서버 A 의 측정 시나리오(`startup` ~ `scale`)가 끝날 때마다 1초 창(최대 120초), `upload_large`·`upload_12mp` 의 업로드와 real 흐름은 하나마다 시작 전에 0.5초 창(최대 30초), `upload_12mp` 의 동시 업로드 전·`scale` 의 측정 전(시드 뒤)·`pending_poll` 의 조회 전에도 기다린다. 응답 뒤에 남은 일(v2p 의 백그라운드 썸네일 큐, GC, JIT)이 다음 측정에 섞이지 않게 하려는 것이다(실사용에서 업로드는 몇 분 간격). 이 대기를 넣기 전의 빠른 실행(`--quick`, 반복 1회, 동작 확인용이라 결과는 커밋하지 않음)에서는 v2p 의 `concurrent_e2e`·`poll_load` 가 앞 업로드의 썸네일 디코드와 CPU 를 다퉈 v2 보다 2~3배 느리게 나왔다. 대기는 시간 재는 구간 밖에 있다(`test_scenarios.WaitPlacementTest`). 쉬는 것을 확인한 창 하나를 뺀 대기는 `bench.settleAfterMs`(시나리오 뒤)·`bench.settleTotalMs`(측정 전마다의 합), 시나리오 시작부터 대기 끝까지 서버가 쓴 CPU 시간은 `bench.serverCpuMs`(요청 밖으로 옮긴 일도 여기에는 남는다). CPU 시간은 Windows 에서 스케줄러 틱(약 15.6 ms) 단위이고 고정한 두 논리 CPU 가 한 물리 코어를 나눠 쓰므로 변화율만 보이고 판정하지 않는다. 대기 방식(`settings.settle`)과 우선순위(`settings.priority`)가 다른 스위트는 같은 버전이라도 한 p50 으로 합치지 않는다.
- 버전에 기능이 없으면(`capabilities`) 해당 real 시나리오는 `{"status": "skipped", "reason": "capability", "missing": [...]}`, 스텁 파이썬이 없거나 fastapi/uvicorn/python-multipart 를 못 읽으면 `skipped`(이유 `stub unavailable: ...`).

## 요구 사항

- Python 3.10+ (표준 라이브러리만), git, JDK 17(`JAVA_HOME` 또는 PATH 의 `java`; 실제 실행 파일은 `java.home` 으로 찾는다).
- 각 버전의 Gradle 빌드가 되는 환경(의존성 캐시 또는 네트워크). 빌드는 `--no-daemon`.
- real 시나리오: fastapi·uvicorn·python-multipart 가 깔린 파이썬(`--stub-python`). 스텁은 **지금 리포**의 `generation/inference_service/app.py` 를 모든 버전에 똑같이 쓴다.
- PDF: Chrome 또는 Edge(`--browser`, 없으면 흔한 설치 경로와 PATH 의 `google-chrome`·`chromium` 을 찾는다). 없으면 HTML 만 만든다.
- 측정 중에는 다른 무거운 작업(빌드, 다른 벤치마크, 화상 회의 등)을 끈다. 노트북이면 전원을 연결한다.

## 명령

```powershell
# 보고용: 버전 3개 × 3회 (약 1시간, 시나리오 순서는 반복마다 회전)
python scripts/perf/run.py --versions v0,v1,v2 --reps 3 --versions-file docs/perf/versions.json --stub-python C:/path/to/venv/Scripts/python.exe

# 동작 확인용 (횟수 1/10, N = 50/100/200, 결과는 scripts/perf/out/quick/ 에, 커밋하지 않는다)
python scripts/perf/run.py --versions v0,v1 --reps 1 --quick --versions-file scripts/perf/versions.example.json --stub-python <python>

# 일부 시나리오만 (나머지는 skipped 로 기록)
python scripts/perf/run.py --versions v1 --reps 1 --quick --only healthz,e2e_mock --versions-file scripts/perf/versions.example.json

# 보고서 (--data 는 여러 번 줄 수 있다. 같은 라벨은 반복으로 합친다)
python scripts/perf/report.py --data docs/perf/data/2026-11-01_0930 --versions-file docs/perf/versions.json --notes docs/perf/notes --out docs/perf/backend-perf-report.pdf

# 단위 테스트
python -m unittest discover -s scripts/perf -p "test_*.py"
```

`run.py` 옵션: `--work-dir`(기본 `<OS 임시 폴더>/beside-perf`, OneDrive 밖), `--out`, `--server-cpus N`(서버를 마지막 N 개 논리 CPU 에 고정, 0 = 고정 안 함, 기본 2), `--java`, `--keep-storage`(서버 저장소를 지우지 않음, 디버깅용), `--cooldown`(스위트 사이 쉬는 초, 기본 3). 진행 줄에 남은 시간(ETA)을 찍고, 스위트나 시나리오에 오류가 있으면 종료 코드 1 이지만 측정한 것은 모두 쓴다.

## 출력

- 결과 폴더(기본 `docs/perf/data/<YYYY-MM-DD_HHMM>`, `--quick` 이면 `scripts/perf/out/quick/<...>`):
  - `<label>-r<rep>.json` — 스위트 하나: 버전(ref, 해석한 SHA), jar(SHA-256, 출처), 설정(JVM 옵션, 고정 CPU, 횟수, 스위트 직전 2초의 PC 전체 CPU 사용률 `backgroundCpuPct` — 다른 프로그램의 부하), 픽스처 해시, 서버별 기동·종료 방식, 시나리오별 `{status, reason?, samples, values, jobs, jobGroups, notes, server}`. 원시 표본은 0.01 ms 단위. `server` 는 작업 묶음마다 `{n, jobs, missing, results, measured, excluded, passthrough, samples}`(`results` 는 모든 줄의 결과별 개수, `samples` 는 COMPLETED 실행 `measured` 개의 값, `excluded` 는 뺀 줄 수).
  - `run.json` — 환경(OS, CPU, 논리 CPU, RAM, java -version, Python, JVM 옵션, 고정 CPU `pinnedCpus` 와 그 물리 코어 수 `pinnedCores`, `serverCpus`, 전원), 작업 폴더 `workDir`, 버전과 SHA, 반복별 순서, 시작·끝, 도구 커밋(`git rev-parse HEAD`, scripts/perf 미커밋 변경 여부), 명령줄. 쓰기가 OneDrive 잠금 등으로 실패하면 측정을 멈추지 않고 끝에 다시 쓰며, 그래도 안 되면 작업 폴더의 실행 폴더(`runDir`)에 남긴다.
- 작업 폴더(`--work-dir`): `jars/<sha>.jar`(한 번 쓰면 바꾸지 않는 캐시, 이 도구가 만든 것은 `<sha>.jar.json` 기록이 붙음), `fixtures/`(내용 해시로 검증하는 픽스처), `runs/<stamp>-p<pid>/<label>-r<rep>/logs/`(서버·스텁 로그)와 `events.jsonl`. 업로드·DB 등 큰 저장소는 서버마다 끝나면 지운다.
- 보고서: `--out` 의 PDF 와 `--html-out`(기본 `scripts/perf/out/report.html`, gitignore). `scripts/perf/out/` 의 결과·PDF·HTML 은 커밋하지 않는다.

## 통계와 판정 규칙

- 백분위수는 선형 보간(numpy `linear`, Hyndman–Fan type 7). 요약은 `{n, min, p50, p90, p95, p99, max, mean, stdev}`(ms, 소수 셋째 자리, stdev 는 표본 표준편차).
- 버전 비교(`stats.compare`): 반복을 모두 합친 표본의 중앙값으로 Δ% = (후보 − 기준) ÷ 기준 × 100. 값 지표(기동 시간, RSS, 처리량)는 반복별 값을 표본으로 쓴다.
- 판정: **|Δ%| ≥ 10 이고, 반복별 중앙값의 [최소, 최대] 범위가 기준의 범위와 겹치지 않을 때만** `better`(개선) / `worse`(악화). 그 밖에는 `same`(차이 없음, 잡음). 시간은 작을수록, 처리량(`bench.uploadMBps`, `bench.jobsPerSec`, `bench.pollReqPerSec`)은 클수록 개선.
- 기준 중앙값이 0 이고 후보가 0 이 아니면 변화율이 없으므로 `n/a`(보고서 '비교 불가'). `same` 으로 두면 0 → 5 같은 변화가 잡음으로 보인다.
- 오류 수 `bench.errors` 는 측정값이 아니라 사건 수라 반복을 **합한** 값을 보이고(한 반복에서만 난 실패가 중앙값 0 에 묻히지 않게), 0 이 아니면 빨간 칸, 변화율·판정은 없다. 실패한 요청·흐름이 하나라도 있으면 그 시나리오는 `status: "error"`(표본은 성공분 그대로)라 칸에 '오류 포함', 표지에 `<라벨> r<반복> (시나리오)` 가 나온다.
- 반복이 1회면 범위가 한 점이라 10 % 만 넘으면 판정이 나온다. 보고서는 이때 "참고용" 경고를 띄운다. 보고용은 `--reps 3` 이상.
- 기준 버전은 versions 파일의 `baseline`.
- `--data` 를 여러 개 주면 같은 라벨의 스위트를 반복으로 합친다. 단 `--quick` 여부나 횟수(`settings.counts`)가 다른 스위트는 한 p50 으로 합치지 않고 보고서 생성을 거부한다(종료 코드 2, 파일 목록 출력). 라벨마다 조건이 다르면(예: v0 는 quick, v1 은 보고용) 만들되 표지 경고와 측정 방법의 '데이터별 조건'에 다른 횟수를 적는다.

## 이름 규칙

- `bench.*` 값과 클라이언트 표본 이름(`healthzMs`, `postMs`, `flowMs`, `completeMs`, `assetMs`, `pollMs`, `pendingPollMs`, `listFirstMs@N` 등)은 이 벤치마크 전용이며 `docs/METRICS.md` 에 등록하지 않는다. 앱의 `uploadMs`(METRICS §3)와 섞지 않는다.
- 서버 값 `uploadMs`·`queuedMs`·`processingMs`·`totalMs`(결과의 `server` 항목)와 real 의 `serverQueuedMs`·`serverProcessingMs`·`serverTotalMs` 는 events.jsonl 의 METRICS §1 정의 그대로다. real 시나리오는 스텁 패스스루라 `passthrough=true` 이고 모델 측정이 아니다.
- 단위: 시간 ms(서버 CPU 시간 `bench.serverCpuMs` 는 Windows 에서 스케줄러 틱 약 15.6 ms 단위), 메모리 MiB(2^20 바이트, Windows 는 작업 집합 WorkingSetSize/PeakWorkingSetSize, Linux 는 VmRSS/VmHWM), 처리량 MB/s 는 10^6 바이트/초(요청 본문 전체 ÷ p50).

## 측정 조건

- 서버: `java -Xms256m -Xmx1536m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -jar <jar> --server.port=<빈 포트> --spring.profiles.active=<프로파일>` + 모든 `storage.*` 경로를 실행마다 새 폴더의 절대 경로로(`db.path`, `upload.dir`, `events.path`, `result.dir`, `tmp.dir`, `thumb.dir`, `backup.dir`, `root.dir`), 작업 폴더는 그 실행 폴더. `--notify.webhook-url=`·`--inference.token=` 을 비워 넘기고, 측정에 영향을 줄 수 있는 환경변수(`JAVA_TOOL_OPTIONS`, `SPRING_*`, `INFERENCE_*`, `NOTIFY_*`, `STORAGE_*`, `BESIDE_*` 등)는 서버·스텁에 넘기지 않는다.
- CPU 고정: 서버만 마지막 2개 논리 CPU(Windows `SetProcessAffinityMask`, Linux `sched_setaffinity`; macOS 는 고정 불가). 부하 생성기와 추론 스텁은 고정하지 않는다. SMT CPU 에서는 마지막 두 논리 CPU 가 한 물리 코어일 수 있다 — `run.json` 의 `env.pinnedCores` 에 고정한 CPU 가 걸친 물리 코어 수를 남기고(Windows `GetLogicalProcessorInformation`, Linux sysfs), 보고서의 서버 조건·한계 문장은 이 값과 `serverCpus`·`pinnedCpus`·`jvmArgs`·`os`·`java`·`workDir` 로 만든다(이 PC: 논리 CPU 10, 11 = 물리 코어 1개).
- 우선순위(Windows): 서버·추론 스텁·부하 생성기(`run.py` 와 `poll_load` 의 자식 프로세스)를 `ABOVE_NORMAL_PRIORITY_CLASS` 로 띄운다. Windows 는 다른 프로그램을 서버가 고정된 CPU 에서 떼어 놓지 않아, 게임 클라이언트 같은 프로그램이 떠 있으면 서버가 몇 초씩 멈췄다. `run.json` 의 `env.priority` 에 남긴다. Linux·macOS 는 바꾸지 않는다(권한 필요).
- 갓 쓴 파일 첫 열기: 실행을 시작할 때 큰 사진 픽스처(4.5 MB)의 사본을 작업 폴더에 쓰자마자 열어 읽은 시간과 다시 읽은 시간(3회 중앙값)을 `env.freshFileOpen` 에 남긴다. 실시간 백신이 갓 쓴 파일을 처음 열 때 검사하면 첫 열기만 느리다(이 PC: 약 200 ms 대 1 ms, 알약). 업로드 직후 사진을 여는 서버 코드(요청 안의 썸네일, 업로드 전송)는 그 대기를 포함하므로 보고서 '한계'에 적는다. 백신 설정은 바꾸지 않는다.
- 포트는 OS 가 고른 빈 포트(8080 은 쓰지 않는다). 기동 시간은 Popen 직후부터 25 ms 간격 healthz(연결 제한 25 ms: Windows 는 닫힌 포트 연결 거부에 약 1초 걸려서).
- 종료: 먼저 정상 종료(Windows 는 자식 콘솔에만 Ctrl+C, 그 밖은 SIGTERM → Spring 이 H2 를 닫는다), 30초 안에 안 끝나면 프로세스 트리를 강제 종료(`taskkill /T /F`). 서버 B 는 PENDING 작업이 Spring 작업 실행기의 30초 종료 대기를 붙잡으므로 5초만 기다린다(저장소는 버린다). 이 도구가 띄운 프로세스만 건드리고, Windows 에서는 kill-on-close 작업 개체에 넣어 도구가 강제 종료돼도 남지 않게 한다.
- 클라이언트: `http.client`, 127.0.0.1, 스레드마다 keep-alive 연결. 시간은 요청을 쓰기 직전부터 응답 본문을 다 읽을 때까지(`perf_counter_ns`). 연결 수립은 시간에서 뺀다(Tomcat 이 100 요청마다 연결을 닫는 것 포함). 끊긴 연결은 한 번 다시 연결해 재시도하고 성공한 시도만 잰다. E2E 흐름(`flowMs`, `completeMs`)도 흐름 안에서 일어난 연결 수립·죽은 시도 시간(`HttpClient.untimed_ns`)을 빼고, 뺀 값은 흐름마다 `untimedMs`. POST 마다 새 `Idempotency-Key`.
- jar: `git rev-parse <ref>^{commit}` → 캐시에 없으면 `git worktree add --detach` 한 임시 폴더에서 `gradlew bootJar -x test --no-daemon` → 복사 → `git worktree remove --force`. 같은 작업 폴더를 여러 실행이 함께 써도 되도록 커밋별 잠금 파일과 실행별 폴더를 쓴다. `gradlew --stop` 은 같은 Gradle 버전의 **모든** 데몬(사용자 것 포함)을 멈추므로 쓰지 않고 데몬 없이 빌드한다.
- 픽스처는 시드 고정 생성기로 만들고 작업 폴더에 내용 해시와 함께 캐시한다: 64×64 노이즈 PNG, 1230×1230 노이즈 PNG(약 4.5 MB), 4000×3000 그라디언트 PNG(약 1.1 MB), 합성 GLB(1,500,000 바이트, glTF 2.0 헤더·JSON·BIN 청크).

## 새 버전 추가

1. `docs/perf/versions.json`(견본 `scripts/perf/versions.example.json`)의 `versions` 에 라벨을 더한다: `ref`(커밋·브랜치·태그), `title`, `description`, `capabilities`(`mock`, `real_path`, `real_upload`, `prod` 중 그 버전에 있는 것).
2. `order` 에 라벨을 넣는다. 보고서의 색은 이 순서의 자리로 정해진다(앞 8개까지 고정 팔레트, 그 뒤는 회색) — 기존 라벨의 자리를 바꾸지 않는다.
3. 비교 기준을 바꾸려면 `baseline` 을 고친다.
4. 기준과 별개로 두 버전을 맞대려면(개선 전 → 후, 이번 → 다음 버전) `pairs` 에 `{"from": "v2", "to": "v3", "title": "..."}` 를 더한다. 표지의 '쌍 비교' 표에 열 하나로 나오고, 판정 규칙은 같다(앞 버전이 기준). 측정하지 않은 버전이 든 쌍은 표에서 빠지고 그 사실이 표 위에 적힌다.
5. `run.py --versions ...,<새 라벨>` 로 측정하고 `report.py --data <새 결과 폴더> [--data <이전 결과 폴더>]` 로 보고서를 만든다. 브랜치 ref 는 측정 때 SHA 로 해석해 결과에 남는다. 커밋한 보고서를 다시 만들 수 있도록 `docs/perf/versions.json` 의 ref 는 잰 커밋의 SHA 로 고정한다(브랜치는 움직인다).

서술(개선 사례, 다음 버전 절차)은 `docs/perf/notes/*.md` 에 쓴다. 파일 이름 순으로 3장(시나리오별 결과) 뒤에 들어간다. 지원 문법: `#`~`###` 제목, 문단, `- `·`1. ` 목록, `**굵게**`, `` `코드` ``, 파이프 표(`|:--|--:|` 정렬), 링크는 글자만, ```` ``` ```` 코드 블록.

## 한계

- 로컬 루프백(127.0.0.1)에서 잰 값이다. 클라우드 VM, Tailscale, 실제 GPU 추론 경로가 아니다. real 시나리오는 모델 없는 스텁이라 Spring 쪽 오버헤드만 보인다.
- 부하 생성기가 Python(GIL, http.client)이라 동시 시나리오에는 클라이언트 자신의 지연이 섞일 수 있다(`poll_load` 는 이를 줄이려 4프로세스).
- 서버와 클라이언트가 한 PC 를 나눠 쓴다. 디스크·메모리 대역, 백신 실시간 검사, 다른 프로그램의 부하가 섞인다. 다른 프로그램의 부하는 스위트마다 `backgroundCpuPct` 로 남기고, 보고서는 그 범위를 '한계'에 적으며 최대가 10 % 이상이면 표지에 경고한다.
- 서버 쪽 시간(events.jsonl)은 ms 단위 정수(버림)라 1 ms 보다 작은 차이는 보이지 않는다. `completeMs` 는 폴링 간격만큼 늦게 관측된다(`bench.pollsPerJob` 참고).
- 앞 시나리오가 JIT 를 데우므로 시나리오 사이 비교보다 같은 시나리오의 버전 사이 비교에 쓴다. `scale` 의 첫 N 은 앞 시나리오가 만든 작업 수(보고용 약 390, `--quick` 68)보다 작을 수 있다. 보고서의 scale 행 이름은 실제로 잰 N 이다(`bench.actualN@N`, 예: `N=390 (목표 200)`).
- 시나리오 메모(`notes`)는 보고서에 `<라벨> r<반복>: 메모` 로 버전마다 4개까지 나온다(나머지는 결과 JSON).
