<#
Downloads the preprocessing models (photo check and cutout) into a folder and checks each one against
backend\models\SHA256SUMS. Idempotent: a file that is already there with the right hash is left alone; a download
with the wrong hash is not installed and the script fails. Sources and licences: backend\models\README.md.

  .\scripts\fetch_models.ps1                          # into $env:PREPROCESS_MODELSDIR, else backend\models
  .\scripts\fetch_models.ps1 -Target C:\beside\models # keep it outside OneDrive (about 210 MB), ASCII path

Then point the server at the folder: $env:PREPROCESS_MODELSDIR = 'C:\beside\models'
#>
param([string]$Target)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$sums = Join-Path $repo 'backend\models\SHA256SUMS'
if (-not $Target) {
    $Target = if ($env:PREPROCESS_MODELSDIR) { $env:PREPROCESS_MODELSDIR } else { Join-Path $repo 'backend\models' }
}

$want = @{}
foreach ($line in Get-Content $sums) {
    $parts = $line.Trim() -split '\s+'
    if ($parts.Count -ge 2) { $want[$parts[1].TrimStart('*')] = $parts[0].ToLower() }
}
New-Item -ItemType Directory -Force -Path $Target | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-Model([string]$Name, [string]$Url) {
    if (-not $want.ContainsKey($Name)) { throw "no SHA-256 for $Name in $sums" }
    $file = Join-Path $Target $Name
    if ((Test-Path $file) -and ((Get-FileHash $file -Algorithm SHA256).Hash.ToLower() -eq $want[$Name])) {
        Write-Host "ok    $Name"
        return
    }
    $part = "$file.part"
    $ProgressPreference = 'SilentlyContinue' # Windows PowerShell 5.1 downloads very slowly with the progress bar
    Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing
    $got = (Get-FileHash $part -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $want[$Name]) {
        Remove-Item $part -Force
        throw "SHA-256 of $Name is $got, expected $($want[$Name]): not installed"
    }
    Move-Item -Force $part $file
    Write-Host "got   $Name"
}

Get-Model 'yolox_s.onnx' 'https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx'
Get-Model 'u2net.onnx' 'https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx'
Write-Host "models in $Target (point PREPROCESS_MODELSDIR at it unless it is backend\models)"
