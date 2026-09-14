param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Version = '1.13.8',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$destinationDirectory = Join-Path $ProjectRoot 'third_party'
$destination = Join-Path $destinationDirectory "sherpa-onnx-$Version.aar"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$Version/sherpa-onnx-$Version.aar"

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
if ($Force -or -not (Test-Path $destination) -or (Get-Item $destination).Length -lt 40000000) {
    Write-Host "Downloading official sherpa-onnx $Version AAR..."
    & curl.exe -L --fail --retry 5 --retry-all-errors --retry-delay 2 -o $destination $url
    if ($LASTEXITCODE -ne 0) { throw "AAR download failed with exit code $LASTEXITCODE" }
}

$length = (Get-Item $destination).Length
if ($length -lt 40000000) { throw "Downloaded AAR is incomplete: $length bytes" }
$expectedSha256 = '633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96'
$actualSha256 = (Get-FileHash $destination -Algorithm SHA256).Hash
if ($actualSha256 -ne $expectedSha256) {
    throw "AAR checksum mismatch: expected $expectedSha256, got $actualSha256"
}
Write-Host "Ready: $destination ($length bytes, SHA-256 $actualSha256)"
