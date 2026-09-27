@echo off
rem Launcher for cmd.exe: delegates to the PowerShell launcher (keeps Unicode arguments intact).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0wiki-trends.ps1" %*
exit /b %ERRORLEVEL%
