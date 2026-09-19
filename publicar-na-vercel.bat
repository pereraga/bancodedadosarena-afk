@echo off
title Publicar Totem Central na Vercel (pereraga)
cls
echo ========================================================
echo   PUBLICANDO TOTEM CENTRAL NA VERCEL - CONTA PERERAGA
echo ========================================================
echo.
cd /d "%~dp0"

echo [1/2] Autenticando com sua conta Vercel...
call vercel login

echo.
echo [2/2] Realizando o Deploy da Totem Central...
call vercel --prod --yes

echo.
echo ========================================================
echo   DEPLOY CONCLUIDO COM SUCESSO!
echo ========================================================
echo.
pause
