@echo off
title Totem Play - Servidor em Tempo Real
cls
echo ========================================================
echo   INICIANDO O TOTEM PLAY (VIDEO EM TEMPO REAL)
echo ========================================================
echo.
cd /d "%~dp0"
node server-local.js
pause
