# scripts — 전체 흐름 확인 스크립트 (공통, 유지 담당: Backend 트랙)

실행 중인 Spring 서버에 대해 API v1 전체 흐름을 `curl`로 실행한다: `GET /api/v1/healthz` → `POST /api/v1/jobs`(사진 업로드) → `GET /api/v1/jobs/{id}` 1초 폴링(최대 `-TimeoutSeconds`, 기본 60초) → `GET /api/v1/jobs/{id}/asset?variant=base` 다운로드. 결과 파일과 헤더 덤프는 `scripts/out/`(gitignore)에 남고, 마지막 줄에 측정값 JSON(`uploadMs`, `waitMs`, `downloadMs`, `e2eMs`, `bytes`, `states`)을 출력한다.

| 파일 | 환경 | 비고 |
|---|---|---|
| `e2e_mock.ps1` | Windows PowerShell 5.1+ | `curl.exe`(Windows 10 이상 기본 포함) 사용. mock·real 서버 모두에 쓴다 |
| `e2e_mock.sh` | Git Bash / macOS / Linux | `curl`만 필요, `jq` 불필요 |
| `run_real.ps1` | Windows PowerShell 5.1+ (GPU PC) | 추론 서비스(uvicorn, 127.0.0.1:8001)와 Spring real 을 새 창 두 개로 띄우고 두 healthz 를 확인한다. `-SampleGlb` 를 주면 패스스루(연동 확인용, 측정 아님). 절차는 [backend/README.md](../backend/README.md) 'GPU PC 에 real 배치' |

```powershell
cd backend; .\gradlew.bat bootRun                  # 터미널 1
.\scripts\e2e_mock.ps1                             # 터미널 2: 1x1 PNG 두 장을 만들어 업로드
.\scripts\e2e_mock.ps1 -Photos C:\photos\dog1.jpg, C:\photos\dog2.jpg
.\scripts\e2e_mock.ps1 -BaseUrl http://192.168.0.10:8080   # 다른 기기에서 보는 PC 주소로 점검
.\scripts\e2e_mock.ps1 -Photos C:\photos\fail-dog.jpg      # 파일명에 fail → FAILED 흐름(종료 코드 1)
```

```bash
scripts/e2e_mock.sh
scripts/e2e_mock.sh http://localhost:8080 ~/photos/dog1.jpg ~/photos/dog2.jpg
```

GPU PC 에서 real 프로파일(실제 모델 E2E):

```powershell
.\scripts\run_real.ps1                                                     # 추론 서비스 + Spring real, 각각 새 창
.\scripts\run_real.ps1 -SampleGlb C:\beside\sample.glb                     # 모델 없이 연동만 확인
.\scripts\run_real.ps1 -InferencePython C:\miniconda3\envs\beside-infer\python.exe -Jar C:\beside\backend\build\libs\mock-backend-0.0.1-SNAPSHOT.jar
.\scripts\e2e_mock.ps1 -BaseUrl http://192.168.0.20:8080 -Photos C:\photos\dog1.jpg -TimeoutSeconds 600   # real 은 수 분, 실제 사진으로
```

종료 코드 0은 COMPLETED와 다운로드 성공, 1은 FAILED·타임아웃·HTTP 오류다. 다운로드한 GLB가 0바이트이면 경고를 출력한다(`backend/storage/results/sample-dog.glb` 교체 필요).
