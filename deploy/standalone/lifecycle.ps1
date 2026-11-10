param([Parameter(Mandatory)][ValidateSet('start','stop')][string]$Action)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PSScriptRoot)
$jar = Join-Path $root 'aiwyw-server.jar'
$run = Join-Path $root 'run'
$pidFile = Join-Path $run 'server.pid'
$stopFile = Join-Path $run 'stop.request'
New-Item -ItemType Directory -Path $run -Force | Out-Null
$server = $null
if (Test-Path -LiteralPath $pidFile) {
  $serverId = 0
  if (![int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$serverId) -or $serverId -le 0) {
    throw 'Invalid run/server.pid'
  }
  $server = Get-CimInstance Win32_Process -Filter "ProcessId=$serverId"
  if ($server -and ($server.Name -ne 'java.exe' -or !$server.CommandLine -or
      !$server.CommandLine.Contains('-jar "' + $jar + '"'))) {
    throw 'PID does not belong to this deployment'
  }
}
if ($Action -eq 'start') {
  if ($server) { throw 'Spring Boot is already running' }
  if (!(Test-Path -LiteralPath $jar) -or !(Test-Path -LiteralPath (Join-Path $root 'lib')) -or
      !(Test-Path -LiteralPath (Join-Path $root 'config/application.properties'))) {
    throw 'Missing JAR, lib, or config/application.properties'
  }
  $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
  if (Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile }
  $socketFallback = Join-Path ([IO.Path]::GetTempPath()) ('aiwyw-tcp-only-' + [Guid]::NewGuid().ToString('N'))
  $arguments = @(
    ('"-Djdk.net.unixdomain.tmpdir=' + $socketFallback + '"'),
    ('"-Dloader.path=' + (Join-Path $root 'lib') + '"'),
    '-Daiwyw.windows-control=true', '-jar', ('"' + $jar + '"'),
    '--spring.config.additional-location=file:./config/'
  )
  $process = Start-Process -FilePath $java -WorkingDirectory $root -WindowStyle Hidden -ArgumentList $arguments -PassThru -RedirectStandardOutput (Join-Path $run 'server.log') -RedirectStandardError (Join-Path $run 'server-error.log')
  [IO.File]::WriteAllText($pidFile, [string]$process.Id)
  Start-Sleep -Seconds 2
  if ($process.HasExited) { throw 'Startup failed; check run/server.log and run/server-error.log' }
  Write-Output "Spring Boot PID $($process.Id); check /api/install/status for readiness"
} elseif ($server) {
  [IO.File]::WriteAllText($stopFile, [string]$serverId)
  Wait-Process -Id $serverId -Timeout 60 -ErrorAction Stop
  Remove-Item -LiteralPath $pidFile
  if (Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile }
  Write-Output 'Spring Boot stopped'
} else {
  if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile }
  Write-Output 'Spring Boot is stopped'
}
