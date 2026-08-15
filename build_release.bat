@echo off
setlocal

REM ============================================================
REM  Build openRelease APK (skip lint, as lint breaks the
REM  release build), then open the output folder in Explorer.
REM
REM  Output: app\build\outputs\apk\open\release\
REM          J2ME_Loader-*-open-release.apk
REM
REM  Usage: build_release.bat
REM ============================================================

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%app\build\outputs\apk\open\release"

echo [1/2] Building openRelease APK (may take a while) ...
call "%ROOT%gradlew.bat" assembleOpenRelease -x lint
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
