param([string]$Stage='本地阶段版本',[switch]$OpenBrowser)
# 每阶段默认完整执行：停本项目、备份、Maven验证打包、前端验证、浏览器验收、隔离副本启动。
# 不写生产服务器、不清数据库、不自动恢复旧数据；失败时只恢复已知的上一版运行文件。
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalTools
$lock = $null; $previous = $null; $newRelease = $null; $stopped = $false
$started = [DateTime]::UtcNow
$releaseId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$backup = Join-Path $AiwywLocal "backups/$releaseId"
$newRelease = Join-Path $AiwywLocal "releases/$releaseId"
function Run-LocalCommand([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "检查未通过，退出码 $LASTEXITCODE；未切换到新版本。" }
}
Push-Location $AiwywRepo
try {
    try { $lock = [IO.File]::Open((Join-Path $AiwywLocal 'deploy.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw '另一个本地更新正在执行，请等待完成。' }
    $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { throw '未找到完整JDK，请先安装JDK并配置JAVA_HOME。' }
    Assert-LocalPorts
    $active = Read-LocalJson (Join-Path $AiwywLocal 'active-release.json')
    if ($active -and (Test-Path -LiteralPath (Join-Path $active.release 'release.json'))) { $previous = $active.release }
    elseif ((Test-Path -LiteralPath 'backend/target/aiwyw-server-0.1.0-SNAPSHOT.jar') -and (Test-Path -LiteralPath 'web/.output/server/index.mjs')) {
        $previous = Join-Path $AiwywLocal "releases/$releaseId-previous"
        Copy-LocalRelease $previous '接管前的本地版本'
    }
    Write-Host '[1/7] 正在停止本项目服务并备份数据…'
    Stop-LocalRuntime; $stopped = $true
    Run-LocalCommand $LocalNode @('scripts/backup-local-data.mjs',$backup)
    if (!(Test-Path -LiteralPath 'node_modules/nuxt/package.json')) {
        Write-Host '正在安装锁定版本的前端依赖…'
        Run-LocalCommand $npm @('ci')
    }
    Write-Host '[2/7] Maven验证与打包…'
    Run-LocalCommand $maven @('-B','-ntp','-f','backend/pom.xml','clean','verify')
    Write-Host '[3/7] 前端类型检查…'
    Run-LocalCommand $npm @('run','typecheck')
    Write-Host '[4/7] 编译前端…'
    Run-LocalCommand $npm @('run','build')
    Write-Host '[5/7] 隔离数据的浏览器流程检查…'
    Run-LocalCommand $npm @('run','test:e2e')
    Write-Host '[6/7] 创建运行副本并启动…'
    Copy-LocalRelease $newRelease $Stage
    $status = Start-LocalRelease $newRelease
    Write-Host '[7/7] 健康检查通过，记录本地版本…'
    $result = @{status='success';stage=$Stage;release=$newRelease;previous=$previous;backup=$backup;deployedAt=[DateTime]::UtcNow.ToString('o');durationSeconds=[math]::Round(([DateTime]::UtcNow-$started).TotalSeconds);url='http://127.0.0.1:3000/';checks=@('maven verify','typecheck','web build','browser e2e','HTTP health')}
    Write-LocalJson (Join-Path $AiwywLocal 'active-release.json') $result
    Write-LocalJson (Join-Path $AiwywLocal 'last-deploy.json') $result
    Write-Host "本地更新完成：http://127.0.0.1:3000/"
    Write-Host "数据备份：$backup"
    if ($OpenBrowser) { Start-Process $(if($status.installed){'http://127.0.0.1:3000/'}else{'http://127.0.0.1:3000/install'}) }
} catch {
    $failure = $_.Exception.Message
    Write-Host "本地更新失败：$failure"
    $restored = $false
    if ($stopped -and $previous) {
        try { Stop-LocalRuntime; $null = Start-LocalRelease $previous; $restored = $true; Write-Host '已恢复上一版运行文件，未覆盖当前数据库。' }
        catch { Write-Host '旧版未能恢复启动，请保留数据与备份并查看本地日志。' }
    }
    if ($lock) { Write-LocalJson (Join-Path $AiwywLocal 'last-deploy.json') @{status='failed';message=$failure;previousRestored=$restored;previous=$previous;backup=$backup;failedAt=[DateTime]::UtcNow.ToString('o')} }
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
