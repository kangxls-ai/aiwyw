@echo off
cd /d "%~dp0"
if exist ".local\active-release.json" (
  call "%~dp0open-local.cmd"
) else (
  call "%~dp0update-local.cmd"
)
