@echo off
setlocal

cd /d "%~dp0"

echo ========================================
echo  Building androCE (Debug APK)
echo ========================================
gradlew.bat assembleDebug --no-daemon

if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
echo APK: app\build\outputs\apk\debug\app-debug.apk
