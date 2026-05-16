@echo off
setlocal

cd /d "%~dp0"

echo ========================================
echo  Deploying to connected ADB device
echo ========================================

for /f "tokens=1 delims=" %%a in ('adb devices -l ^| findstr /C:"device"') do set "device_found=1"

if not defined device_found (
    echo ERROR: No Android device connected via ADB.
    echo Make sure USB debugging is enabled and the device is plugged in.
    exit /b 1
)

echo.
echo Installing APK...
adb install -r "app\build\outputs\apk\debug\app-debug.apk"

if errorlevel 1 (
    echo.
    echo Install failed ^(signature mismatch or existing package^).
    echo Uninstalling old package and retrying...
    adb uninstall com.androce
    adb install -r "app\build\outputs\apk\debug\app-debug.apk"
    if errorlevel 1 (
        echo INSTALL FAILED
        exit /b 1
    )
)

echo.
echo Launching app...
adb shell am start -n com.androce/.MainActivity

echo.
echo DEPLOY COMPLETE
