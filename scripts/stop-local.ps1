$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
foreach ($service in @('server', 'redis')) {
    $pidFile = Join-Path $projectRoot ".runtime\$service.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $processId = [int](Get-Content -LiteralPath $pidFile)
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    $expectedCommand = if ($service -eq 'server') { Join-Path $projectRoot 'target\notification-server-0.0.1-SNAPSHOT.jar' } else { Join-Path $projectRoot 'target\test-classes' }
    if ($processInfo -and $processInfo.CommandLine.Contains($expectedCommand)) {
        # 이 프로젝트에서 띄운 프로세스만 종료
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$processId"
        foreach ($child in $children) {
            if ($child.Name -match '^redis-server.*\.exe$') { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
        }
        Stop-Process -Id $processId -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidFile
}
