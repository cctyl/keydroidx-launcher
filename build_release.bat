@echo off
setlocal

REM ============================================================
REM  Build openRelease APK (lint INCLUDED: NewApi must pass,
REM  minSdk=19 means unguarded >19 APIs crash on Android 4.4),
REM  then open the output folder in Explorer.
REM
REM  Output: app\build\outputs\apk\open\release\
REM          KeydroidXLauncher-v*-open-release.apk
REM
REM  Usage: build_release.bat
REM ============================================================

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%app\build\outputs\apk\open\release"

echo [1/2] Building openRelease APK (may take a while) ...
call "%ROOT%gradlew.bat" assembleOpenRelease
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo [2/2] Opening output folder:
echo   %OUTDIR%
start "" "%OUTDIR%"

echo Done.
endlocal
pause
