@echo off
echo Deteniendo cluster Raft...
echo.

taskkill /F /FI "WINDOWTITLE eq JavaNode1*" >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [OK] JavaNode1 detenido
) else (
    echo [--] JavaNode1 no estaba corriendo
)

taskkill /F /FI "WINDOWTITLE eq JavaNode2*" >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [OK] JavaNode2 detenido
) else (
    echo [--] JavaNode2 no estaba corriendo
)

taskkill /F /FI "WINDOWTITLE eq JavaNode3*" >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [OK] JavaNode3 detenido
) else (
    echo [--] JavaNode3 no estaba corriendo
)

echo.
echo Cluster detenido!
echo.
pause
