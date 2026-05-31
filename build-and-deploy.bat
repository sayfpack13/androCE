@echo off

setlocal EnableDelayedExpansion



cd /d "%~dp0"



set "LOGDIR=%~dp0logs"

if not exist "%LOGDIR%" mkdir "%LOGDIR%"

del /q "%LOGDIR%\_section_*.tmp" 2>nul

del /q "%LOGDIR%\_build_*.txt" 2>nul

del /q "%LOGDIR%\_install_*.txt" 2>nul



for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%t"

set "SESSION_LOG=%LOGDIR%\deploy_%TS%.txt"

> "%SESSION_LOG%" echo ======== androCE deploy %TS% ========



call :log "========================================"

call :log " androCE - Build, Deploy and Log"

call :log "========================================"

call :log "Session log: %SESSION_LOG%"

call :log "WLAN: adb-wlan.conf or build-and-deploy.bat IP:PORT"

call :log.



call :log "[1/4] Building debug APK..."

set "BUILD_TMP=%LOGDIR%\_build_%TS%.txt"

call gradlew.bat assembleDebug --no-daemon > "%BUILD_TMP%" 2>&1

set "GRADLE_ERR=!errorlevel!"

type "%BUILD_TMP%"

type "%BUILD_TMP%" >> "%SESSION_LOG%"

del "%BUILD_TMP%" 2>nul

if !GRADLE_ERR! neq 0 (

    call :log "BUILD FAILED"

    exit /b 1

)



set "APK=app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK%" (

    call :log "ERROR: APK not found at %APK%"

    exit /b 1

)

call :log "BUILD OK: %APK%"

call :log.



call :log "[2/4] ADB device..."

call :adb_ensure %* >> "%SESSION_LOG%" 2>&1

if errorlevel 1 exit /b 1

call :log.



call :log "[3/4] Installing and launching..."

adb devices >> "%SESSION_LOG%" 2>&1

adb logcat -c

set "INSTALL_TMP=%LOGDIR%\_install_%TS%.txt"

adb install -r "%APK%" > "%INSTALL_TMP%" 2>&1

set "INSTALL_ERR=!errorlevel!"

type "%INSTALL_TMP%"

type "%INSTALL_TMP%" >> "%SESSION_LOG%"

if !INSTALL_ERR! neq 0 (

    call :log "Install failed - uninstalling and retrying..."

    adb uninstall com.androce >> "%INSTALL_TMP%" 2>&1

    type "%INSTALL_TMP%" >> "%SESSION_LOG%"

    adb install -r "%APK%" > "%INSTALL_TMP%" 2>&1

    set "INSTALL_ERR=!errorlevel!"

    type "%INSTALL_TMP%"

    type "%INSTALL_TMP%" >> "%SESSION_LOG%"

    if !INSTALL_ERR! neq 0 (

        call :log "INSTALL FAILED"

        exit /b 1

    )

)

del "%INSTALL_TMP%" 2>nul

adb shell am start -n com.androce/.MainActivity >> "%SESSION_LOG%" 2>&1

ping 127.0.0.1 -n 3 >nul

call :log.



call :log "[4/4] Logcat + live device log pull (Ctrl+C to stop)..."

call :log "      Session file: %SESSION_LOG%"

call :log "      Pulls every 5s: install.log, frida-server.log, androce_log.txt"

call :log "      Use app (Install Frida, speed hack) while this runs"

call :log.



set "APP_PID="

for /L %%i in (1,1,15) do (

    for /f "delims=" %%p in ('adb shell pidof -s com.androce 2^>nul') do set "APP_PID=%%p"

    if defined APP_PID goto :have_pid

    ping 127.0.0.1 -n 2 >nul

)

call :log "App PID: (not found yet - logcat still runs)"

goto :run_stream



:have_pid

call :log "App PID: !APP_PID!"



:run_stream

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy-log-stream.ps1" -SessionLog "%SESSION_LOG%" -PullIntervalSec 5



call :log.

call :log "Done. Full log: %SESSION_LOG%"

exit /b 0



:log

if "%~1"=="" (

    echo.

    >>"%SESSION_LOG%" echo.

) else (

    echo %~1

    >>"%SESSION_LOG%" echo %~1

)

exit /b 0



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


