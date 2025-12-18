#!/bin/bash

echo "Deteniendo cluster Raft..."

if [ -f .cluster_pids ]; then
    while read pid; do
        if ps -p $pid > /dev/null 2>&1; then
            echo "Deteniendo proceso $pid..."
            kill $pid
        else
            echo "Proceso $pid ya no está corriendo"
        fi
    done < .cluster_pids
    
    rm .cluster_pids
    echo "✓ Cluster detenido"
else
    echo "No se encontró archivo .cluster_pids"
    echo "Intentando detener todos los procesos java RaftNode..."
    pkill -f "RaftNode"
    echo "✓ Procesos detenidos"
fi

echo ""
echo "Limpiando archivos de logs..."
rm -f logs_node*.txt
echo "✓ Logs eliminados"
