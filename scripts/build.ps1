param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'),
    [int]$VersionCode = 100
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $JavaHome -or -not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) {
    throw 'Set JAVA_HOME or pass -JavaHome with the path to JDK 17.'
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath "$AndroidSdk/platforms/android-34/android.jar")) {
    throw 'Set ANDROID_HOME or pass -AndroidSdk with an Android SDK containing platform 34.'
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & ./gradlew.bat @Tasks "-PversionCode=$VersionCode" --console=plain --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
