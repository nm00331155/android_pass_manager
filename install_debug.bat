@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "PKG=com.securevault.app"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

echo [1/5] Checking adb command...
where adb >nul 2>nul
if errorlevel 1 (
  echo ERROR: adb command not found in PATH.
  echo        Install Android platform-tools and add adb to PATH.
  exit /b 1
)

echo [2/5] Checking connected device...
set "DEVICE_FOUND="
for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
  if "%%B"=="device" set "DEVICE_FOUND=1"
)
if not defined DEVICE_FOUND (
  echo ERROR: No online device found.
  adb devices
  echo        Connect a device, allow USB debugging, and retry.
  exit /b 1
)

echo [3/5] Building debug APK...
call gradlew.bat --no-daemon :app:assembleDebug
if errorlevel 1 (
  echo ERROR: Build failed.
  exit /b 1
)

if not exist "%APK%" (
  echo ERROR: APK not found at "%APK%".
  exit /b 1
)

echo [4/5] Installing APK...
adb shell am force-stop %PKG% >nul 2>nul
adb install -r "%APK%"
if errorlevel 1 (
  echo ERROR: Install failed.
  exit /b 1
)

echo [5/5] Launch check...
adb shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>nul
adb shell dumpsys package %PKG% | findstr /i "lastUpdateTime"

echo.
echo Done: install completed.
exit /b 0
