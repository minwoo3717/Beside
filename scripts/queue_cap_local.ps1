<#
.SYNOPSIS
  큐 상한(안건 #21) 로컬 동작 확인: mock 서버를 상한을 켜고 띄운 뒤 거절·retry·회복을 curl.exe 로 확인한다.
.DESCRIPTION
  서버를 먼저 띄운다(예, backend 에서):
    java -jar build\libs\<jar> --server.port=18080 --spring.profiles.active=mock `
      --admission.max-pending=3 --admission.retry-after-seconds=30 `
      --mock.worker.pending-ms=20000 --mock.worker.processing-ms=1000 `
      --storage.db.path=<임시>\db\beside --storage.upload.dir=<임시>\uploads --storage.events.path=<임시>\events.jsonl
  확인하는 것:
    1. 상한(3)까지는 202, 그다음 생성은 503 QUEUE_FULL + Retry-After(초).
    2. 상한이 찬 동안 FAILED 작업의 retry 는 503 이고 작업은 FAILED·attempt 1 그대로.
    3. 다른 v1 오류(404)에는 Retry-After 가 없다. v0 POST /api/jobs 는 상한을 받지 않는다.
    4. 앞 작업이 PENDING 을 벗어나면 다시 202, retry 도 202(attempt 2).
  동작 확인용이다. 용량 결과가 아니다. 종료 코드: 0 = 모두 통과, 1 = 하나라도 실패.
#>
# NOTE: UTF-8 BOM 포함으로 저장한다(Windows PowerShell 5.1).
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$Cap = 3,
    [int]$RetryAfter = 30,
    [int]$PendingSeconds = 20,
    [string]$Photo = (Join-Path $PSScriptRoot "..\backend\src\test\resources\photos\dog.jpg"),
    [string]$OutDir = (Join-Path $env:TEMP "beside-queue-cap-local")
)

$ErrorActionPreference = "Stop"
$curl = (Get-Command curl.exe).Source
$BaseUrl = $BaseUrl.TrimEnd("/")
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Photo = (Resolve-Path $Photo).Path
$failPhoto = Join-Path $OutDir "fail-dog.jpg"
Copy-Item $Photo $failPhoto -Force

$results = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$ok, [string]$detail) {
    $results.Add([pscustomobject]@{ check = $name; ok = $ok; detail = $detail })
    $mark = if ($ok) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1} - {2}" -f $mark, $name, $detail)
}

# One HTTP call: returns status, headers (lowercase names), body text.
function Invoke-Api([string]$method, [string]$path, [string[]]$extra = @()) {
    $hdr = Join-Path $OutDir "h.txt"; $body = Join-Path $OutDir "b.txt"
    Remove-Item $hdr, $body -ErrorAction SilentlyContinue
    $curlArgs = @("-s", "-X", $method, "-D", $hdr, "-o", $body, "-w", "%{http_code}") + $extra + @("$BaseUrl$path")
    $code = & $curl @curlArgs
    $headers = @{}
    if (Test-Path $hdr) {
        foreach ($line in Get-Content $hdr) {
            if ($line -match '^([A-Za-z0-9-]+):\s*(.*)$') { $headers[$matches[1].ToLower()] = $matches[2].Trim() }
        }
    }
    $text = if (Test-Path $body) { [IO.File]::ReadAllText($body) } else { "" }
    return [pscustomobject]@{ status = [int]$code; headers = $headers; body = $text }
}

function New-Job([string]$file, [string]$key) {
    return Invoke-Api "POST" "/api/v1/jobs" @("-H", "Idempotency-Key: $key", "-F", "photos=@$file;type=image/jpeg")
}
function Get-Job([string]$id) { return (Invoke-Api "GET" "/api/v1/jobs/$id").body | ConvertFrom-Json }
function Wait-Status([string]$id, [scriptblock]$until, [int]$timeoutS) {
    $deadline = (Get-Date).AddSeconds($timeoutS)
    while ((Get-Date) -lt $deadline) {
        $j = Get-Job $id
        if (& $until $j) { return $j }
        Start-Sleep -Milliseconds 500
    }
    return Get-Job $id
}
$run = [Guid]::NewGuid().ToString("N").Substring(0, 8)

# 0. healthz
$h = Invoke-Api "GET" "/api/v1/healthz"
Check "healthz" ($h.status -eq 200) "status $($h.status)"

# 1. A FAILED job to retry later (fail token in the filename).
$f = New-Job $failPhoto "qc-$run-f"
$fId = ($f.body | ConvertFrom-Json).jobId
Check "fail job created" ($f.status -eq 202) "status $($f.status) jobId $fId"
$fJob = Wait-Status $fId { param($j) $j.status -eq "FAILED" } ($PendingSeconds + 30)
Check "fail job ends FAILED" ($fJob.status -eq "FAILED") "status $($fJob.status) error $($fJob.error.code)"

# 2. Fill the queue to the cap.
$ids = @()
for ($i = 1; $i -le $Cap; $i++) {
    $r = New-Job $Photo "qc-$run-a$i"
    $ids += ($r.body | ConvertFrom-Json).jobId
    Check "create $i of cap $Cap" ($r.status -eq 202) "status $($r.status)"
}

# 3. One more create is refused.
$x = New-Job $Photo "qc-$run-over"
$xErr = try { ($x.body | ConvertFrom-Json).error } catch { $null }
Check "create over cap -> 503" ($x.status -eq 503) "status $($x.status)"
Check "error.code QUEUE_FULL" ($xErr -and $xErr.code -eq "QUEUE_FULL") "code $($xErr.code)"
Check "Retry-After = $RetryAfter" ($x.headers["retry-after"] -eq "$RetryAfter") "Retry-After '$($x.headers["retry-after"])'"
Check "refused create has no jobId" (-not $xErr.jobId) "jobId '$($xErr.jobId)'"
$again = New-Job $Photo "qc-$run-over"
Check "same key again is still refused (key not stored)" ($again.status -eq 503) "status $($again.status)"

# 4. A retry while full is refused and leaves the job FAILED, attempt 1.
$rt = Invoke-Api "POST" "/api/v1/jobs/$fId/retry"
$rtErr = try { ($rt.body | ConvertFrom-Json).error } catch { $null }
Check "retry while full -> 503 QUEUE_FULL" ($rt.status -eq 503 -and $rtErr.code -eq "QUEUE_FULL") "status $($rt.status) code $($rtErr.code) jobId '$($rtErr.jobId)'"
Check "retry 503 has Retry-After" ($rt.headers["retry-after"] -eq "$RetryAfter") "Retry-After '$($rt.headers["retry-after"])'"
$fAfter = Get-Job $fId
# The v1 job response has no attempt field: an unchanged FAILED row keeps its status, error code and updatedAt.
$same = ($fAfter.status -eq "FAILED") -and ($fAfter.updatedAt -eq $fJob.updatedAt) -and ($fAfter.error.code -eq $fJob.error.code)
Check "refused retry leaves the FAILED row unchanged" $same "status $($fAfter.status) error $($fAfter.error.code) updatedAt $($fAfter.updatedAt) (before $($fJob.updatedAt))"

# 5. Other v1 errors carry no Retry-After; v0 is not capped.
$nf = Invoke-Api "GET" "/api/v1/jobs/00000000-0000-0000-0000-000000000000"
Check "404 has no Retry-After" ($nf.status -eq 404 -and -not $nf.headers.ContainsKey("retry-after")) "status $($nf.status)"
$v0 = Invoke-Api "POST" "/api/jobs" @("-F", "photos=@$Photo;type=image/jpeg")
Check "v0 POST /api/jobs not capped" ($v0.status -ge 200 -and $v0.status -lt 300) "status $($v0.status)"

# 6. When the first jobs leave PENDING the queue admits again.
$first = Wait-Status $ids[0] { param($j) $j.status -ne "PENDING" } ($PendingSeconds + 30)
Check "first job left PENDING" ($first.status -ne "PENDING") "status $($first.status)"
$all = $ids | ForEach-Object { Wait-Status $_ { param($j) $j.status -ne "PENDING" } 30 }
$y = New-Job $Photo "qc-$run-after"
Check "create after the queue drains -> 202" ($y.status -eq 202) "status $($y.status)"
$rt2 = Invoke-Api "POST" "/api/v1/jobs/$fId/retry"
$f2 = Get-Job $fId
Check "retry after drain -> 202, job leaves FAILED" ($rt2.status -eq 202 -and $f2.status -ne "FAILED" -and -not $f2.error) "status $($rt2.status) job $($f2.status)"

$failed = @($results | Where-Object { -not $_.ok }).Count
Write-Host ("`n{0} checks, {1} failed" -f $results.Count, $failed)
$results | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir "result.json")
if ($failed -gt 0) { exit 1 } else { exit 0 }
