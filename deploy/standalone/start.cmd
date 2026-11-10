@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0lifecycle.ps1" start
exit /b %ERRORLEVEL%
