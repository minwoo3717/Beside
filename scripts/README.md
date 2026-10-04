# scripts — 전체 흐름 확인 스크립트 (공통, 유지 담당: Backend 트랙)

실행 중인 Spring 서버에 대해 API v1 전체 흐름을 `curl`로 실행한다: `GET /api/v1/healthz` → `POST /api/v1/jobs`(사진 업로드) → `GET /api/v1/jobs/{id}` 1초 폴링(최대 60초) → `GET /api/v1/jobs/{id}/asset?variant=base` 다운로드. 결과 파일과 헤더 덤프는 `scripts/out/`(gitignore)에 남고, 마지막 줄에 측정값 JSON(`uploadMs`, `waitMs`, `downloadMs`, `e2eMs`, `bytes`, `states`)을 출력한다.

| 파일 | 환경 | 비고 |
|---|---|---|
| `e2e_mock.ps1` | Windows PowerShell 5.1+ | `curl.exe`(Windows 10 이상 기본 포함) 사용 |
| `e2e_mock.sh` | Git Bash / macOS / Linux | `curl`만 필요, `jq` 불필요 |

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

종료 코드 0은 COMPLETED와 다운로드 성공, 1은 FAILED·타임아웃·HTTP 오류다. 다운로드한 GLB가 0바이트이면 경고를 출력한다(`backend/storage/results/sample-dog.glb` 교체 필요).
