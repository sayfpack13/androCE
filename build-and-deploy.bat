@echo off
setlocal EnableDelayedExpansion

cd /d "%~dp0"

echo ========================================
echo  androCE - Build, Deploy ^& Log
echo ========================================
echo WLAN: adb-wlan.conf or build-and-deploy.bat IP:PORT
echo.

REM -------- [1/4] Build --------
echo [1/4] Building debug APK...
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo ERROR: APK not found at %APK%
    exit /b 1
)
echo BUILD OK: %APK%
echo.

REM -------- [2/4] ADB (USB or WLAN) --------
echo [2/4] ADB device...
call :adb_ensure %*
if errorlevel 1 exit /b 1
echo.

REM -------- [3/4] Install + launch --------
echo [3/4] Installing and launching...
adb logcat -c
adb install -r "%APK%"
if errorlevel 1 (
    echo Install failed ^(signature mismatch^). Uninstalling old package...
    adb uninstall com.androce
    adb install -r "%APK%"
    if errorlevel 1 (
        echo INSTALL FAILED
        exit /b 1
    )
)
adb shell am start -n com.androce/.MainActivity
ping 127.0.0.1 -n 3 >nul
echo.

REM -------- [4/4] Logcat --------
echo [4/4] Logcat ^(Ctrl+C to stop^)...
echo       Tags: SpeedInjector only ^(errors + inject OK/FAIL^)
echo       For speed hack: pick game process, open Speed tab, tap Activate.
call :stream_logs
exit /b 0

REM ============================================================
REM  ADB: USB if present, else adb-wlan.conf / ADB_WLAN / arg
REM ============================================================
:adb_ensure
set "WLAN_ADDR="

if /i "%~1"=="wlan" goto adb_read_conf
if not "%~1"=="" (
    set "WLAN_ADDR=%~1"
    goto adb_try_connect
)

if defined ADB_WLAN (
    set "WLAN_ADDR=%ADB_WLAN%"
    goto adb_try_connect
)

:adb_read_conf
if exist "%~dp0adb-wlan.conf" (
    for /f "usebackq eol=# tokens=1* delims= " %%A in ("%~dp0adb-wlan.conf") do (
        if not "%%A"=="" (
            set "WLAN_ADDR=%%A"
            goto adb_try_connect
        )
    )
)

:adb_try_connect
call :adb_count
if !DEVICE_COUNT! GEQ 1 exit /b 0

if not defined WLAN_ADDR (
    echo ERROR: No ADB device. USB plug-in or set adb-wlan.conf
    adb devices 2>nul
    exit /b 1
)

echo Connecting WLAN: !WLAN_ADDR!
adb connect "!WLAN_ADDR!"
ping 127.0.0.1 -n 3 >nul
call :adb_count
if !DEVICE_COUNT! GEQ 1 (
    echo WLAN device ready.
    exit /b 0
)

echo ERROR: adb connect failed. Pair first: adb pair IP:PAIR_PORT
adb devices 2>nul
exit /b 1

:adb_count
set "DEVICE_COUNT=0"
for /f "skip=1 tokens=1,2" %%a in ('adb devices 2^>nul') do (
    if "%%b"=="device" set /a DEVICE_COUNT+=1
)
exit /b 0

REM ============================================================
REM  Filtered logcat to console + logs\logcat_*.txt
REM ============================================================
:stream_logs
set "LOGDIR=%~dp0logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format 'yyyyMMdd_HHmmss'"') do set "TS=%%t"
set "LOGFILE=%LOGDIR%\logcat_%TS%.txt"
echo Saving to: %LOGFILE%
echo.

REM Tag filter only — avoid matching com.androce in system/OEM spam.
powershell -NoProfile -Command "adb logcat -v time SpeedInjector:I SpeedHook:E *:S 2>&1 | Tee-Object -FilePath '%LOGFILE%'"
exit /b 0
