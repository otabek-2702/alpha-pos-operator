<#
Operator APK signing with key rotation (APK Signature Scheme v3).

Versions up to 2.0.1 were signed with the public React Native template debug key.
`create` makes a private key and a signed proof-of-rotation (lineage) from that
legacy key, so phones update in place without losing their settings. The legacy
key keeps only the "installed data" capability: it can never sign an update again.

Keys live outside the repository in %USERPROFILE%\.smart-pos-operator-signing.
Back that folder up offline: without it, installed phones cannot receive updates.

  -Key release  production key (published GitHub releases)
  -Key ci       emulator-test key (never used for phones)
#>
param(
    [Parameter(Mandatory)][ValidateSet('create', 'sign', 'verify')][string]$Action,
    [ValidateSet('release', 'ci')][string]$Key = 'release',
    [string]$InputApk,
    [string]$OutputApk,
    # Instrumentation APKs are signed with the current key only.
    [switch]$TestApk
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyDir = Join-Path $env:USERPROFILE '.smart-pos-operator-signing'

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '-17-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\keytool.exe')) } |
        Select-Object -First 1
    if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 17 directory, then retry.' }
    $env:JAVA_HOME = $jdk.FullName
}
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$legacy = Join-Path $keyDir 'legacy-debug.keystore'
$alias = @{ release = 'operator-release'; ci = 'operator-ci' }[$Key]
$store = Join-Path $keyDir "$Key.p12"
$passFile = Join-Path $keyDir "$Key.pass"
$lineage = Join-Path $keyDir "$Key-lineage.bin"
$legacySigner = @('--ks', $legacy, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android')
$newSigner = @('--ks', $store, '--ks-key-alias', $alias, '--ks-pass', "file:$passFile")

function Invoke-Tool([string]$Tool, [string[]]$Arguments) {
    # Windows PowerShell wraps native stderr (tool warnings) in errors; rely on the exit code.
    $ErrorActionPreference = 'Continue'
    $output = & $Tool @Arguments 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Tool)) failed (exit $LASTEXITCODE): $($output -join ' ')" }
    return $output
}

function Get-KeyDigest {
    $listing = Invoke-Tool $keytool @('-list', '-v', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias, '-storepass:file', $passFile)
    $line = $listing | Where-Object { "$_" -match '^\s*SHA256:\s*([0-9A-F:]+)\s*$' } | Select-Object -First 1
    if (-not $line) { throw 'Could not read the signing certificate digest.' }
    return (("$line" -replace '^\s*SHA256:\s*', '') -replace ':', '').Trim().ToLowerInvariant()
}

if ($Action -eq 'create') {
    if (Test-Path -LiteralPath $store) { throw "$store already exists. Never replace a release key: installed phones could no longer update." }
    New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
    if (-not (Test-Path -LiteralPath $legacy)) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'android\app\debug.keystore') -Destination $legacy
    }
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($passFile, ([Convert]::ToBase64String($bytes) -replace '[+/=]', ''))
    Invoke-Tool $keytool @('-genkeypair', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias,
        '-keyalg', 'RSA', '-keysize', '4096', '-validity', '12000',
        '-dname', "CN=Smart POS Operator $Key, O=Smart Food, C=UZ",
        '-storepass:file', $passFile, '-keypass:file', $passFile) | Out-Null
    $raw = "$lineage.raw"
    Invoke-Tool $apksigner (@('rotate', '--out', $raw, '--old-signer') + $legacySigner + @('--new-signer') + $newSigner) | Out-Null
    # The public legacy key may carry existing app data forward and nothing else.
    Invoke-Tool $apksigner (@('lineage', '--in', $raw, '--out', $lineage, '--signer') + $legacySigner +
        @('--set-installed-data', 'true', '--set-shared-uid', 'false', '--set-permission', 'false', '--set-rollback', 'false', '--set-auth', 'false')) | Out-Null
    Remove-Item -LiteralPath $raw
    Invoke-Tool $apksigner @('lineage', '--in', $lineage, '--print-certs', '-v')
    Write-Output "Created the $Key key in $keyDir (certificate SHA-256 $(Get-KeyDigest))."
    Write-Output 'Back up this folder offline. Without it, installed phones cannot receive updates.'
    exit 0
}

if (-not (Test-Path -LiteralPath $store) -or -not (Test-Path -LiteralPath $lineage)) {
    throw "Missing the $Key key. Run: scripts/operator-signing.ps1 -Action create -Key $Key"
}
if (-not $InputApk -or -not (Test-Path -LiteralPath $InputApk)) { throw 'Pass -InputApk with an existing APK.' }

if ($Action -eq 'sign') {
    if (-not $OutputApk) { throw 'Pass -OutputApk.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $OutputApk) -Force | Out-Null
    $signArguments = if ($TestApk) { @('sign') + $newSigner }
        else { @('sign') + $legacySigner + @('--next-signer') + $newSigner + @('--lineage', $lineage, '--rotation-min-sdk-version', '28') }
    Invoke-Tool $apksigner ($signArguments + @('--v4-signing-enabled', 'false', '--out', $OutputApk, $InputApk)) | Out-Null
    $InputApk = $OutputApk
}

# Verify: valid signature, and Android 9+ sees our private key as the current signer.
$digest = Get-KeyDigest
$report = Invoke-Tool $apksigner @('verify', '-v', '--print-certs', $InputApk)
$currentSigner = $report | Where-Object { "$_" -match "certificate SHA-256 digest:\s*$digest" }
if (-not $currentSigner) { throw "APK is not signed with the $Key key ($digest)." }
if (-not $TestApk) {
    $history = Invoke-Tool $apksigner @('lineage', '--in', $InputApk, '--print-certs')
    if (-not ($history -match $digest)) { throw 'APK has no signing lineage to the private key.' }
}
Write-Output "Verified $([IO.Path]::GetFileName($InputApk)): current signer $Key ($digest)."
