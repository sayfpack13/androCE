@echo off
setlocal

echo ========================================
echo  androCE - Live Logcat
echo ========================================
echo Press Ctrl+C to stop.
echo.

for /f %%p in ('adb shell pidof -s com.androce 2^>nul') do (
    echo Filtering by PID: %%p
    adb logcat --pid=%%p
    goto done
)

echo Could not find PID for com.androce.
echo Falling back to package grep filter...
echo.
adb logcat | findstr /i "androce"

:done
