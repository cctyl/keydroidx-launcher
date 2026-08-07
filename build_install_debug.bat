@echo off
setlocal

REM ============================================================
REM  One-click: build openDebug APK, then install to device(s)
REM  via adb.  install_debug.py reads APK directly from the
REM  build output directory -- no stale /dist cache involved.
REM  After install, NokiaDesktopActivity is launched on every
REM  targeted device.
REM
REM  Usage:
REM    build_install_debug.bat            (build + install + launch on ALL devices)
REM    build_install_debug.bat <serial>   (build + install + launch only on given device)
REM ============================================================

set "ROOT=%~dp0"
set "COMPONENT=io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity"

echo [1/4] Building openDebug APK ...
call "%ROOT%gradlew.bat" assembleOpenDebug
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo [2/4] Installing APK to device(s) ...
set "SCRIPT=%ROOT%install_debug.py"
if not exist "%SCRIPT%" (
    echo [ERROR] install_debug.py not found next to this bat.
    pause
    exit /b 1
)

python "%SCRIPT%" %*
set "RC=%errorlevel%"

if not "%RC%"=="0" (
    echo [ERROR] Install failed. Check the per-device results above.
    pause
    exit /b %RC%
)

echo [3/4] Launching NokiaDesktopActivity on device(s) ...
if not "%~1"=="" (
    echo   - %~1
    adb -s "%~1" shell am start -n %COMPONENT%
) else (
    for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
        if "%%b"=="device" (
            echo   - %%a
            adb -s "%%a" shell am start -n %COMPONENT%
        )
    )
)

echo [4/4] Done.
endlocal
pause
