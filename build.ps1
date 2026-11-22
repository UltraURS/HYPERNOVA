param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$GradleHome = '',
    [switch]$Offline,
    [switch]$LegacyConfigMigration
)
$ErrorActionPreference = 'Stop'
if (-not $SdkRoot -or -not $JdkHome) { throw 'Set ANDROID_HOME and JAVA_HOME, or pass -SdkRoot and -JdkHome.' }
$names = @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME', 'ANDROID_USER_HOME')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $JdkHome
    $env:ANDROID_HOME = $SdkRoot
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.local/gradle-cache'
    $env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.local/android-user'
    $gradle = if ($GradleHome) { Join-Path $GradleHome 'bin/gradle.bat' } else { Join-Path $PSScriptRoot 'gradlew.bat' }
    $arguments = @('-p', $PSScriptRoot, ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', '--no-daemon', '--max-workers=2')
    if ($Offline) { $arguments += '--offline' }
    if ($LegacyConfigMigration) { $arguments += '-PlegacyConfigMigration' }
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
