@echo off
setlocal

echo ========================================
echo  androCE - Live Logcat
echo ========================================
echo Press Ctrl+C to stop.
echo.

set "LOGDIR=%~dp0logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format 'yyyyMMdd_HHmmss'"') do set "TS=%%t"
set "LOGFILE=%LOGDIR%\logcat_%TS%.txt"
echo Saving to: %LOGFILE%
echo.

for /f %%p in ('adb shell pidof -s com.androce 2^>nul') do (
    echo Filtering by PID: %%p
    powershell -Command "adb logcat --pid=%%p 2>&1 | Tee-Object -FilePath '%LOGFILE%'"
    goto done
)

echo Could not find PID for com.androce.
echo Falling back to package grep filter...
echo.
powershell -Command "adb logcat 2>&1 | Select-String -Pattern 'androce' -CaseSensitive:$false | Tee-Object -FilePath '%LOGFILE%'"

:done
