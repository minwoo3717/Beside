<#
.SYNOPSIS
  Beside Mock 서버의 API v1 전체 흐름(healthz -> 사진 업로드 -> 폴링 -> GLB 다운로드)을 curl.exe 로 실행한다.
.DESCRIPTION
  서버가 실행 중이어야 한다: cd backend; .\gradlew.bat bootRun
  사진 경로를 주지 않으면 1x1 PNG 두 장을 만들어 올린다. 결과 파일은 scripts/out/ 에 남는다(.gitignore).
  종료 코드: 0 = COMPLETED + 다운로드 성공, 1 = FAILED / 타임아웃 / HTTP 오류.
.EXAMPLE
  .\scripts\e2e_mock.ps1
.EXAMPLE
  .\scripts\e2e_mock.ps1 -BaseUrl http://192.168.0.10:8080 -Photos C:\photos\dog1.jpg, C:\photos\dog2.jpg
.EXAMPLE
  .\scripts\e2e_mock.ps1 -Photos C:\photos\fail-dog.jpg   # 파일명에 fail -> Mock 워커가 FAILED 를 돌려준다
#>
# NOTE: 이 파일은 UTF-8 **BOM 포함**이어야 한다. Windows PowerShell 5.1 은 BOM 이 없으면 ANSI 로 읽어 한글 주석이 파싱을 깨뜨린다.
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$Photos = @(),
    [int]$TimeoutSeconds = 60,
    [string]$OutDir = (Join-Path $PSScriptRoot "out")
)

$ErrorActionPreference = "Stop"
$curl = (Get-Command curl.exe).Source
$BaseUrl = $BaseUrl.TrimEnd("/")
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Curl {
    param([string[]]$CurlArgs)
    $output = & $curl @CurlArgs
    if ($LASTEXITCODE -ne 0) { throw "curl failed (exit $LASTEXITCODE): $($CurlArgs -join ' ')" }
    return ($output -join "`n")
}

# 0. 테스트 사진 준비
if ($Photos.Count -eq 0) {
    $png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    $Photos = @(1, 2) | ForEach-Object {
        $path = Join-Path $OutDir "probe-$_.png"
        [IO.File]::WriteAllBytes($path, $png)
        $path
    }
}
foreach ($photo in $Photos) {
    if (-not (Test-Path -LiteralPath $photo)) { throw "photo not found: $photo" }
}

# 1. healthz
$health = Invoke-Curl @("-sS", "$BaseUrl/api/v1/healthz") | ConvertFrom-Json
Write-Host ("[1/4] healthz  status={0} profile={1} workerType={2}" -f $health.status, $health.profile, $health.workerType)

# 2. 업로드 (202 + Location + { jobId })
$uploadHeaders = Join-Path $OutDir "upload-headers.txt"
$form = @()
foreach ($photo in $Photos) { $form += @("-F", "photos=@$photo") }
$idempotencyKey = "e2e-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff")
$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$body = Invoke-Curl (@("-sS", "-D", $uploadHeaders, "-H", "Idempotency-Key: $idempotencyKey") + $form + @("$BaseUrl/api/v1/jobs"))
$uploadMs = $stopwatch.ElapsedMilliseconds
$statusLine = Get-Content $uploadHeaders | Select-Object -First 1
if ($statusLine -notmatch " 202 ") { Write-Host "upload failed: $statusLine"; Write-Host $body; exit 1 }
$location = (Get-Content $uploadHeaders | Where-Object { $_ -match "^Location:" } | Select-Object -First 1) -replace "^Location:\s*", ""
$jobId = ($body | ConvertFrom-Json).jobId
Write-Host ("[2/4] upload   202 jobId={0} location={1} uploadMs={2}" -f $jobId, $location.Trim(), $uploadMs)

# 3. 폴링 (1초 간격, 최대 TimeoutSeconds)
$states = New-Object System.Collections.Generic.List[string]
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$stopwatch.Restart()
do {
    $job = Invoke-Curl @("-sS", "$BaseUrl/api/v1/jobs/$jobId") | ConvertFrom-Json
    if ($states.Count -eq 0 -or $states[$states.Count - 1] -ne $job.status) { $states.Add([string]$job.status) }
    if ($job.status -eq "COMPLETED" -or $job.status -eq "FAILED") { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
$waitMs = $stopwatch.ElapsedMilliseconds
Write-Host ("[3/4] poll     states={0} waitMs={1} timings={2}" -f ($states -join ">"), $waitMs, ($job.timings | ConvertTo-Json -Compress))
if ($job.status -eq "FAILED") {
    Write-Host ("        FAILED error={0}" -f ($job.error | ConvertTo-Json -Compress))
    exit 1
}
if ($job.status -ne "COMPLETED") {
    Write-Host "        timeout after $TimeoutSeconds s (last status: $($job.status))"
    exit 1
}

# 4. 다운로드 (asset.url 은 서버 루트 기준 상대 경로)
$glbPath = Join-Path $OutDir "$jobId-base.glb"
$assetHeaders = Join-Path $OutDir "asset-headers.txt"
$stopwatch.Restart()
Invoke-Curl @("-sS", "-f", "-D", $assetHeaders, "-o", $glbPath, "$BaseUrl$($job.asset.url)") | Out-Null
$downloadMs = $stopwatch.ElapsedMilliseconds
$bytes = (Get-Item -LiteralPath $glbPath).Length
Write-Host ("[4/4] download 200 bytes={0} downloadMs={1} file={2}" -f $bytes, $downloadMs, $glbPath)
if ($bytes -eq 0) {
    Write-Warning "GLB 가 0바이트다. backend/storage/results/sample-dog.glb 를 유효한 GLB 로 교체해야 한다 (docs/asset/GLB_SPEC.md)."
}

[pscustomobject]@{
    jobId      = $jobId
    uploadMs   = $uploadMs
    waitMs     = $waitMs
    downloadMs = $downloadMs
    e2eMs      = $uploadMs + $waitMs + $downloadMs
    bytes      = $bytes
    states     = ($states -join ">")
    workerType = $health.workerType
} | ConvertTo-Json -Compress
exit 0
