param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $taskJava = 'C:\Program Files\Java\jdk-17'
    if (Test-Path -LiteralPath (Join-Path $taskJava 'bin\java.exe')) { $env:JAVA_HOME = $taskJava }
    else { throw '请设置 JAVA_HOME，指向已安装的 JDK 17 或兼容版本。' }
}
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'local.properties'))) {
    $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $taskSdk)) { throw '未找到 Android SDK，请安装并设置 ANDROID_HOME。' }
    $taskSdkProperty = 'sdk.dir=' + $taskSdk.Replace('\', '\\').Replace(':', '\:')
    [System.IO.File]::WriteAllText((Join-Path $taskRoot 'local.properties'), $taskSdkProperty + "`n", [System.Text.UTF8Encoding]::new($false))
}
Push-Location -LiteralPath $taskRoot
try {
    $taskArguments = @(':app:assembleDebug', '--console=plain')
    if (-not $SkipChecks) { $taskArguments = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', '--console=plain') }
    & '.\gradlew.bat' @taskArguments
    if ($LASTEXITCODE -ne 0) { throw '构建或检查未通过，请查看上方输出。' }
    & python (Join-Path $PSScriptRoot 'check_native_alignment.py') (Join-Path $taskRoot 'app\build\outputs\apk\debug\app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw '安装包未通过 16 KB 原生库对齐检查。' }
    New-Item -ItemType Directory -Path (Join-Path $taskRoot 'dist') -Force | Out-Null
    $taskMetadata = Get-Content -LiteralPath (Join-Path $taskRoot 'app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
    $taskVersion = [string]$taskMetadata.elements[0].versionName
    if ($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw '构建元数据中的版本号无效。' }
    $taskOutputName = '暮光相册-v' + $taskVersion + '.apk'
    $taskOutput = Join-Path $taskRoot ('dist\' + $taskOutputName)
    Copy-Item -LiteralPath (Join-Path $taskRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $taskOutput -Force
    $taskChecksum = (Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $taskRoot 'dist\SHA256SUMS.txt'), $taskChecksum + '  ' + $taskOutputName + "`n", [System.Text.UTF8Encoding]::new($false))
    Write-Output ('安装包已生成：' + $taskOutput)
} finally { Pop-Location }
