$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
function Find-CachedJar([string]$relativePath) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $taskCache $relativePath) -Recurse -Filter '*.jar' | Select-Object -First 1
    if (-not $jar) { throw "Missing cached JVM dependency: $relativePath. Run the Android build once first." }
    return $jar.FullName
}
$taskJdk = $env:JAVA_HOME
if (-not $taskJdk) {
    $taskJdk = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory |
        Where-Object { $_.Name -match '-17-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe')) } |
        Select-Object -First 1 -ExpandProperty FullName
}
$java = Join-Path $taskJdk 'bin\java.exe'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\1.9.25'
$compilerJars = @(
    (Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\1.9.25'),
    $stdlib,
    (Find-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\1.9.25'),
    (Find-CachedJar 'org.jetbrains.kotlin\kotlin-daemon-embeddable\1.9.25'),
    (Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\1.6.10'),
    (Find-CachedJar 'org.jetbrains.intellij.deps\trove4j\1.0.20200330'),
    (Find-CachedJar 'org.jetbrains\annotations')
)
$taskOutput = Join-Path $projectRoot '.native-policy-tests'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
& $java '-Xmx256m' '-cp' ($compilerJars -join ';') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-classpath' $stdlib '-jvm-target' '17' '-d' $taskOutput `
    (Join-Path $projectRoot 'plugins\operator-native\OperatorRecordingPolicy.kt') (Join-Path $projectRoot 'tests\native\OperatorRecordingPolicyTest.kt')
if ($LASTEXITCODE -ne 0) { throw 'Native recording policy test compilation failed.' }
& $java '-cp' ($taskOutput + ';' + $stdlib) '__PACKAGE__.OperatorRecordingPolicyTestKt'
if ($LASTEXITCODE -ne 0) { throw 'Native recording policy tests failed.' }
