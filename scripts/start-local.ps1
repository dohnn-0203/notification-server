param(
    [int]$Port = 8080,
    [int]$RedisPort = 16379,
    [switch]$SkipBuild,
    [string]$MavenRepository = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimePath = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Force $runtimePath | Out-Null
$redisProcess = $null
Push-Location $projectRoot
try {
    foreach ($localPort in @($Port, $RedisPort)) {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $localPort)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    if (-not $SkipBuild) {
        $mavenArgs = @('-q', 'package', 'dependency:build-classpath', '-Dmdep.outputFile=target/test-classpath.txt', '-Dmdep.includeScope=test')
        if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
        & .\mvnw.cmd @mavenArgs
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    }
    $javaCommand = (Get-Command java).Source
    $classpath = (Join-Path $projectRoot 'target\test-classes') + ';' + (Get-Content 'target/test-classpath.txt' -Raw).Trim()
    $redisProcess = Start-Process -FilePath $javaCommand -ArgumentList @('-Dfile.encoding=UTF-8', '-cp', ('"' + $classpath + '"'), 'dev.riri.notification.LocalRedis', "$RedisPort") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtimePath\redis.log" -RedirectStandardError "$runtimePath\redis-error.log"
    $redisProcess.Id | Set-Content "$runtimePath\redis.pid"
    $redisReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($redisProcess.HasExited) { throw 'Local Redis failed. Check .runtime/redis-error.log.' }
        if ((Get-Content "$runtimePath\redis.log" -Raw -ErrorAction SilentlyContinue) -match 'Local Redis ready') { $redisReady = $true; break }
        Start-Sleep -Milliseconds 300
    }
    if (-not $redisReady) { throw 'Local Redis startup timed out.' }
    $jarPath = Join-Path $projectRoot 'target\notification-server-0.0.1-SNAPSHOT.jar'
    $appProcess = Start-Process -FilePath $javaCommand -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $jarPath + '"'), "--server.port=$Port", "--spring.data.redis.port=$RedisPort") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtimePath\server.log" -RedirectStandardError "$runtimePath\server-error.log"
    $appProcess.Id | Set-Content "$runtimePath\server.pid"
    $appReady = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($appProcess.HasExited) { throw 'Server failed. Check .runtime/server.log.' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health"
            if ($health.status -eq 'UP') { $appReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 300
    }
    if (-not $appReady) { throw 'Application startup timed out.' }
    Write-Host "Ready: http://127.0.0.1:$Port"
    Write-Host 'Temporary local Redis. Stop with scripts/stop-local.ps1.'
} catch {
    if ($redisProcess) { & (Join-Path $PSScriptRoot 'stop-local.ps1') }
    throw
} finally {
    Pop-Location
}
