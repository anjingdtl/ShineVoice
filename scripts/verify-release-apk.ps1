# Hard acceptance for the canonical signed ShineVoice APK.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$expectedSigner = '017b3fbed4001083f2f70a0c51e8e463322df66b095e1c3a476fdd0d86dc2a0a'
$version = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'version.properties') | ConvertFrom-StringData)
$expectedName = "ShineVoice-V$($version.versionName)-release.apk"
$apk = Join-Path $projectRoot "dist\apk\release\$expectedName"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK not found: $apk" }

$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) }
$sdk = $sdkCandidates | Select-Object -First 1
if (-not $sdk) { throw 'Android SDK not found.' }
$buildToolsRoot = Join-Path $sdk 'build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android Build Tools not found.' }
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
foreach ($tool in @($apksigner, $zipalign, $aapt)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required Android tool missing: $tool" }
}

$signerOutput = (& $apksigner verify --verbose --print-certs $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed.' }
if ($signerOutput -notmatch 'Verified using v2 scheme \(APK Signature Scheme v2\): true') {
    throw 'APK is not verified with the v2 signature scheme.'
}
if ($signerOutput -notmatch 'Number of signers: 1') { throw 'APK must have exactly one signer.' }
$certMatch = [regex]::Match(
    $signerOutput,
    '(?:Signer #1 certificate SHA-256 digest|V2 Signer: certificate SHA-256 digest): ([0-9a-fA-F]{64})'
)
if (-not $certMatch.Success -or $certMatch.Groups[1].Value.ToLowerInvariant() -ne $expectedSigner) {
    throw 'APK signer certificate does not match the fixed release certificate.'
}

$zipOutput = (& $zipalign -c -P 16 -v 4 $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $zipOutput -notmatch 'Verification successful') { throw 'APK zipalign verification failed.' }
$badging = (& $aapt dump badging $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'aapt dump badging failed.' }
$packageMatch = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
if (-not $packageMatch.Success) { throw 'Could not parse APK package metadata.' }
if ($packageMatch.Groups[1].Value -ne 'com.shinevoice') { throw 'APK package name mismatch.' }
if ($packageMatch.Groups[2].Value -ne $version.versionCode) { throw 'APK versionCode mismatch.' }
if ($packageMatch.Groups[3].Value -ne $version.versionName) { throw 'APK versionName mismatch.' }

$fileInfo = Get-Item -LiteralPath $apk
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
Write-Output 'Release APK verification PASS.'
Write-Output "APK=$apk"
Write-Output 'package=com.shinevoice'
Write-Output "versionName=$($version.versionName)"
Write-Output "versionCode=$($version.versionCode)"
Write-Output "signerSha256=$expectedSigner"
Write-Output "sizeBytes=$($fileInfo.Length)"
Write-Output "sha256=$hash"
