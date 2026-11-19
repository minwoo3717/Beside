# Runpod Pod 연결 기록 (2026-11-17)

GCP VM(Spring) ↔ Runpod Pod(추론 서비스)을 Pod 안 Tailscale(userspace)로 처음 이어 공개 주소에서 전체 흐름을 돌린 기록이다. 절차는 [deploy/README.md](../../deploy/README.md) 'Runpod Pod 쪽'. 시각은 UTC.

- **[구현됨]** VM·Pod 가 같은 tailnet 에 있고, 공개 주소 → VM → Pod(패스스루) → GLB 까지 사진 1건이 끝났다. 같은 날 Pod 에 AnimalLift 를 설치해 실제 모델로 3건이 끝났다(아래 '모델 모드'). Pod 의 추론 서비스는 127.0.0.1:8002 에만 열려 있고 Pod 에 공개 포트는 없다.
- **[실험 결과]** 아래 수치는 표본 1~4건이다. 동작 확인용이며 성능 측정이 아니다.
- **[계획]** Unity 앱 시험, Pod 를 지운 뒤 같은 100.x 유지 확인(새 Pod 로 같은 100.x 에 붙는 것은 2026-11-18 에 확인). VM ↔ Pod 직접 연결은 2026-11-18 에 됐다(아래 '2026-11-18 2차 부하 시험 때 바뀐 것').

## 구성

| 항목 | 값 |
|---|---|
| Runpod 계정 | 팀 계정(재료비 크레딧 약 $200, 2026-11-17 결제) |
| 네트워크 볼륨 | `beside-workspace`(`z4kgw4v5zi`), EU-SE-1, 30GB STANDARD, 월 $2.10. Pod 의 `/workspace`(fuse 네트워크 파일시스템) |
| Pod | `beside-gpu`(`pzcls7d82ffens`), Secure, EU-SE-1, A40 48GB(드라이버 595.112.07, 호스트 CUDA 13.2), 시간당 $0.59, 컨테이너 디스크 20GB, 공개 포트 없음 |
| 이미지 | `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`(공식 템플릿 `runpod-torch-v240` 의 이미지), API 로 `gpu.minCudaVersion: "12.4"` |
| 토큰 | Runpod Secret `beside_inference_token` → Pod env `BESIDE_INFERENCE_TOKEN={{ RUNPOD_SECRET_beside_inference_token }}` |
| Tailscale | tailnet 1개(팀 계정). VM `beside-vm` 100.98.76.5(1.104.7, apt 저장소 설치, `--accept-dns=false`), Pod `beside-gpu` 100.83.86.62(1.104.6, `start_pod.sh`, userspace) |
| 코드 | Pod: `deploy/gcp` 브랜치 clone, `deploy/runpod/start_pod.sh`(패스스루, 삼각형 샘플 GLB). VM: `INFERENCE_BASE_URL=http://100.83.86.62:8002`(이전 값은 `deploy/.env.bak-20261117-stub`, 스텁 컨테이너는 남겨 둠) |

## 수치 [실험 결과]

| 항목 | 값 | 조건 |
|---|---|---|
| `tailscale ping` VM → Pod | 301~308 ms, **DERP(hel) 중계**, 직접 연결 안 됨 | VM 에서 3회 |
| `/healthz` VM → Pod:8002 | 호스트 0.87 s, backend 컨테이너 0.54 s | 각 1회, 첫 요청 |
| backend 재생성 → healthy | 24 s | `up -d --no-deps backend`, 추론 상태 `UNKNOWN -> UP` 은 그 뒤 8 s |
| 전체 흐름(밖에서) | 업로드 909 ms, 대기 6278 ms(queued 3473 + processing 2253), 다운로드 138 ms, 합계 7325 ms | 개발 PC, `scripts/e2e_mock.sh`, `dog.jpg`(169,451 B), 결과 624 B(Pod 의 삼각형 샘플) |
| 비교: 11-13 VM 안 스텁 | 업로드 971 ms, 대기 2839 ms, 다운로드 224 ms | [gcp-first-deploy](2026-11-13_gcp-first-deploy.md) |
| `/workspace` 사용량 | venv 437 MB, Tailscale 83 MB, 리포 314 MB | `du -sh` |

대기가 스텁보다 약 3.4 s 길다. 처리 구간이 유럽 Pod 까지 DERP 중계를 왕복하기 때문으로 보이지만 표본 1건이라 원인을 나누지 않았다.

## 확인한 것

- Pod 의 nginx 가 실제로 0.0.0.0:8001(그리고 3001·7270·7861·8081·9091)을 쓴다. VM 에서 Pod:8001 로 가면 nginx 가 200 을 돌려준다 — 8002 로 옮긴 이유가 맞았다.
- Pod 로그에서 `/infer/upload`·`/artifacts` 요청이 모두 `127.0.0.1` 에서 들어왔다: userspace 모드의 tailscaled 가 tailnet 연결을 127.0.0.1 로 넘긴다는 가정이 실제 Pod 에서 맞았다. 업로드 200 → GLB 200 → 작업 폴더 삭제 204.
- `nvidia-smi -L` 에 A40 이 보이고, 이미지에 curl·git·flock·setsid·python3.11 venv 가 있고 tmux 는 없다. `RUNPOD_POD_ID` 가 들어 있다.
- 처음 만들 때 컨테이너 디스크 30GB 로는 "no longer any instances available"(400), 20GB 로 다시 하자 만들어졌다. 디스크 크기가 원인인지는 표본 1건이라 확실하지 않다.
- Secret 값에 `INFERENCE_TOKEN=` 이름까지 붙어 들어갔다(길이 80, 이름을 떼면 64 자리이고 해시 앞자리가 VM 값과 같음). 스크립트는 이 형태를 거부하므로 이번에는 `/workspace/beside-bootstrap.sh` 에서 이름을 떼고 띄웠다. Secret 을 값만으로 고치는 것은 사용자 몫 **[계획]**.
- VM 에 공식 설치 스크립트(`curl … | sh`)를 쓰려던 것은 이 세션의 자동 권한 검사가 막아서 사용자가 apt 저장소 명령으로 직접 설치했다.

## 모델 모드 (같은 날 09:26~09:38 UTC)

- `deploy/runpod/install_animallift.sh` 를 Pod 에서 백그라운드로 돌려 497 s 에 끝났다: Miniforge 26.9.4-2 104 s, env(Python 3.10.20) 67 s, AnimalLift `5d364cf` 와 pip(torch 2.4.0+cu124) 약 300 s, 체크포인트 약 20 s. 디스크 conda 12 GB, AnimalLift 2.3 GB. torch 가 A40 을 `cuda 12.4` 로 인식.
- `start_pod.sh` 를 다시 실행하자 "the inference service (pid 266) runs in passthrough mode, MODE is model - kill 266" 으로 멈췄다(리뷰에서 넣은 거부가 실제로 동작). 패스스루를 끄고 다시 띄우자 `model loaded in 31.3s on cuda`.
- 공개 주소로 실제 사진 3장이 모두 COMPLETED, GLB 1,310,876~1,327,400 B, 삼각형 7,774. 모델 수치(inferMs 3.9~5.7 s, gpuPeakMB 5,120)와 작업 시간은 [exp-01](../../generation/experiments/2026-11-17_exp-01_baseline-runpod.md).
- VM 의 `INFERENCE_BASE_URL` 은 그대로(Pod 의 100.x:8002). 스텁에서 바꾼 뒤 backend 는 다시 띄우지 않았다(같은 주소의 서비스만 바뀜).

## Pod 끄기·다시 켜기와 디스코드 알림 (2026-11-17 17:35~17:40 UTC, 한국 11-18 02:35~02:40)

- 사용자 요청으로 API 로 Pod 를 **멈췄다**(`stop` → EXITED). 켜져 있던 시간 32,068 s(약 8시간 54분), 시간당 $0.59 로 약 $5.3(추정, Runpod 청구 내역은 늦게 반영된다). 이 Pod 는 볼륨 디스크 없이 네트워크 볼륨만 쓰므로 멈춘 동안 Pod 요금은 없고 볼륨 월 $2.10 만 나간다. conda·AnimalLift·체크포인트·Tailscale 상태는 모두 `/workspace` 에 있어 그대로다.
- 지우지(terminate) 않고 멈춘 이유: 콘솔의 Start 한 번으로 같은 Pod·같은 100.x 로 돌아온다. 멈춘 Pod 는 원래 기계로만 돌아가므로 그 GPU 를 남이 쓰고 있으면 켜지지 않는다. 그때는 지우고 위 '구성'의 값(이미지, A40, EU-SE-1, 볼륨 `z4kgw4v5zi` 를 `/workspace` 에, env 토큰, `gpu.minCudaVersion 12.4`)으로 새로 만든다.
- VM 은 Pod 주소를 그대로 둔다. 17:35:17 `UP -> DOWN`. Pod 가 꺼진 동안 들어온 작업은 30분 PENDING 으로 기다리다 `INFERENCE_UNAVAILABLE` 로 끝난다.
- 디스코드: 웹훅 주소가 개발 PC 의 로컬 설정에만 있고 VM `.env` 의 `NOTIFY_WEBHOOK_URL` 은 비어 있었다(그래서 11-17 의 연결·끊김은 알림이 없었다). VM `.env` 에 넣고(백업 `deploy/.env.bak-20261118-notify`) backend 를 다시 만들자(healthy 까지 약 30 s) `UNKNOWN -> DOWN` 끊김 메시지 1건이 웹훅 오류 로그 없이 나갔다 **[구현됨]**. 메시지의 서버 이름(`NOTIFY_INFERENCE_LABEL=Runpod A40`, VM `.env` 에 넣어 둠)과 45초 유예는 PR #5 병합 뒤 2026-11-18 VM 리포를 main `b6c0e6c` 로 바꿔 배포하면서 들어갔다(서버 이름이 든 메시지를 확인한 기록은 아직 없다).

다시 켜는 순서:

1. Runpod 콘솔 → Pods → `beside-gpu` → Start(또는 API `pod-action start`).
2. RUNNING 이 되면 Pod 의 웹 터미널(Connect → Web Terminal)에서 아래 두 줄. 첫 줄은 볼륨의 리포를 main 에 맞춘다(`start_pod.sh` 는 당기지 않는다. 2026-11-19 병합한 PR #11 은 이렇게 맞춰야 Pod 에 들어간다). 볼륨 리포가 detached HEAD 여도 `main` 브랜치로 돌아오도록 `pull` 대신 `fetch` + `checkout -B` 를 쓴다(4차 때 detached 였다. 이유는 [deploy/README.md](../../deploy/README.md) Runpod 절 '다시 쓸 때마다'). Pod 의 시작 명령이 비어 있어 서비스가 저절로 뜨지 않는다. `start_pod.sh` 가 `/workspace/AnimalLift` 를 보고 모델 모드로 띄우고, 모델 로드는 약 31 s 다.
   ```bash
   git -C /workspace/Beside fetch -q origin && git -C /workspace/Beside checkout -q -B main origin/main
   nohup bash /workspace/beside-bootstrap.sh > /workspace/beside-logs/start.log 2>&1 &
   ```
3. VM 이 15초마다 `/healthz` 를 읽어 `DOWN -> LOADING -> UP` 이 되고 디스코드에 🟡·🟢 메시지가 간다. 기다리던 작업은 UP 이 되면 이어서 처리된다.

## 2026-11-18 2차 부하 시험 때 바뀐 것

결과 문서는 [docs/perf/load/2026-11-18_vm-2nd.md](../perf/load/2026-11-18_vm-2nd.md) 다.

- **새 Pod**:
  - `beside-gpu`(`pzcls7d82ffens`)를 09:41·09:44 UTC 에 켜 보았지만 두 번 모두 "There are not enough free GPUs on the host machine to start this pod" 였다. 위 '다시 켜는 순서'의 대안대로 같은 이미지·A40·EU-SE-1·볼륨 `z4kgw4v5zi`·Secret 으로 새 Pod `beside-gpu2`(`yq8zyoibhf15l5`)를 만들었다.
  - 볼륨의 Tailscale 상태로 **같은 `100.83.86.62` 로 붙었고**, VM `.env` 는 바꾸지 않았다. `start_pod.sh` 는 "this Tailscale state was last used by Pod pzcls7d82ffens" 라고 알렸다.
  - 새 Pod 의 모델 로드는 73.2 s 였다 [실험 결과](`gpu/pod-info.txt`). 새 호스트에서 볼륨 파일 읽기가 캐시되지 않은 탓으로 본다 [가설].
- **경로**: 방화벽을 바꾸지 않았는데 이날은 VM ↔ Pod 가 **직접 연결**이었다(`tailscale ping` 268~269 ms, 6번 확인) [실험 결과](각 실행의 `vm/derp-rtt.txt`). 왕복이 DERP(약 300 ms)와 비슷해 전송 시간은 크게 줄지 않은 것으로 본다 [가설].
- **두 Pod 모두 정지**(15:29 UTC, EXITED 확인). 다음에 켤 때:
  1. 둘 중 하나를 Start 한다. 같은 노드 키라 **둘을 동시에 켜지 않는다**.
  2. 켜지지 않으면 다른 하나를 켠다.
  3. 둘 다 안 되면 같은 값으로 새 Pod 를 만든다.
- 쓰지 않는 Pod 하나는 지워도 된다(볼륨은 남는다). 어느 쪽을 남길지는 팀이 정한다 [계획].
- **3차 뒤**: 3차 부하 시험(2026-11-18 16:29 UTC)에서 `4vh9x6broleb2z`(beside-gpu3)를 만들었다. 2026-11-19 07:00:25 UTC 에 `o5rbm1cuv3ae7x`(beside-gpu3-migration)가 만들어졌고, VM backend 로그에는 운영 작업 1건이 있다(07:00:48 생성, 07:11:28 UP, 07:11:45 COMPLETED, 07:13:22 DOWN). 모든 Pod 가 같은 100.x 로 붙어 backend 로그로는 Pod 를 가리지 못하므로, 시각으로 보아 이 Pod 가 처리한 것으로 본다 [가설]. 2026-11-19 상태 읽기로 네 Pod 모두 EXITED, 같은 볼륨이다. 다음에 켤 때는 하나만 Start 한다(같은 노드 키). 쓰지 않는 Pod 는 지워도 된다 [계획].
- **4차**(2026-11-19 17:18 UTC): 앞의 A40 Pod 3개가 호스트에 빈 GPU 가 없어 켜지지 않았고, 같은 값으로 새 A40 Pod 를 만드는 것도 여러 번 실패했다. GPU 당 최소 vCPU 4·RAM 24 GB 의 배치 조건(`minVcpuCountPerGpu`·`minRamPerGpu`)을 주자 `nsz73mtl802mvw`(beside-gpu4)가 만들어졌다(vCPU 9·RAM 50 GB). 같은 볼륨이라 같은 `100.83.86.62` 로 붙었다. 18:57 에 정지했고 EXITED 를 확인했다. 결과는 [docs/perf/load/2026-11-20_vm-4th.md](../perf/load/2026-11-20_vm-4th.md).
- 2차 시험 동안 13:09:57 UTC 에 16초 동안 `UP -> DOWN (HttpConnectTimeoutException)` 이 한 번 있었다(부하 없던 때) [실험 결과](`vm/backend.log`). 11-17 의 15초 끊김과 같은 모양이다.

## 남은 것 [계획]

- VM ↔ Pod 직접 연결: 2026-11-18 에는 방화벽을 바꾸지 않고도 직접 연결이었다(위). 날마다 다를 수 있으므로 GCP 방화벽 UDP 41641 은 DERP 로 돌아갈 때 다시 본다.
- Unity 앱으로 `https://beside-team.duckdns.org` 시험(지금 결과는 삼각형이라 모양 확인용은 아님).
- 쓰지 않을 때 Pod 를 지우고(볼륨은 남음) 다시 만들어 같은 100.x 로 돌아오는지 확인 — 새 Pod 로 같은 100.x 에 붙는 것은 2026-11-18 에 확인했다(위). 지운 Pod 의 노드를 Tailscale 관리 화면에서 지울 필요가 있는지는 아직 보지 않았다.
