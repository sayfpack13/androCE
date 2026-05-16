@echo off
setlocal

cd /d "%~dp0"

echo ========================================
echo  androCE - Build ^& Deploy
echo ========================================

echo.
echo [1/3] Building debug APK...
gradlew.bat assembleDebug --no-daemon

if errorlevel 1 (
    echo.
    echo BUILD FAILED. Aborting.
    exit /b 1
)

echo.
echo [2/3] Checking ADB device...
for /f "tokens=1 delims=" %%a in ('adb devices -l ^| findstr /C:"device"') do set "device_found=1"

if not defined device_found (
    echo ERROR: No Android device connected via ADB.
    echo Make sure USB debugging is enabled and the device is plugged in.
    exit /b 1
)

echo.
echo [3/3] Installing ^& launching...
adb install -r "app\build\outputs\apk\debug\app-debug.apk"

if errorlevel 1 (
    echo Install failed ^(signature mismatch^). Uninstalling old package...
    adb uninstall com.androce
    adb install -r "app\build\outputs\apk\debug\app-debug.apk"
    if errorlevel 1 (
        echo INSTALL FAILED
        exit /b 1
    )
)

adb shell am start -n com.androce/.MainActivity

echo.
echo ========================================
echo  DONE! App built and launched.
echo ========================================
