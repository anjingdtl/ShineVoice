param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Package = 'com.shinevoice.debug',
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$runtimeRoot = Join-Path $ProjectRoot 'runtime\zipvoice'
$modelName = 'sherpa-onnx-zipvoice-distill-int8-zh-en-emilia'
$modelDirectory = Join-Path $runtimeRoot $modelName
$vocoder = Join-Path $runtimeRoot 'vocos_24khz.onnx'
$manifest = Join-Path $runtimeRoot 'model-manifest.properties'
$reference = Join-Path $runtimeRoot 'reference\default\reference.wav'
$remoteRoot = "/sdcard/Android/data/$Package/files"

foreach ($path in @($modelDirectory, $vocoder, $manifest, $reference)) {
    if (-not (Test-Path $path)) { throw "Missing local runtime asset: $path; run prepare-zipvoice-runtime.ps1 first." }
}

& adb -s $Serial shell "mkdir -p $remoteRoot/models/zipvoice $remoteRoot/voices/default"
if ($LASTEXITCODE -ne 0) { throw "Could not create remote app-specific directories" }
& adb -s $Serial push $modelDirectory "$remoteRoot/models/zipvoice/"
if ($LASTEXITCODE -ne 0) { throw "Model push failed" }
& adb -s $Serial push $vocoder "$remoteRoot/models/zipvoice/vocos_24khz.onnx"
if ($LASTEXITCODE -ne 0) { throw "Vocoder push failed" }
& adb -s $Serial push $manifest "$remoteRoot/models/zipvoice/model-manifest.properties"
if ($LASTEXITCODE -ne 0) { throw "Model manifest push failed" }
& adb -s $Serial push $reference "$remoteRoot/voices/default/reference.wav"
if ($LASTEXITCODE -ne 0) { throw "Reference WAV push failed" }
Write-Host "Pushed ZipVoice runtime to $Serial for $Package"
