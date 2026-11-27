# 관측 보강 wave3 운영 적용 — 배포 창 3·Pod 확인(E4) (2026-11-27)

PR #42(main `e222ec1`)를 운영 VM 과 GPU Pod 에 적용하고 확인한 기록이다. 계획 조각 E3·E4. 시각은 모두 UTC.

## 배포 창 3 (14:59~15:02) **[구현됨: 적용]**

- 배포 전(읽기 전용): VM `2569998`, 대기·처리 중 작업 0건, 메모리 여유 5.7 GB. `gcloud compute firewall-rules list` 의 0.0.0.0/0 규칙은 tcp 22·80·443, udp 443, tcp 3389, icmp 뿐이라 3500·4320 은 열려 있지 않다.
- 순서
  1. 14:59:44 대기열 0 확인, 되돌리기 이미지 `beside-backend:rollback-2569998`.
  2. `git pull --ff-only`(→ `e222ec1`), 14:59:58 `$M up -d --no-deps alloy`(포트·명령이 바뀌어 다시 만들어짐), 14:59:59 `$M restart prometheus`.
  3. 15:00:12 개발 PC 에서 `scripts/deploy_vm.sh beside-gcp`(backend 만 다시 만듦), 15:02:06 `$M restart grafana`.
- 배포하며 찾은 것 두 가지(스크립트 고침은 [계획])
  - `ssh … 'bash -s' < 스크립트` 안의 `docker compose exec -T` 가 표준 입력을 먹어 뒤 줄이 실행되지 않았다. `</dev/null` 을 붙이거나 스크립트를 복사해 실행한다.
  - `scripts/deploy_vm.sh` 는 VM 안이 아니라 개발 PC 에서 `deploy_vm.sh <ssh 대상>` 으로 부른다. README '배포 창 3 순서' 2번을 그대로 VM 에서 이어 치면 여기서 멈춘다.

### 확인

| 항목 | 결과 |
|---|---|
| `scripts/check_public_routes.sh` | 63 통과, 0 실패. healthz `real,prod` |
| 개발 PC → 공인 IP 3500·4320 | 둘 다 5 s 시간 초과(curl exit 28) |
| VM 안 `127.0.0.1:3500/ready` | `ready` |
| Prometheus `up` | beside-backend·prometheus·loki·tempo·alloy 모두 1 |
| 새 버킷 | `http_server_requests_seconds_bucket{le="0.005"}` 있음 |
| Grafana 알림 | 공급 파일 18개, 로그 `finished to provision alerting` |
| backend 로그 env | Loki 라벨 `env` 값 `prod` |
| k6 표지 | `X-Request-Id: lt-w3check-begin` 의 healthz 가 Caddy 로그에 남음 |
| 한 ID | 앱처럼 `X-Request-Id`·`traceparent`(같은 32 hex)를 붙인 요청의 backend JSON 줄에서 `requestId` = `traceId` |
| 메모리 | backend 1.21 GiB / 3 GiB, alloy 82 MiB / 384 MiB |

Grafana 로그의 `xychart` 플러그인 등록 오류 두 줄은 알림 공급과 관계없다. 그 전에도 있었는지는 보지 않았다.

## Pod 확인 E4 (15:23~15:27) **[실험 결과]**

- Pod `8p1zx5e83e92nq`(beside-gpu-obs): A40 SECURE, EU-SE-1, $0.59/h, 볼륨 `z4kgw4v5zi`, 리허설과 같은 이미지·시작 명령에 env `BESIDE_TELEMETRY_VM=100.98.76.5`·`BESIDE_TELEMETRY_ENV=prod` 를 더함. 만들 때 다른 Pod 8대는 모두 EXITED.
- 15:23:46 생성 → 15:23:48 `e222ec1` 받음 → 15:25:36 VM backend 가 `LOADING -> UP`(약 110 s).
- Pod 안: 127.0.0.1:1055 에 tailscaled, 로그 보내기 실행, `curl -x http://127.0.0.1:1055 http://100.98.76.5:3500/ready` → `ready`.
- Loki: `{service="inference"}` 에 라벨 `env=prod`·`host=beside-gpu`·`file=inference.log`·`level` 로 uvicorn·timing·모델 로드 줄이 들어옴.

### 작업 1건의 한 trace

공개 주소로 앱처럼 보냈다: 업로드·GLB 내려받기에 `traceparent: 00-<id>-<새 span>-01`, 모든 요청에 같은 `X-Request-Id: <id>`(32 hex). 사진 `backend/src/test/resources/photos/dog.jpg`, 작업 `aed78abb-8d56-42bf-9c82-15063b59a632`, 15:26:01 업로드 → 15:26:26 COMPLETED, GLB 2,100,924 B.

Tempo `/api/traces/<id>` 로 읽은 trace(id = 앱의 `X-Request-Id` `8c9289ded30b4968b08c80a63cb01147`)의 span 21개:

| 시작 | 시간 | 서비스 | span |
|---|---|---|---|
| 0.00 s | 775.5 ms | backend | http post /api/v1/jobs |
| 0.04 s | 554.4 ms | backend | beside.upload.check |
| 0.60 s | 146.0 ms | backend | beside.upload.save |
| 0.75 s | 23.4 ms | backend | beside.job.dispatch-wait |
| 0.77 s | 21142.9 ms | backend | beside.job.run |
| 0.77 s | 3130.2 ms | backend | beside.job.cutout |
| 3.90 s | 4.3 ms | backend | beside.job.slot-wait |
| 3.91 s | 280.0 ms | backend | beside.job.service-wait |
| 4.21 s | 15116.8 ms | backend | POST /infer/upload |
| 4.66 s | 14534.1 ms | inference | POST /infer/upload |
| 6.03 s | 25.3 ms | inference | beside.inference.save-photos |
| 10.26 s | 0.1 ms | inference | beside.inference.lock-wait |
| 10.26 s | 7740.5 ms | inference | beside.inference.predict |
| 18.00 s | 1032.3 ms | inference | beside.inference.convert |
| 19.04 s | 117.6 ms | inference | beside.inference.rig |
| 19.33 s | 2232.9 ms | backend | GET /artifacts/{jobId}/base.glb |
| 19.47 s | 66.9 ms | inference | GET /artifacts/{jobId}/{name} |
| 21.56 s | 5.0 ms | backend | beside.job.store |
| 21.60 s | 311.4 ms | backend | DELETE /artifacts/{jobId} |
| 21.75 s | 37.0 ms | inference | DELETE /artifacts/{jobId} |
| 24.48 s | 22.9 ms | backend | http get /api/v1/jobs/{jobId}/asset |

- 같은 id 로 Loki `{service=~"caddy|backend|inference"} |= "<id>"`: Caddy 10줄, backend 5줄, inference 7줄.
- 조회(폴링)는 `X-Request-Id` 만 보내 trace 에 들어가지 않는다(계획대로).
- **trace 로 처음 보인 것 [가설]**: GPU 서버에서 사진 저장(6.03 s) 뒤 모델 락(10.26 s)까지 약 4.2 s 가 어느 span 에도 없다. 모델 입력 준비로 보이나 확인하지 않았다. `POST /infer/upload` 서버 span 시작(4.66 s)과 사진 저장(6.03 s) 사이 1.4 s 는 본문(사진) 받기다.
- Grafana 화면으로 본 것은 아니다(API 로 읽음). 캡처는 [사람].

## 기기 확인 E2 (15:44~15:51, 팀원 SM-S947N) **[실험 결과]**

- 앱: main `e222ec1` 로 만든 APK, 설정 화면에서 서버를 `https://beside-team.duckdns.org` 로 저장(로그 `[JobManager] server -> https://beside-team.duckdns.org`). 그 전 첫 전송 2번은 옛 주소라 `Cannot resolve destination host` 로 실패했고, 저장 뒤 `sent 3: accepted 3, dropped 0` 로 남은 것까지 보냈다(오프라인 보관·다시 보내기가 기기에서 동작).
- **앱에서 받은 이벤트 0건 → 43건**(관리 API `GET /api/v1/admin/events`, 15:40~16:10, 모두 `platform=android`, dropped 0):

| 이벤트 | 건수 | 확인한 값 |
|---|---|---|
| screen_view | 24 | home·settings·confirm·progress·preview·ar·library·camera·error (`library`·`camera` 도 저장됨) |
| photos_selected | 3 | gallery 2, camera 1 |
| generate_tap | 2 | mode new |
| model_ready | 1 | cached false, loadMs 153, waitMs 12609 |
| fur_toggle | 2 | off → on |
| model_placed | 4 | job 첫 배치 again false, 다시 배치 again true, 불러온 강아지 source library 2번 |
| animation_play | 1 | TailWag |
| saved_dog_change | 1 | saved, count 1 |
| saved_dog_open | 2 | ok true, from ar·home, loadMs 101·148 |
| job_failed_shown | 1 | NO_ANIMAL_DETECTED, retryable false |
| start_over | 2 | from preview·error |

- 퍼널(`GET /api/v1/admin/funnel`, 같은 구간): 세션 2. 본 퍼널 home 2 → photos_selected 1(이탈 1) → generate_tap 1 → model_ready 1 → ar 1 → model_placed 1. 불러오기 퍼널 home 2 → library 2 → saved_dog_open 2 → ar 2 → model_placed 2. 작업 완료율 created 1·completed 1(100 %). `lastScreens`·`dropReasons` 는 비었다(두 번째 세션의 마지막 이벤트가 조회 시각 30분 안이라 이탈 분석에서 빠짐 — 정의대로).
- 기기 작업 `5d4ec42f…` 의 trace: backend 로그의 `requestId` = `traceId` = 앱 흐름 id `42338276f2204ccba748afc57c59713e`. Tempo 에 span 21개(backend·inference), 작업 실행 12.1 s(크롭 2.0 s, `POST /infer/upload` 6.7 s 중 predict 3.95 s·convert 1.02 s·rig 0.12 s, GLB 받기 2.7 s).
- 오류 화면 문의 번호 `930d812c`: Loki 에서 `930d812c` 로 backend 줄 2개(`photo check refused NO_ANIMAL_DETECTED`, `POST /api/v1/jobs NO_ANIMAL_DETECTED (HTTP 400)`, requestId `930d812c86d04e438d969de06fd30199`)와 Caddy 400 줄 1개가 바로 나왔다. 앱 화면의 8자로 서버 원인까지 간다.
- E4 의 'GPU 서버 안 빈 4.2 s' 는 이 작업에는 없었다(사진 저장 → 모델 락이 바로 이어짐). 그래서 Pod 가 뜬 뒤 첫 작업에만 생기는 준비 시간으로 본다 [가설].
- `app_error` 는 이 시험에서 0건(앱 오류가 나지 않음).

## 털 설정 `BESIDE_HAIR_META` 켬 (15:57) **[적용: 이 Pod]**

- 팀원 지적: 기기에서 본 서버 모델에 코·눈처럼 털이 없어야 할 곳에 털이 났다. 고침 코드는 이미 있다 — 변환기의 가이드 털 맵(가이드가 닿는 곳에만 털, 코·발바닥은 비움, `generation/tools/convert_asset.py` `_from_guides`·`_hairless_mask`)이 `BESIDE_HAIR_META` 가 있을 때만 켜지는데(기본 꺼짐), 지금까지 Pod 에 이 변수를 준 적이 없었다.
- Pod 에 파일(`/workspace/AnimalLift/assets/hair_map_meta_uvanchor.npz`·`base_texture.png`)과 scipy 1.15.3 이 있음을 확인하고 Pod env 에 `BESIDE_HAIR_META=/workspace/AnimalLift/assets/hair_map_meta_uvanchor.npz` 를 더했다(`update-pod`, 컨테이너 재시작, 15:57:58 `LOADING -> UP`, 서비스 프로세스 환경에 값 있음).
- 확인 작업 `73214f52…`: 변환 경고(`base_texture.png not used`) 없음, convert 1,377 ms·GLB 1,781,656 B(켜기 전 작업은 1,032 ms·2,100,924 B). 털 모양이 실제로 고쳐졌는지는 기기에서 다시 볼 것 [사람].
- 시연 Pod 에도 같은 변수를 넣도록 RUNBOOK '발표 당일 GPU 절차' 의 env 에 더했다.

## 남은 것

- 기기에서 털 모양 다시 확인(`BESIDE_HAIR_META` 켠 뒤) [사람].
- Pod 를 지운다(이 기록 뒤, 기기 확인 끝나면).
- `scripts/check_public_routes.sh` 에 3500·4320 바깥 시간 초과 항목, README '배포 창 3 순서' 의 `deploy_vm.sh` 부르는 곳 고침 [계획].
