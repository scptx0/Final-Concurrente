@echo off
echo Iniciando cluster Raft de 3 nodos Java...
echo.

start cmd /k "title JavaNode1 (Port 6000) && java -cp node_java/src RaftNode JavaNode1 6000 6001 6002"
timeout /t 1 >nul

start cmd /k "title JavaNode2 (Port 6001) && java -cp node_java/src RaftNode JavaNode2 6001 6000 6002"
timeout /t 1 >nul

start cmd /k "title JavaNode3 (Port 6002) && java -cp node_java/src RaftNode JavaNode3 6002 6000 6001"

echo.
echo Cluster iniciado!
echo Monitores web disponibles en:
echo   - http://localhost:8001 (JavaNode1)
echo   - http://localhost:8002 (JavaNode2)
echo   - http://localhost:8003 (JavaNode3)
echo.
echo Para conectar un cliente:
echo   java -cp node_java/src RaftDesktopClient
