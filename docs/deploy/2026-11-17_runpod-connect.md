# Runpod Pod 연결 기록 (2026-11-17)

GCP VM(Spring) ↔ Runpod Pod(추론 서비스)을 Pod 안 Tailscale(userspace)로 처음 이어 공개 주소에서 전체 흐름을 돌린 기록이다. 절차는 [deploy/README.md](../../deploy/README.md) 'Runpod Pod 쪽'. 시각은 UTC.

- **[구현됨]** VM·Pod 가 같은 tailnet 에 있고, 공개 주소 → VM → Pod(패스스루) → GLB 까지 사진 1건이 끝났다. 같은 날 Pod 에 AnimalLift 를 설치해 실제 모델로 3건이 끝났다(아래 '모델 모드'). Pod 의 추론 서비스는 127.0.0.1:8002 에만 열려 있고 Pod 에 공개 포트는 없다.
- **[실험 결과]** 아래 수치는 표본 1~4건이다. 동작 확인용이며 성능 측정이 아니다.
- **[계획]** VM ↔ Pod 직접 연결(지금은 DERP 중계), Unity 앱 시험, Pod 를 지우고 다시 만들었을 때 같은 100.x 유지 확인.

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

## 남은 것 [계획]

- VM ↔ Pod 직접 연결: GCP 방화벽에 UDP 41641 을 열면 DERP 를 거치지 않을 수 있다(Tailscale GCE 안내). 큰 사진 업로드 시간을 재 보고 정한다.
- Unity 앱으로 `https://beside-team.duckdns.org` 시험(지금 결과는 삼각형이라 모양 확인용은 아님).
- 쓰지 않을 때 Pod 를 지우고(볼륨은 남음) 다시 만들어 같은 100.x 로 돌아오는지 확인.
