# Load the existing tavo-mini signing environment into this process and build
# a signed ShineVoice APK. Secret values are never printed or written.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

function Read-EnvironmentValue([string] $name) {
    $processValue = [Environment]::GetEnvironmentVariable($name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    return [Environment]::GetEnvironmentVariable($name, 'User')
}

$mapping = @{
    'SHINEVOICE_RELEASE_STORE_FILE' = @('SHINEVOICE_RELEASE_STORE_FILE', 'SHINE_WRITER_RELEASE_STORE_FILE')
    'SHINEVOICE_RELEASE_STORE_PASSWORD' = @('SHINEVOICE_RELEASE_STORE_PASSWORD', 'SHINE_WRITER_RELEASE_STORE_PASSWORD')
    'SHINEVOICE_RELEASE_KEY_ALIAS' = @('SHINEVOICE_RELEASE_KEY_ALIAS', 'SHINE_WRITER_RELEASE_KEY_ALIAS')
    'SHINEVOICE_RELEASE_KEY_PASSWORD' = @('SHINEVOICE_RELEASE_KEY_PASSWORD', 'SHINE_WRITER_RELEASE_KEY_PASSWORD')
}

foreach ($target in $mapping.Keys) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($target, 'Process'))) {
        foreach ($source in $mapping[$target]) {
            $value = Read-EnvironmentValue $source
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                [Environment]::SetEnvironmentVariable($target, $value, 'Process')
                break
            }
        }
    }
}

$required = @(
    'SHINEVOICE_RELEASE_STORE_FILE',
    'SHINEVOICE_RELEASE_STORE_PASSWORD',
    'SHINEVOICE_RELEASE_KEY_ALIAS',
    'SHINEVOICE_RELEASE_KEY_PASSWORD'
)
$missing = @($required | Where-Object {
    [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
})
if ($missing.Count -gt 0) {
    throw "Missing release signing vars: $($missing -join ', ')"
}
$keystore = [Environment]::GetEnvironmentVariable('SHINEVOICE_RELEASE_STORE_FILE', 'Process')
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
    throw 'Release signing keystore does not exist.'
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') ':app:assembleRelease' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed with exit code $LASTEXITCODE" }

    $version = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'version.properties') | ConvertFrom-StringData).versionName
    $source = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Gradle did not produce app-release.apk.' }
    $destinationDir = Join-Path $projectRoot 'dist\apk\release'
    New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
    $destination = Join-Path $destinationDir "ShineVoice-V$version-release.apk"
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $destination).Length
    Write-Output 'Release APK prepared.'
    Write-Output "APK=$destination"
    Write-Output "versionName=$version"
    Write-Output "sizeBytes=$size"
    Write-Output "sha256=$hash"
} finally {
    Pop-Location
}
