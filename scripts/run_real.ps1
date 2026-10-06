<#
.SYNOPSIS
  GPU PC 한 대에서 Beside real 프로파일을 띄운다: Python 추론 서비스(uvicorn) + Spring real, 각각 새 PowerShell 창.
.DESCRIPTION
  둘 다 같은 PC 에서 돌므로(공유 파일시스템) 추론 서비스는 127.0.0.1 에만 묶고, Spring 만 8080 을 LAN 에 연다.
  두 healthz 가 응답하면 폰에서 쓸 IPv4 와 다음 명령을 출력한다. 종료는 각 창에서 Ctrl+C.
  준비: Java 17, 추론 서비스 venv(generation/inference_service/.venv, requirements.txt 설치), 방화벽 8080 허용.
  절차 전체는 backend/README.md 'GPU PC 에 real 배치'.
.EXAMPLE
  .\scripts\run_real.ps1                                   # 모델이 연결된 추론 서비스 + Spring real
.EXAMPLE
  .\scripts\run_real.ps1 -SampleGlb C:\beside\sample.glb   # 패스스루: 모델 없이 연동만 확인 (측정 아님)
.EXAMPLE
  .\scripts\run_real.ps1 -InferencePython C:\miniconda3\envs\beside-infer\python.exe -Jar C:\beside\backend\build\libs\mock-backend-0.0.1-SNAPSHOT.jar
#>
# NOTE: 이 파일은 UTF-8 **BOM 포함**이어야 한다. Windows PowerShell 5.1 은 BOM 이 없으면 ANSI 로 읽어 한글 주석이 파싱을 깨뜨린다.
[CmdletBinding()]
param(
    [string]$InferencePython = "",
    [string]$SampleGlb = "",
    [string]$Jar = "",
    [int]$Port = 8080,
    [int]$InferencePort = 8001,
    [int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$inferenceDir = Join-Path $root "generation\inference_service"
$backendDir = Join-Path $root "backend"

# 한글·공백 경로는 모델 쪽 이미지 로더(cv2 등)가 못 여는 경우가 있다. GPU PC 에서는 C:\beside 처럼 ASCII 경로에 둔다.
if ($root -match '[^\x20-\x7E]' -or $root -match ' ') {
    Write-Warning "리포 경로에 한글 또는 공백이 있다: $root  (GPU PC 에서는 ASCII·공백 없는 경로를 권장)"
}
if ($InferencePython -eq "") { $InferencePython = Join-Path $inferenceDir ".venv\Scripts\python.exe" }
if (-not (Test-Path -LiteralPath $InferencePython)) {
    throw "추론 서비스용 python 이 없다: $InferencePython`n  cd generation\inference_service; python -m venv .venv; .\.venv\Scripts\python -m pip install -r requirements.txt"
}
if ($SampleGlb -ne "" -and -not (Test-Path -LiteralPath $SampleGlb)) { throw "SampleGlb 가 없다: $SampleGlb" }
if ($Jar -ne "" -and -not (Test-Path -LiteralPath $Jar)) { throw "Jar 가 없다: $Jar" }

function Start-Window {
    param([string]$Title, [string]$Command)
    # -EncodedCommand: 경로의 공백·따옴표 때문에 인자가 깨지지 않는다.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("`$Host.UI.RawUI.WindowTitle = '$Title'; $Command"))
    Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded | Out-Null
}

function Wait-Healthz {
    param([string]$Url, [string]$Name)
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        try { return Invoke-RestMethod -Uri $Url -TimeoutSec 5 } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    throw "$Name 이(가) $StartupTimeoutSeconds 초 안에 응답하지 않았다: $Url (해당 창의 로그를 본다)"
}

# 1. 추론 서비스 — 같은 PC 의 Spring 만 부르므로 127.0.0.1
$inferenceCommand = "Set-Location -LiteralPath '$inferenceDir'; "
if ($SampleGlb -ne "") { $inferenceCommand += "`$env:BESIDE_SAMPLE_GLB = '$SampleGlb'; " }
$inferenceCommand += "& '$InferencePython' -m uvicorn app:app --host 127.0.0.1 --port $InferencePort"
Start-Window "Beside inference :$InferencePort" $inferenceCommand
$inferHealth = Wait-Healthz "http://127.0.0.1:$InferencePort/healthz" "추론 서비스"
Write-Host ("[1/2] inference  status={0} device={1} modelLoaded={2} passthrough={3}" -f $inferHealth.status, $inferHealth.device, $inferHealth.modelLoaded, $inferHealth.passthrough)
if ($inferHealth.passthrough) { Write-Warning "패스스루 모드: 어떤 사진을 올려도 샘플 GLB 가 온다. 연동 확인용이며 측정이 아니다(job_runs.passthrough=true)." }

# 2. Spring real — 작업 폴더는 backend/ (storage/ 상대 경로 기준)
$springArgs = "--spring.profiles.active=real --server.port=$Port --inference.base-url=http://127.0.0.1:$InferencePort"
if ($Jar -ne "") {
    $springCommand = "Set-Location -LiteralPath '$backendDir'; java -jar '$Jar' $springArgs"
} else {
    $springCommand = "Set-Location -LiteralPath '$backendDir'; .\gradlew.bat bootRun --args='$springArgs'"
}
Start-Window "Beside Spring real :$Port" $springCommand
$springHealth = Wait-Healthz "http://127.0.0.1:$Port/api/v1/healthz" "Spring"
Write-Host ("[2/2] spring     status={0} profile={1} workerType={2}" -f $springHealth.status, $springHealth.profile, $springHealth.workerType)
if ($springHealth.workerType -ne "real") { Write-Warning "workerType 이 real 이 아니다. 8080 에 다른 서버(mock)가 떠 있지 않은지 확인한다." }

# 3. 폰과 다른 PC 에서 쓸 주소
$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress
Write-Host ""
Write-Host "실행 중. 폰(같은 Wi-Fi)·다른 PC 에서는 아래 주소로 붙는다 (방화벽 8080 허용 필요):"
foreach ($ip in $addresses) { Write-Host ("  http://{0}:{1}/api/v1/healthz" -f $ip, $Port) }
Write-Host "다음:"
Write-Host ("  .\scripts\e2e_mock.ps1 -BaseUrl http://<IP>:{0} -Photos C:\photos\dog1.jpg -TimeoutSeconds 600   # real 은 수 분" -f $Port)
Write-Host ("  http://localhost:{0}/h2-console  (jdbc:h2:file:./storage/db/beside-real, sa, 빈 비밀번호) → job_runs 측정표 내보내기" -f $Port)
Write-Host "  종료: 두 창에서 Ctrl+C"
exit 0
