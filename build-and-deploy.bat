@echo off
setlocal EnableDelayedExpansion

cd /d "%~dp0"

REM -------- single session log per run --------
set "LOGDIR=%~dp0logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format 'yyyyMMdd_HHmmss'"') do set "TS=%%t"
set "SESSION_LOG=%LOGDIR%\deploy_%TS%.txt"
> "%SESSION_LOG%" echo ======== androCE deploy %TS% ========

call :log "========================================"
call :log " androCE - Build, Deploy ^& Log"
call :log "========================================"
call :log "Session log: %SESSION_LOG%"
call :log "WLAN: adb-wlan.conf or build-and-deploy.bat IP:PORT"
call :log ""

REM -------- [1/4] Build --------
call :log "[1/4] Building debug APK..."
set "BUILD_TMP=%LOGDIR%\_build_%TS%.txt"
call gradlew.bat assembleDebug --no-daemon > "%BUILD_TMP%" 2>&1
set "GRADLE_ERR=!errorlevel!"
type "%BUILD_TMP%"
type "%BUILD_TMP%" >> "%SESSION_LOG%"
del "%BUILD_TMP%" 2>nul
if !GRADLE_ERR! neq 0 (
    call :log "BUILD FAILED"
    call :pull_device_logs
    exit /b 1
)

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    call :log "ERROR: APK not found at %APK%"
    call :pull_device_logs
    exit /b 1
)
call :log "BUILD OK: %APK%"
call :log ""

REM -------- [2/4] ADB --------
call :log "[2/4] ADB device..."
call :adb_ensure %* >> "%SESSION_LOG%" 2>&1
if errorlevel 1 (
    call :pull_device_logs
    exit /b 1
)
call :log ""

REM -------- [3/4] Install + launch --------
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
        call :pull_device_logs
        exit /b 1
    )
)
del "%INSTALL_TMP%" 2>nul
adb shell am start -n com.androce/.MainActivity >> "%SESSION_LOG%" 2>&1
ping 127.0.0.1 -n 3 >nul
call :log ""

REM -------- [4/4] Logcat (Ctrl+C merges device logs) --------
call :log "[4/4] Logcat (Ctrl+C to stop)..."
call :log "      All output -> %SESSION_LOG%"
call :log "      Tags: SpeedInjector, SpeedHook, DependencyInstaller, FridaSpeedHack, Setup"
call :log ""
call :stream_logs
call :pull_device_logs
call :log ""
call :log "Done. Full log: %SESSION_LOG%"
exit /b 0

:log
echo %~1
>>"%SESSION_LOG%" echo %~1
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

:stream_logs
>>"%SESSION_LOG%" echo.
>>"%SESSION_LOG%" echo ======== LOGCAT (Ctrl+C to end) ========
set "APP_PID="
for /L %%i in (1,1,15) do (
    for /f "delims=" %%p in ('adb shell pidof -s com.androce 2^>nul') do set "APP_PID=%%p"
    if defined APP_PID goto :stream_logs_pid
    ping 127.0.0.1 -n 2 >nul
)
call :log "Logcat: tag filter (app PID not found)"
powershell -NoProfile -Command "adb logcat -v time SpeedInjector:I SpeedInjector:E SpeedHook:I SpeedHook:E SpeedHook:W androCE.DependencyInstaller:I androCE.DependencyInstaller:E androCE.FridaSpeedHack:I androCE.FridaSpeedHack:E androCE.Setup:I androCE.Setup:E GlobalExceptionHandler:E AndroidRuntime:E DEBUG:F androCE.MemoryReader:E androCE.Scanner:E androCE.MemoryWriter:E androCE.ScanViewModel:E androCE.ProcessLister:E *:S 2>&1 | ForEach-Object { Write-Host $_; Add-Content -LiteralPath '%SESSION_LOG%' -Value $_ }"
goto :stream_logs_done

:stream_logs_pid
call :log "App PID: !APP_PID!"
powershell -NoProfile -Command "adb logcat -v time SpeedInjector:I SpeedInjector:E SpeedHook:I SpeedHook:E SpeedHook:W androCE.DependencyInstaller:I androCE.DependencyInstaller:E androCE.FridaSpeedHack:I androCE.FridaSpeedHack:E androCE.Setup:I androCE.Setup:E AndroidRuntime:E DEBUG:F *:S 2>&1 | ForEach-Object { Write-Host $_; Add-Content -LiteralPath '%SESSION_LOG%' -Value $_ }"

:stream_logs_done
exit /b 0

:pull_device_logs
call :log "Merging device logs..."
call :append_pulled_file "/data/local/tmp/androce/install.log" "DEVICE install.log"
call :append_pulled_file "/data/local/tmp/frida-server.log" "DEVICE frida-server.log"
call :append_pulled_shell "getenforce" "SELinux getenforce"
call :append_pulled_shell "pgrep -af frida" "frida processes"
call :append_pulled_shell "file /data/local/tmp/androce/usr/bin/frida-server" "frida-server file"
call :append_pulled_file "/sdcard/Android/data/com.androce/files/androce_log.txt" "APP androce_log.txt"
exit /b 0

:append_pulled_file
set "REMOTE_PATH=%~1"
set "SECTION_TITLE=%~2"
set "LOCAL_TMP=%LOGDIR%\_section_%RANDOM%.tmp"
adb pull "!REMOTE_PATH!" "!LOCAL_TMP!" >nul 2>&1
>>"%SESSION_LOG%" echo.
if not exist "!LOCAL_TMP!" (
    >>"%SESSION_LOG%" echo ======== %SECTION_TITLE% (not found: %REMOTE_PATH%) ========
    exit /b 0
)
>>"%SESSION_LOG%" echo ======== %SECTION_TITLE% ========
type "!LOCAL_TMP!" >>"%SESSION_LOG%"
del "!LOCAL_TMP!" 2>nul
exit /b 0

:append_pulled_shell
set "SHELL_CMD=%~1"
set "SECTION_TITLE=%~2"
>>"%SESSION_LOG%" echo.
>>"%SESSION_LOG%" echo ======== %SECTION_TITLE% ========
adb shell su -c "!SHELL_CMD!" >>"%SESSION_LOG%" 2>&1
exit /b 0
