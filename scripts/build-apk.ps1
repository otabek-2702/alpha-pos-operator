param(
    [string]$Architectures = 'arm64-v8a,armeabi-v7a'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot

try {
    # Prefer an explicitly configured JDK; otherwise use a cached Gradle JDK 17.
    if (-not $env:JAVA_HOME) {
        $jdkCache = Join-Path $env:USERPROFILE '.gradle\jdks'
        $jdk = Get-ChildItem -LiteralPath $jdkCache -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '-17-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe')) } |
            Select-Object -First 1
        if (-not $jdk) { throw 'Set JAVA_HOME to an installed JDK 17 directory, then retry.' }
        $env:JAVA_HOME = $jdk.FullName
    }
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        throw 'JAVA_HOME must point to a full JDK, not a Java runtime (JRE).'
    }
    if (-not $env:ANDROID_HOME) {
        $env:ANDROID_HOME = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        throw 'Set ANDROID_HOME to the installed Android SDK directory, then retry.'
    }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:NODE_ENV = 'production'
    $env:CI = '1'
    $env:CMAKE_BUILD_PARALLEL_LEVEL = '1'

    # Keep generated native config current while retaining incremental build caches.
    & .\node_modules\.bin\expo.cmd prebuild --platform android --no-install
    if ($LASTEXITCODE -ne 0) { throw "Expo prebuild failed (exit $LASTEXITCODE)." }

    & .\android\gradlew.bat -p android :app:assembleRelease -I (Join-Path $PSScriptRoot 'native-jobs.init.gradle') --build-cache --console=plain --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' "-PreactNativeArchitectures=$Architectures"
    if ($LASTEXITCODE -ne 0) { throw "Android build failed (exit $LASTEXITCODE)." }

    # AAR dependencies contain every ABI even when CMake built only one. Verify
    # packaging filters and the app's required native engines before delivery.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $builtApk = Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk'
    $archive = [IO.Compression.ZipFile]::OpenRead($builtApk)
    try {
        $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
        $actualAbis = @($nativeEntries | ForEach-Object { $_.FullName.Split('/')[1] } | Sort-Object -Unique)
        $requestedAbis = @($Architectures.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique)
        if (Compare-Object $requestedAbis $actualAbis) { throw "APK ABI packaging differs from requested architectures: $($actualAbis -join ',')." }
        foreach ($abi in $requestedAbis) {
            foreach ($library in @('libexpo-modules-core.so', 'libreactnative.so', 'libhermes.so', 'libc++_shared.so')) {
                if (-not $archive.GetEntry("lib/$abi/$library")) { throw "APK is missing $library for $abi; refusing to publish an incomplete build." }
            }
        }
    } finally { $archive.Dispose() }

    $outputDir = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $apkPath = Join-Path $outputDir 'operator.apk'
    Copy-Item -LiteralPath 'android\app\build\outputs\apk\release\app-release.apk' -Destination $apkPath -Force
    Write-Output "Standalone APK: $apkPath"
    Write-Output 'Uses the native project signing configuration; the default is the local debug key.'
} finally {
    Pop-Location
}
