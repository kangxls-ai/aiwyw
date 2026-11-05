# 已部署版本无需重新编译即可启动。若尚未部署，引导先执行一键更新。
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalTools
Assert-LocalPorts
if ((Get-LocalListeners).Count -gt 0) { Start-Process 'http://127.0.0.1:3000/'; exit 0 }
$active = Read-LocalJson (Join-Path $AiwywLocal 'active-release.json')
if (!$active) { throw '尚无本地阶段部署，请先运行 update-local.cmd。' }
$status = Start-LocalRelease $active.release
Start-Process $(if($status.installed){'http://127.0.0.1:3000/'}else{'http://127.0.0.1:3000/install'})
