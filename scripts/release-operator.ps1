<#
Build, sign, verify and (with -Publish) publish an Operator update.

  npm run release -- -Version 2.1.1 -Notes "What changed" -Publish

Phones running 2.1.0+ check the public release repository every 30 minutes and
install the newest release themselves between calls. versionCode is derived from
the version (2.1.1 -> 20101), so every release must use a higher version.
#>
param(
    [Parameter(Mandatory)][ValidatePattern('^\d{1,2}\.\d{1,2}\.\d{1,2}$')][string]$Version,
    [string]$Notes = '',
    [string]$Architectures = 'arm64-v8a,armeabi-v7a',
    [ValidateSet('release', 'ci')][string]$Key = 'release',
    # Publish the APK already built, signed and tested in dist\release-<version>.
    [switch]$SkipBuild,
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseRepo = 'otabek-2702/smart-pos-operator-releases'
$numbers = $Version.Split('.') | ForEach-Object { [int]$_ }
$versionCode = $numbers[0] * 10000 + $numbers[1] * 100 + $numbers[2]
if ($Publish -and $Key -ne 'release') { throw 'Only release-key builds can be published.' }

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE)." }
}

Push-Location -LiteralPath $projectRoot
try {
    $suffix = if ($Key -eq 'ci') { '-ci' } else { '' }
    $outDir = Join-Path $projectRoot "dist\release-$Version$suffix"
    $apkName = "operator-$Version.apk"
    $apk = Join-Path $outDir $apkName
    if ($SkipBuild) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "No built APK at $apk." }
        Invoke-Checked powershell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\operator-signing.ps1', '-Action', 'verify', '-Key', $Key, '-InputApk', $apk)
    } else {
        # 1. Version (app.json drives the native versionName/versionCode).
        $current = [int](node -p "require('./app.json').expo.android.versionCode")
        if ($versionCode -lt $current) { throw "Version $Version ($versionCode) is older than the app's current versionCode $current." }
        Invoke-Checked node @('-e', "const fs=require('fs');const j=JSON.parse(fs.readFileSync('app.json','utf8'));j.expo.version=process.argv[1];j.expo.android.versionCode=Number(process.argv[2]);fs.writeFileSync('app.json',JSON.stringify(j,null,2)+'\n')", $Version, "$versionCode")
        Invoke-Checked npm.cmd @('version', $Version, '--no-git-tag-version', '--allow-same-version', '--ignore-scripts')

        # 2. Build (debug-key signed by Gradle) and 3. re-sign with the rotation lineage.
        Invoke-Checked powershell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\build-apk.ps1', '-Architectures', $Architectures)
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
        Invoke-Checked powershell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\operator-signing.ps1', '-Action', 'sign', '-Key', $Key,
            '-InputApk', 'android\app\build\outputs\apk\release\app-release.apk', '-OutputApk', $apk)
    }

    # 4. Package identity and version as Android will see them.
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk | Select-Object -First 1
    if ($badging -notmatch "name='com\.alphapos\.operatorlink' versionCode='$versionCode' versionName='$([regex]::Escape($Version))'") {
        throw "Unexpected package identity: $badging"
    }

    # 5. No private values (bot token, group IDs, setup QR) inside the APK.
    $audit = Join-Path $projectRoot '.private\audit-apk.py'
    if (Test-Path -LiteralPath $audit) { Invoke-Checked python @($audit, $apk) }
    elseif ($Publish) { throw 'The private APK audit (.private/audit-apk.py) is required before publishing.' }

    # 6. update.json read by the phones (UTF-8 without BOM).
    $sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        versionCode = $versionCode
        versionName = $Version
        apkUrl = "https://github.com/$releaseRepo/releases/download/v$Version/$apkName"
        sha256 = $sha256
        size = (Get-Item -LiteralPath $apk).Length
        notes = $Notes
        publishedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
    $utf8 = New-Object Text.UTF8Encoding $false
    [IO.File]::WriteAllText((Join-Path $outDir 'update.json'), ($manifest | ConvertTo-Json), $utf8)
    [IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS.txt'), "$sha256  $apkName`n", $utf8)
    Write-Output "Release files: $outDir (versionCode $versionCode, SHA-256 $sha256)"

    if (-not $Publish) { return }

    # 7. Publish to the public release repository and confirm what phones will download.
    & gh repo view $releaseRepo --json name *> $null
    if ($LASTEXITCODE -ne 0) {
        Invoke-Checked gh @('repo', 'create', $releaseRepo, '--public', '--add-readme', '--description', 'Smart POS Operator Android app releases (self-update source)')
    }
    $body = if ($Notes) { $Notes } else { "Smart POS Operator $Version" }
    Invoke-Checked gh @('release', 'create', "v$Version", $apk, (Join-Path $outDir 'update.json'), (Join-Path $outDir 'SHA256SUMS.txt'),
        '--repo', $releaseRepo, '--title', "Operator $Version", '--notes', $body, '--latest')
    $published = (& curl.exe -fsSL "https://github.com/$releaseRepo/releases/latest/download/update.json") -join "`n" | ConvertFrom-Json
    if ($published.sha256 -ne $sha256 -or $published.versionCode -ne $versionCode) { throw 'The published update.json does not match this build.' }
    $downloaded = Join-Path $env:TEMP "operator-$Version-published.apk"
    Invoke-Checked curl.exe @('-fsSL', '-o', $downloaded, $manifest.apkUrl)
    if ((Get-FileHash -LiteralPath $downloaded -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) { throw 'The published APK does not match this build.' }
    Remove-Item -LiteralPath $downloaded
    Write-Output "Published v$Version. Phones on 2.1.0+ install it automatically within about 30 minutes, between calls."
} finally {
    Pop-Location
}
