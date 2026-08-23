param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$runtimeRoot = Join-Path $ProjectRoot 'runtime\zipvoice'
$modelName = 'sherpa-onnx-zipvoice-distill-int8-zh-en-emilia'
$modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$modelName.tar.bz2"
$vocoderUrl = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx'
$archive = Join-Path $runtimeRoot "$modelName.tar.bz2"
$modelDirectory = Join-Path $runtimeRoot $modelName
$vocoder = Join-Path $runtimeRoot 'vocos_24khz.onnx'
$reference = Join-Path $runtimeRoot 'reference\default\reference.wav'

New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
if ($Force -or -not (Test-Path $modelDirectory)) {
    Write-Host "Downloading official ZipVoice model..."
    & curl.exe -L --fail --retry 5 --retry-all-errors --retry-delay 2 -o $archive $modelUrl
    if ($LASTEXITCODE -ne 0) { throw "ZipVoice model download failed with exit code $LASTEXITCODE" }
    Write-Host "Extracting model..."
    & tar.exe -xjf $archive -C $runtimeRoot
    if ($LASTEXITCODE -ne 0) { throw "ZipVoice model extraction failed with exit code $LASTEXITCODE" }
}

if ($Force -or -not (Test-Path $vocoder) -or (Get-Item $vocoder).Length -lt 1000000) {
    Write-Host "Downloading official Vocos 24 kHz vocoder..."
    & curl.exe -L --fail --retry 5 --retry-all-errors --retry-delay 2 -o $vocoder $vocoderUrl
    if ($LASTEXITCODE -ne 0) { throw "Vocoder download failed with exit code $LASTEXITCODE" }
}

$sourceReference = Join-Path $modelDirectory 'test_wavs\leijun-1.wav'
if (-not (Test-Path $sourceReference)) {
    throw "Official reference WAV not found: $sourceReference"
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reference) | Out-Null
Copy-Item $sourceReference $reference -Force

$required = @(
    (Join-Path $modelDirectory 'encoder.int8.onnx'),
    (Join-Path $modelDirectory 'decoder.int8.onnx'),
    (Join-Path $modelDirectory 'tokens.txt'),
    (Join-Path $modelDirectory 'lexicon.txt'),
    (Join-Path $modelDirectory 'espeak-ng-data'),
    $vocoder,
    $reference
)
$missing = $required | Where-Object { -not (Test-Path $_) }
if ($missing) { throw "ZipVoice runtime is incomplete:`n$($missing -join "`n")" }

$checksumFiles = @(
    (Join-Path $modelDirectory 'encoder.int8.onnx'),
    (Join-Path $modelDirectory 'decoder.int8.onnx'),
    $vocoder,
    (Join-Path $modelDirectory 'tokens.txt'),
    (Join-Path $modelDirectory 'lexicon.txt')
)
$records = $checksumFiles | ForEach-Object {
    "$(Split-Path -Leaf $_):$((Get-FileHash $_ -Algorithm SHA256).Hash.ToLowerInvariant())"
}
$aggregateInput = [Text.Encoding]::UTF8.GetBytes(($records -join "`n"))
$aggregateSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($aggregateInput)).ToLowerInvariant()
$totalSizeBytes = ($checksumFiles | Get-Item | Measure-Object -Property Length -Sum).Sum
@(
    "modelId=$modelName",
    'version=1.13.6',
    'engine=sherpa-onnx+zipvoice',
    "sizeBytes=$totalSizeBytes",
    "aggregateSha256=$aggregateSha256"
) | Set-Content -Encoding utf8 (Join-Path $runtimeRoot 'model-manifest.properties')
Write-Host "Ready: $runtimeRoot"
