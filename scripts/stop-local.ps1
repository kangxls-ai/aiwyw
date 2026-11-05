# 只停止本仓库受管服务；不删除数据和历史版本。
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalTools
Stop-LocalRuntime
Write-Host 'AI万有网本地服务已停止，数据已保留。'
