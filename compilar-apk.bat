@echo off
title Compilando APK TotemScreen...
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\PC\.jdks\jbr-21.0.11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_BAT=C:\Users\PC\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat"
cd android
call "%GRADLE_BAT%" assembleDebug
if %ERRORLEVEL% EQU 0 (
    copy /y "app\build\outputs\apk\debug\app-debug.apk" "..\totemscreen.apk"
    echo.
    echo ========================================================
    echo  APK GERADO COM SUCESSO!
    echo  Arquivo salvo em: %~dp0totemscreen.apk
    echo ========================================================
    echo.
) else (
    echo.
    echo Ocorreu um erro ao compilar o APK.
)
pause
