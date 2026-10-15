@echo off
title LART BMS Mobile Preview Server
echo ========================================================
echo       LART BMS Mobile Preview (SolidWorks 3D CAD)
echo ========================================================
echo A iniciar servidor local para contornar restricoes CORS...
echo URL: http://localhost:8080/mobile_preview.html
echo.
echo Para fechar o servidor, fecha esta janela ou pressiona Ctrl+C.
echo.
start http://localhost:8080/mobile_preview.html
py -m http.server 8080
if %ERRORLEVEL% NEQ 0 (
    echo Python 'py' falhou, a tentar 'python'...
    python -m http.server 8080
)
pause
