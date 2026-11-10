@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0lifecycle.ps1" stop
exit /b %ERRORLEVEL%
