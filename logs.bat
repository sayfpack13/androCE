@echo off
setlocal

echo ========================================
echo  androCE - Live Logcat
echo ========================================
echo Press Ctrl+C to stop.
echo Tags: androce, SpeedInjector, SpeedHook, MemoryReader, Scanner, MemoryWriter
echo.

set "LOGDIR=%~dp0logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format 'yyyyMMdd_HHmmss'"') do set "TS=%%t"
set "LOGFILE=%LOGDIR%\logcat_%TS%.txt"
echo Saving to: %LOGFILE%
echo.

powershell -NoProfile -Command "adb logcat -v time 2>&1 | Select-String -Pattern 'androce|SpeedInjector|SpeedHook|MemoryReader|Scanner|MemoryWriter' | Tee-Object -FilePath '%LOGFILE%'"
