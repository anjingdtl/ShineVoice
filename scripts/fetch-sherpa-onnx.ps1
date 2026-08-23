param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Version = '1.13.6',
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
$expectedSha256 = '0012D9A28F15BD6FB966B62B70A75DA3990512FDCCCE28B83098248CE4BE1698'
$actualSha256 = (Get-FileHash $destination -Algorithm SHA256).Hash
if ($actualSha256 -ne $expectedSha256) {
    throw "AAR checksum mismatch: expected $expectedSha256, got $actualSha256"
}
Write-Host "Ready: $destination ($length bytes, SHA-256 $actualSha256)"
