# 本地运行工具的共享函数。只识别本仓库进程，不按java/node名称批量结束进程。
# 从PowerShell 7调用Windows PowerShell时，优先使用当前引擎的系统模块，避免继承不兼容模块。
if ($PSVersionTable.PSEdition -eq 'Desktop') { $env:PSModulePath = (Join-Path $PSHOME 'Modules') + ';' + $env:PSModulePath }
$ErrorActionPreference = 'Stop'
$AiwywRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$AiwywLocal = Join-Path $AiwywRepo '.local'

function Write-LocalJson([string]$File, $Value) {
    [IO.File]::WriteAllText($File, ($Value | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding $false))
}
function Read-LocalJson([string]$File) {
    if (Test-Path -LiteralPath $File) { return Get-Content -LiteralPath $File -Raw -Encoding UTF8 | ConvertFrom-Json }
    return $null
}
function Initialize-LocalTools {
    New-Item -ItemType Directory -Path $AiwywLocal -Force | Out-Null
    # 备份含数据库和主密钥；目录仅当前Windows账户可读写，不能作为网站静态目录。
    $acl = Get-Acl -LiteralPath $AiwywLocal
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $AiwywLocal -AclObject $acl
    foreach ($folder in 'logs','backups','releases') { New-Item -ItemType Directory -Path (Join-Path $AiwywLocal $folder) -Force | Out-Null }
    $userJava = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')
    if ($userJava) { $env:JAVA_HOME = $userJava }
    $mavenHome = [Environment]::GetEnvironmentVariable('MAVEN_HOME','User')
    if (!$mavenHome) { $mavenHome = $env:MAVEN_HOME }
    if ($mavenHome) { $env:MAVEN_HOME = $mavenHome; $env:Path = (Join-Path $mavenHome 'bin') + ';' + $env:Path }
    $script:LocalNode = (Get-Command node.exe -ErrorAction Stop).Source
}
function Get-LocalListeners {
    return @(Get-NetTCPConnection -LocalPort 3000,18080 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
}
function Test-OwnedProcess($Process) {
    if (!$Process -or !$Process.CommandLine) { return $false }
    # 校验实际完整命令行中的本仓库绝对路径；同机其他Java或Node服务不属于本项目。
    return $Process.CommandLine.IndexOf($AiwywRepo, [StringComparison]::OrdinalIgnoreCase) -ge 0
}
function Assert-LocalPorts {
    foreach ($processIdValue in (Get-LocalListeners)) {
        $item = Get-CimInstance Win32_Process -Filter "ProcessId = $processIdValue"
        if (!(Test-OwnedProcess $item)) { throw "端口3000或18080被其他应用占用，未停止其他应用。进程ID：$processIdValue" }
    }
}
function Stop-LocalRuntime {
    Assert-LocalPorts
    $state = Read-LocalJson (Join-Path $AiwywLocal 'runtime-state.json')
    if ($state -and $state.status -in @('starting','running','stopping')) {
        $supervisor = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.supervisorPid)" -ErrorAction SilentlyContinue
        if ((Test-OwnedProcess $supervisor) -and $supervisor.CommandLine.Contains('local-supervisor.mjs')) {
            Write-LocalJson (Join-Path $AiwywLocal 'stop-request.json') @{runId=$state.runId}
            $deadline = [DateTime]::UtcNow.AddSeconds(35)
            while ([DateTime]::UtcNow -lt $deadline) {
                $current = Read-LocalJson (Join-Path $AiwywLocal 'runtime-state.json')
                if ($current -and $current.status -in @('stopped','failed') -and (Get-LocalListeners).Count -eq 0) { return }
                Start-Sleep -Milliseconds 350
            }
            throw '本地服务未按时停止，请查看受保护的运行日志；没有强行结束未知进程。'
        }
    }
    # 首次接管以前的npm start：仅停止经路径核实的旧前后端，让旧父进程自行退出。
    foreach ($processIdValue in (Get-LocalListeners)) {
        $item = Get-CimInstance Win32_Process -Filter "ProcessId = $processIdValue" -ErrorAction SilentlyContinue
        if (Test-OwnedProcess $item) { Stop-Process -Id $processIdValue -ErrorAction SilentlyContinue }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ((Get-LocalListeners).Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if ((Get-LocalListeners).Count -gt 0) { throw '本地端口尚未释放，未开始更新。' }
}
function Start-LocalRelease([string]$Release) {
    $expected = [IO.Path]::GetFullPath((Join-Path $AiwywLocal 'releases')) + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath($Release)
    if (!$resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase)) { throw '发布目录不在本项目运行目录内' }
    Assert-LocalPorts
    if ((Get-LocalListeners).Count -gt 0) { throw '本地服务仍在运行，不能重复启动' }
    $run = [Guid]::NewGuid().ToString('N')
    $supervisorScript = Join-Path $AiwywRepo 'scripts/local-supervisor.mjs'
    # Start-Process的参数数组会重组成命令行，因此显式引用路径，支持目录中有空格。
    $arguments = @('"'+$supervisorScript+'"','"'+$resolved+'"')
    $process = Start-Process -FilePath $LocalNode -ArgumentList $arguments -WorkingDirectory $AiwywRepo -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $AiwywLocal "logs/supervisor-$run.log") `
        -RedirectStandardError (Join-Path $AiwywLocal "logs/supervisor-$run.error.log")
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw '本地进程启动失败，请查看运行日志' }
        try {
            $status = Invoke-RestMethod 'http://127.0.0.1:3000/api/install/status' -TimeoutSec 3
            $catalog = Invoke-RestMethod 'http://127.0.0.1:3000/api/public/catalog' -TimeoutSec 3
            $homeResponse = Invoke-WebRequest 'http://127.0.0.1:3000/' -UseBasicParsing -TimeoutSec 5
            if ($homeResponse.StatusCode -eq 200 -and $null -ne $catalog.sections) { return $status }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw '本地服务健康检查超时，更新未标记成功。'
}
function Copy-LocalRelease([string]$Destination, [string]$Stage) {
    New-Item -ItemType Directory -Path (Join-Path $Destination 'backend') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Destination 'web') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $AiwywRepo 'backend/target/aiwyw-server-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $Destination 'backend/server.jar')
    Get-ChildItem -LiteralPath (Join-Path $AiwywRepo 'web/.output') -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $Destination 'web') -Recurse }
    Write-LocalJson (Join-Path $Destination 'release.json') @{stage=$Stage;createdAt=[DateTime]::UtcNow.ToString('o');source=$AiwywRepo}
}
