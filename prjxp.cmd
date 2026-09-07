@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prjxp.ps1" %*
exit /b %ERRORLEVEL%
