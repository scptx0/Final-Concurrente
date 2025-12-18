#!/bin/bash

echo "Iniciando cluster Raft de 3 nodos Java..."
echo ""

# Compilar primero
echo "Compilando nodos Java..."
javac -encoding UTF-8 -cp node_java/src node_java/src/*.java
if [ $? -ne 0 ]; then
    echo "Error al compilar. Abortando..."
    exit 1
fi
echo "Compilación exitosa!"
echo ""

# Iniciar nodos en background
echo "Iniciando JavaNode1 (Puerto 6000)..."
java -cp node_java/src RaftNode JavaNode1 6000 6001 6002 > logs_node1.txt 2>&1 &
NODE1_PID=$!
sleep 1

echo "Iniciando JavaNode2 (Puerto 6001)..."
java -cp node_java/src RaftNode JavaNode2 6001 6000 6002 > logs_node2.txt 2>&1 &
NODE2_PID=$!
sleep 1

echo "Iniciando JavaNode3 (Puerto 6002)..."
java -cp node_java/src RaftNode JavaNode3 6002 6000 6001 > logs_node3.txt 2>&1 &
NODE3_PID=$!
sleep 1

echo ""
echo "✓ Cluster iniciado!"
echo ""
echo "PIDs de los procesos:"
echo "  - JavaNode1: $NODE1_PID"
echo "  - JavaNode2: $NODE2_PID"
echo "  - JavaNode3: $NODE3_PID"
echo ""
echo "Monitores web disponibles en:"
echo "  - http://localhost:8001 (JavaNode1)"
echo "  - http://localhost:8002 (JavaNode2)"
echo "  - http://localhost:8003 (JavaNode3)"
echo ""
echo "Logs guardados en:"
echo "  - logs_node1.txt"
echo "  - logs_node2.txt"
echo "  - logs_node3.txt"
echo ""
echo "Para conectar un cliente:"
echo "  java -cp node_java/src RaftDesktopClient"
echo ""
echo "Para detener el cluster:"
echo "  kill $NODE1_PID $NODE2_PID $NODE3_PID"
echo "  o ejecuta: ./stop_cluster.sh"
echo ""

# Guardar PIDs en archivo para poder detenerlos después
echo "$NODE1_PID" > .cluster_pids
echo "$NODE2_PID" >> .cluster_pids
echo "$NODE3_PID" >> .cluster_pids

echo "Presiona Ctrl+C para detener el monitoreo (los nodos seguirán corriendo)"
echo "Monitoreando logs (Ctrl+C para salir)..."
echo ""

# Monitorear logs en tiempo real
tail -f logs_node1.txt logs_node2.txt logs_node3.txt
