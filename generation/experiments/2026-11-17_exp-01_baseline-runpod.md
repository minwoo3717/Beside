# exp-01 — 베이스라인 AnimalLift 1건 생성 시간·GPU 메모리 (Runpod A40)

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-17 |
| 담당 | Backend(배포·측정). 3D 트랙 검토 필요 |
| 코드 커밋 | Beside `deploy/gcp`(볼륨의 리포는 2026-11-18 확인 때 `ee030f2`. 모델 경로는 그때 main(`b6c0e6c`)과 같다(2026-11-19 PR #11 로 main 의 모델 경로가 바뀌기 전). main 의 패스스루 부하 설정 `BESIDE_PASSTHROUGH_*` 는 없다 — [vm-3rd 7절](../../docs/perf/load/2026-11-19_vm-3rd.md)), AnimalLift `5d364cf`(github.com/Chuny1/AnimalLift), 체크포인트 `Chunyi99/AnimalLift` `latest.pt`(2,317,900,684 B) |
| 환경 | Runpod Pod, Secure, EU-SE-1, NVIDIA A40 48GB(드라이버 595.112.07, 호스트 CUDA 13.2), 이미지 `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`, conda Python 3.10.20, torch 2.4.0+cu124, 배치 1, 사진 1장/작업, 리깅 끔, `/workspace` 는 네트워크 볼륨(fuse) |
| 입력 데이터 | `backend/src/test/resources/photos/` 의 `dog.jpg`(169,451 B), `dog-single-black.jpg`(205,402 B), `dog-single-edge.jpg`(193,464 B) — 출처는 같은 폴더 `SOURCES.md`, 각 1마리. VM 을 거친 3건은 Spring 이 만든 크롭+배경 제거 PNG 가 첫 사진으로 들어갔다 |
| 상태 | **[실험 결과]** 시간·메모리·출력 크기(표본 4건). 2026-11-18 2차 부하 시험에서 `dog.jpg` 반복 539건을 더했다(6절). 품질 점수는 **[계획]**(평가자 2명 이상) |

## 1. 실패 사례 (무엇이 안 되는가)

베이스라인 측정이다. 개선 실험이 아니라서 실패 사례·가설·개선(1~3절)은 없고, 아래 4절의 baseline 행이 이후 실험(exp-03 입력 전처리 등)의 비교 기준이 된다. 이번에 알게 된 것 중 실패에 가까운 것:

- VM(서울) → Pod(스웨덴) 경로가 Tailscale DERP 중계(헬싱키, 왕복 약 300 ms)라, 작업 처리 시간의 절반 이상이 모델이 아니라 전송이다(4절 아래 표).

## 2. 원인 가설

- 전송 시간: 직접 연결이 안 되고 중계를 거친다(VM 에서 `tailscale ping` → "via DERP(hel)", "direct connection not established"). GCP 방화벽이 Tailscale 의 UDP 를 받지 않는 것이 한 원인으로 보인다(확인 안 함).

## 3. 개선 (무엇을 바꿨는가)

없음(베이스라인). 통제 변수: 같은 Pod·같은 모델 로드(한 번 띄운 서비스에서 연속 실행), `BESIDE_NFE`·`BESIDE_CFG_SCALE` 은 체크포인트 값 그대로.

## 4. 비교 실험

모델 쪽 수치(추론 서비스 로그와 `/infer/upload` 응답의 `metrics`, METRICS 이름):

| 실험 | inferMs | gpuPeakMB | modelParams | outputVertices | outputTriangles | convertMs | faceShape | ears | bodyProportion | furColor | pattern | textureArtifacts | GLB bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline `dog.jpg` (VM 경유, 첫 추론) | 5728 | — | — | — | 7774 | 867 | | | | | | | 1,326,748 |
| baseline `dog-single-black.jpg` (VM 경유) | 4081 | — | — | — | 7774 | 1133 | | | | | | | 1,327,400 |
| baseline `dog-single-edge.jpg` (VM 경유) | 3982 | — | — | — | 7774 | 862 | | | | | | | 1,310,876 |
| baseline `dog.jpg` (Pod 안에서 직접, 원본 JPG) | 3850 | 5120 | 153,833,642 | 4310 | 7774 | 846 | | | | | | | — |

- "—": VM 경유 3건의 gpuPeakMB·modelParams·outputVertices 는 Spring `job_runs` 에 있고 이 기록에는 옮기지 않았다. 품질 6항목은 비어 있다(평가자 채점 **[계획]**).
- 모델 로드: `model loaded in 31.3s on cuda`(첫 실행이라 SD VAE·DINOv2 를 받음, 418 MB 가 `/workspace/.cache/huggingface` 에 남음). 대기 중 GPU 메모리 1203~1281 MiB.

작업 전체(공개 주소, 개발 PC 의 `scripts/e2e_mock.sh`, Spring 의 `timings`):

| 사진 | 업로드 ms | 대기 ms | 다운로드 ms | Spring queuedMs | Spring processingMs |
|---|---|---|---|---|---|
| `dog.jpg` | 709 | — | — | 2279 | 13390 |
| `dog-single-black.jpg` | 582 | 12409 | 186 | — | — |
| `dog-single-edge.jpg` | 583 | 13372 | 157 | 2157 | 10309 |

- `dog.jpg` 는 클라이언트 쪽 출력이 업로드 줄에서 끊겼지만 작업은 COMPLETED(1,326,748 B)였다. `dog-single-black.jpg` 의 Spring timings 는 읽지 않았다.
- processingMs(10.3~13.4 s)가 모델(추론+변환 4.8~6.6 s)보다 5~7 s 길다: 사진 업로드와 GLB(약 1.3 MB) 다운로드가 DERP 중계를 지나는 시간으로 보인다(분리 측정 안 함).

GLB_SPEC 검사(gltf-validator·삼각형 상한·텍스처·파일 크기): **[계획]**. 삼각형 7,774 개는 GLB_SPEC 의 20,000 이하.

## 5. 결론 · 다음 실험

- Spring 의 600 s 타임아웃(`inference.timeout-ms`)에 비해 한 건이 훨씬 짧다: 모델 4~6 s, 전체 12~16 s.
- GPU 최대 메모리 5,120 MB(표본 1건)라 16 GB GPU 로도 돌 가능성이 크다. 더 싼 GPU 로 바꾸려면 같은 사진들로 다시 재 본다.
- 다음: (1) GCP 방화벽 UDP 41641 로 VM ↔ Pod 직접 연결을 만들어 processingMs 가 얼마나 줄어드는지, (2) 사진 10장 이상으로 반복(METRICS §1.1 `job_runs`), (3) 품질 6항목 채점, (4) exp-03 입력 전처리 비교에 이 baseline 을 쓴다. (1)·(2) 의 일부는 6절.

## 6. 추가 표본 — 2026-11-18 2차 부하 시험 [실험 결과]

배포 VM 의 부하 시험 중에 같은 모델로 539건을 만들었다. 기록은 [docs/perf/load/2026-11-18_vm-2nd.md](../../docs/perf/load/2026-11-18_vm-2nd.md), 원시 값은 그 문서 12절의 실행 폴더 `vm/events.jsonl` 이다.

**조건**: AnimalLift `5d364cf` 는 같다. 환경도 같다(A40, 드라이버 595.112.07, 같은 이미지, 같은 네트워크 볼륨). 달라진 것은 넷이다.
- Pod: 기존 Pod 가 GPU 를 못 받아 새 Pod `yq8zyoibhf15l5` 를 만들었다. 모델 로드 73.2 s.
- 경로: VM ↔ Pod Tailscale **직접 연결**(왕복 268 ms).
- 입력: `dog.jpg` 를 VM 이 크롭·배경 제거한 PNG, 작업당 1장.
- 실행: 부하 시험의 G0(대기열 없음, 30초 간격) 31건과 G1·G3(대기열 있음) 508건이다. 539건 모두 COMPLETED 였다.

| 표본 | n | inferMs 평균 (중앙값, p95, 최소~최대) | convertMs 평균 (중앙값, p95, 최소~최대) | gpuPeakMB | modelParams | outputVertices | outputTriangles | GLB bytes |
|---|---|---|---|---|---|---|---|---|
| G0 (대기열 없음) | 31 | 4,071 (4,008, 4,538, 3,699~4,670) | 905 (884, 1,112, 704~1,150) | 5,120 | 153,833,642 | 4,310 | 7,774 | 1,326,748 |
| 2차 전체 | 539 | 3,989 (3,761, 4,326, 3,377~15,411) | 900 (816, 987, 678~5,979) | 5,120~5,128 | 153,833,642 | 4,310 | 7,774 | 1,326,748 |

- 같은 사진이라 출력(정점·삼각형·GLB 크기)이 539건 모두 같다. 품질 6항목은 채점하지 않았다.
- 539건 중 14건(한 실행의 두 묶음)은 추론이 8.0~15.4 s 이거나 변환이 4.3~6.0 s 였다. 원인은 가리지 못했다(Pod CPU 경합 또는 볼륨 지연 [가설], 부하 문서 6절).
- **GPU 메모리**: nvidia-smi 의 프로세스 전체 사용은 최대 9,463 MiB 였다(대기 중 1,281~1,291 MiB). `gpuPeakMB`(torch 할당 최대) 5,120 MB 보다 크다. 16 GB GPU 를 볼 때는 이 값을 기준으로 한다.
- **전송을 나눠 쟀다**(4절 "분리 측정 안 함"을 일부 채운다). G0 의 `processingMs` 는 평균 10,403 ms(중앙값 9,894, p95 14,360)이고, 그중 추론+변환 밖 시간("전송 등": 업로드·내려받기와 Pod·VM 쪽 파일 처리)이 평균 5,427 ms 다.
  - VM·Pod 로그로 나누면 업로드(크롭 PNG)의 상한은 31건 1.94~2.81 s(중앙값 2.50 s)였다 [계산]. Pod 로그 시각이 0~1 s 늦게 찍혀 실제 업로드는 최대 약 1 s 짧을 수 있고, 그만큼 Pod 응답 뒤 GLB 내려받기·저장이 길다(3건: 2.15~4.07 s 이상).
  - 직접 연결이 되었지만 전송 등은 exp-01 2건(5.47·6.80 s)보다 크게 줄지 않았다. 표본이 2건이라 가리지 못하고, 왕복이 DERP 와 비슷하기 때문으로 본다(268 ms 대 약 300 ms) [가설].
  - 연결을 이어 쓴 작업은 전송 등이 새 연결 작업보다 같은 실행 안에서 평균 0.5~1.3 s 짧았다 [계산]. 과부하 재실행에서는 평균 3.5 s 였다. 세부 기제(연결 맺기·슬로 스타트)는 [가설]이다(부하 문서 7절).
- 다음: 사진 10장 이상은 여전히 [계획]이다(이번 표본은 한 사진의 반복). 전송을 줄이려면 VM 과 가까운 GPU 나 왕복 수를 줄이는 전송이 필요하다 [가설].
