# AnimalLift 를 추론 서비스에 연결하기 (GPU PC)

Unity 사진 → Spring real → **`/infer` (AnimalLift → convert_asset.py)** → Spring 저장 → Unity 다운로드·배치.
Spring·Unity 는 코드 변경 없이 그대로 동작한다. 이 문서는 GPU PC 에서 추론 서비스를 실제 모델로 띄우는 절차다.

## 구성

| 파일 | 역할 |
|---|---|
| `inference_service/animallift_runner.py` | AnimalLift 를 한 번만 불러 두고 사진 1장 → `mesh.obj`, `uv.png`, `hair.npz` (원본 `test.py` 와 같은 코드 경로) |
| `inference_service/app.py` | `BESIDE_ANIMALLIFT_DIR` 가 있으면 시작 시 백그라운드로 모델 로드, `/infer` 에서 추론 → 변환 → `glbPath`·metrics·modelVersion 응답 |
| `tools/convert_asset.py` | GLB_SPEC 변환: 머리 +X → **+Z**, 최장 축 1 m, 발바닥 y = 0, 노드 `base`, 텍스처 임베드, `base.metrics.json`. 털 지도 PNG 도 같이 만든다(API 미포함) |
| `scripts/run_real.ps1 -AnimalLiftDir` | 위 환경 변수를 넣어 두 서버를 띄운다 |

출력 위치: `generation/inference_service/work/<jobId>/` (`raw/` = AnimalLift 원본, `base.glb`, `base.metrics.json`, `hair_*.png`). Spring 이 `base.glb` 를 `backend/storage/results/<jobId>/base.glb` 로 복사한다. `work/` 는 커밋하지 않는다(.gitignore 에 추가).

## 1. AnimalLift 설치 (한 번)

```powershell
cd C:\beside                                   # 공백·한글 없는 경로
git clone https://github.com/Chuny1/AnimalLift.git
cd AnimalLift
conda create -n animallift python=3.10.20 pip -y
conda activate animallift
python -m pip install -r requirements.txt       # PyTorch 2.4.0 + CUDA 12.4
hf download Chunyi99/AnimalLift --local-dir ckpt # → ckpt/latest.pt (다른 이름이면 BESIDE_ANIMALLIFT_CKPT)
python test.py --max_images 1 --batch_size 1    # 원본이 먼저 돌아야 한다. outputs/test/ 에 결과
```

같은 env 에 서비스 의존성을 더한다.

```powershell
python -m pip install -r C:\beside\Beside\generation\inference_service\requirements.txt
python -m pip install -r C:\beside\Beside\generation\tools\requirements.txt
```

참고: AnimalLift 는 **Linux + NVIDIA** 에서 검증됐다. Windows 에서도 PyTorch cu124 휠이 있어 대부분 그대로 돌지만, 문제가 생기면 WSL2(Ubuntu)에서 같은 절차로 띄우고 Spring 은 Windows 에 두되 `imagePaths`/`glbPath` 가 양쪽에서 읽히는 경로여야 한다(같은 PC 의 `/mnt/c/...`). 첫 실행은 SD VAE·DINOv2 가중치를 내려받으므로 인터넷이 필요하다.

## 2. 실행

```powershell
cd C:\beside\Beside
.\scripts\run_real.ps1 -AnimalLiftDir C:\beside\AnimalLift -InferencePython C:\miniconda3\envs\animallift\python.exe
```

- 추론 창에 `AnimalLift loaded in …s on cuda` 가 뜨면 준비 완료. 그 전 요청은 503 → 작업 `INFERENCE_UNAVAILABLE`(앱에서 다시 시도).
- `http://127.0.0.1:8001/healthz` 의 `modelLoaded: true` 로도 확인한다. 로드 실패 원인은 추론 창 로그에 있다.
- 단독 실행: `$env:BESIDE_ANIMALLIFT_DIR='C:\beside\AnimalLift'; python -m uvicorn app:app --host 127.0.0.1 --port 8001` (animallift env 의 python).

선택 환경 변수: `BESIDE_ANIMALLIFT_CKPT`, `BESIDE_DEVICE`(cuda), `BESIDE_NFE`, `BESIDE_CFG_SCALE`(기본은 체크포인트 값), `BESIDE_WORK_DIR`, `BESIDE_VAE_LOCAL_PATH`.

## 3. 확인 순서

1. 단독: `curl -X POST http://127.0.0.1:8001/infer -H "Content-Type: application/json" -d "{\"jobId\":\"t1\",\"imagePaths\":[\"C:/beside/AnimalLift/input_images/animal_3.png\"]}"` → `glbPath`, `inferMs`, `gpuPeakMB`.
2. 서버 흐름: `.\scripts\e2e_mock.ps1 -BaseUrl http://localhost:8080 -Photos C:\photos\dog1.jpg -TimeoutSeconds 600`.
3. 폰: 앱 "서버 설정" 에 `http://<GPU PC IP>:8080` (같은 Wi-Fi) 또는 터널 주소 → 사진 선택 → 생성 화면에 경과 시간 → 미리보기 → 배치.
4. 측정: `job_runs` 에 `passthrough=false`, `modelVersion=animallift-baseline@<commit>:latest`, `inferMs`, `gpuPeakMB` 가 남는지(METRICS §1.1).

## 알아 둘 것

- 사진은 **첫 장만** 쓴다(AnimalLift 는 단일 이미지). 배경이 단순하고 동물 전신이 보이는 사진이 결과가 좋다. 배경 제거·크롭 전처리는 PLAN 3단계 개선 후보.
- 같은 사진은 같은 결과(시드 고정).
- 결과 GLB 는 정적 메시다(애니메이션 없음). 앱은 동작 버튼을 자동으로 숨긴다. 리깅(SMAL 뼈대·클립)을 붙이려면 리깅 스크립트를 `convert` 다음 단계로 넣는다 — GLB_SPEC rigged 확정 후.
- 털: `hair_length.png`/`hair_flow.png`/`hair_maps.json` 이 GLB 옆에 생기지만 API 로 내려주지는 않는다(계약 미정).
- 라이선스: AnimalLift 리포에 라이선스 파일이 없다. 학습·연구 목적 사용은 저자 안내를 따르고, 결과물·가중치를 공개 배포하기 전에 확인한다.

## 모델 교체 (개선 모델로 바꿀 때)

Spring·Unity 는 모델을 모른다. 둘이 아는 것은 `/infer` 계약(README)과 GLB_SPEC 뿐이라, 아래 범위의 교체는 **추론 서비스 설정만** 바꾸면 된다.

| 바꾸는 것 | 할 일 | 코드 수정 |
|---|---|---|
| 가중치만 (같은 구조로 재학습·파인튜닝) | `BESIDE_ANIMALLIFT_CKPT=<새 .pt>`, `BESIDE_MODEL_NAME=animallift-v2` | 없음 |
| AnimalLift 코드를 고친 포크 (함수 이름·출력 형식 유지) | `BESIDE_MODEL_DIR=<포크 경로>`, `BESIDE_MODEL_NAME=…` | 없음 |
| 추론 함수 이름·인자가 바뀐 포크 | `animallift_runner.py` 의 `predict()` 만 맞춘다 | runner 한 파일 |
| 전혀 다른 모델 | runner 인터페이스(`animallift_runner.py` 상단 RUNNER_INTERFACE)를 구현한 클래스 → `BESIDE_RUNNER=my_runner:MyRunner` | 새 runner 한 파일 |
| 출력 좌표계가 다른 모델 (머리가 +X 가 아님) | `BESIDE_FORWARD_AXIS=+z` 등 | 없음 |

- `run_real.ps1` 은 `-AnimalLiftDir` 만 받는다. 위 변수는 추론 창을 띄우기 전에 `$env:BESIDE_…='…'` 로 넣거나 단독 실행으로 띄운다.
- 베이스라인과 개선안은 `job_runs.model_version`(= `BESIDE_MODEL_NAME@커밋:가중치`)으로 구분된다. 같은 사진 세트를 두 모델로 돌려 METRICS §1.1 쿼리에 `WHERE model_version LIKE 'animallift-v2%'` 를 붙여 비교한다.
- 교체 후에도 지켜야 하는 것: 출력 OBJ 에 UV, 텍스처 PNG, 정점 수 ≤ 20,000 삼각형(GLB_SPEC §3). 변환기가 위반하면 `CONVERSION_FAILED`.
- SMAL 템플릿을 유지해야 그대로 되는 것: **털 지도**(hair.npz 형식·UV 배치), **리깅**(SMAL 뼈대·웨이트 복사). 토폴로지가 바뀌면 이 둘은 다시 만들어야 한다. 기본 모델(base.glb) 생성·배치는 토폴로지와 무관하게 동작한다.

## Ubuntu 에서 쓰기

AnimalLift 는 원래 Linux 에서 검증됐으므로 Ubuntu 가 가장 안정적인 선택이다. `/infer` 계약상 **Spring 과 추론 서비스는 같은 파일을 읽어야** 하므로(사진 경로, `glbPath`), 구성은 둘 중 하나다.

### A. Ubuntu PC 한 대에 전부 (권장)

Spring 도 Ubuntu 에서 돌린다. 폰은 이 PC 의 IP(또는 터널)로 붙는다.

```bash
sudo apt install -y openjdk-17-jdk           # Java 17 (21 이면 build.gradle.kts 의 Lombok 1.18.34+ 필요)
conda activate animallift
pip install -r ~/Beside/generation/inference_service/requirements.txt -r ~/Beside/generation/tools/requirements.txt
cd ~/Beside && chmod +x backend/gradlew scripts/run_real.sh
./scripts/run_real.sh ~/AnimalLift "$(which python)"
tail -f logs/inference.log                   # 'model loaded … on cuda' 확인
```

- 방화벽: `sudo ufw allow 8080/tcp`. 다른 네트워크에서 쓰려면 같은 PC 에서 `cloudflared tunnel --url http://localhost:8080`.
- 종료: `./scripts/run_real.sh stop`.

### B. Windows PC 의 WSL2 Ubuntu 에 AnimalLift, Spring 은 Windows

같은 PC 라 파일은 공유되지만 경로 표기가 다르다(`C:\…` ↔ `/mnt/c/…`). 추론 서비스가 경로를 바꿔 준다.

```bash
# WSL Ubuntu
conda activate animallift
cd /mnt/c/beside/Beside/generation/inference_service
export BESIDE_ANIMALLIFT_DIR=~/AnimalLift
export BESIDE_PATH_MAP='C:\=/mnt/c/'          # Spring 이 보낸 C:\… 사진 경로 → /mnt/c/…, glbPath 는 다시 C:\…
export BESIDE_WORK_DIR=/mnt/c/beside/work     # Windows 쪽 Spring 이 읽을 수 있는 위치에 결과를 쓴다
python -m uvicorn app:app --host 127.0.0.1 --port 8001
```

```powershell
# Windows — Spring real 만 (추론 서비스는 이미 WSL 에서 실행 중)
cd C:\beside\Beside\backend
.\gradlew.bat bootRun --args="--spring.profiles.active=real --inference.base-url=http://127.0.0.1:8001"
```

WSL2 는 기본으로 `localhost` 를 Windows 와 공유하므로 Spring 이 `127.0.0.1:8001` 로 닿는다. 안 되면 WSL 에서 `hostname -I` 로 IP 를 확인해 `inference.base-url` 에 넣고 uvicorn 을 `--host 0.0.0.0` 으로 띄운다.

### Ubuntu 와 Spring PC 가 서로 다른 컴퓨터라면

지금 계약(경로 전달)으로는 안 된다. Spring 을 Ubuntu 로 옮기거나(A), 공유 폴더(NFS/SMB)를 양쪽에 같은 경로로 마운트한 뒤 `BESIDE_PATH_MAP` 으로 맞춘다. 사진·GLB 를 HTTP 로 주고받는 방식은 `/infer` 계약 변경이라 Backend 와 합의가 필요하다.
