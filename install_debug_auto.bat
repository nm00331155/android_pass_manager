@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "PKG=com.securevault.app"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "INSTALL_LOG=%TEMP%\securevault_install.log"

echo [1/6] Checking adb command...
where adb >nul 2>nul
if errorlevel 1 (
  echo ERROR: adb command not found in PATH.
  echo        Install Android platform-tools and add adb to PATH.
  exit /b 1
)

echo [2/6] Checking connected device...
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

echo [3/6] Building debug APK...
call gradlew.bat --no-daemon :app:assembleDebug
if errorlevel 1 (
  echo ERROR: Build failed.
  exit /b 1
)

if not exist "%APK%" (
  echo ERROR: APK not found at "%APK%".
  exit /b 1
)

echo [4/6] Installing APK...
adb shell am force-stop %PKG% >nul 2>nul
adb install -r "%APK%" > "%INSTALL_LOG%" 2>&1
type "%INSTALL_LOG%"

findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%INSTALL_LOG%" >nul
if not errorlevel 1 (
  echo INFO: Signature mismatch detected. Uninstalling old app...
  adb uninstall %PKG% > "%INSTALL_LOG%" 2>&1
  type "%INSTALL_LOG%"
  echo INFO: Reinstalling debug APK...
  adb install "%APK%" > "%INSTALL_LOG%" 2>&1
  type "%INSTALL_LOG%"
)

findstr /C:"Success" "%INSTALL_LOG%" >nul
if errorlevel 1 (
  echo ERROR: Install failed.
  del "%INSTALL_LOG%" >nul 2>nul
  exit /b 1
)

echo [5/6] Launch check...
adb shell am start -n %PKG%/%PKG%.ui.MainActivity >nul 2>nul
if errorlevel 1 (
  echo WARN: Direct activity launch failed. Trying launcher intent...
  adb shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>nul
)

echo [6/6] Package update time...
adb shell dumpsys package %PKG% | findstr /i "lastUpdateTime"

del "%INSTALL_LOG%" >nul 2>nul
echo.
echo Done: install completed.
exit /b 0
