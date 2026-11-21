@echo off
rem MultiSector task runner. Run "dev" with no arguments for the task list.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\dev.ps1" %*
exit /b %ERRORLEVEL%
