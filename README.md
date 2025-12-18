# Sistema de IA Distribuida con Raft

Sistema distribuido de entrenamiento de modelos de IA (Perceptron y MLP) implementado con el algoritmo de consenso Raft y Federated Learning.

## Requisitos

### Software necesario
- **Java JDK 8 o superior** (para compilar y ejecutar los nodos)
- **Python 3.7+** (solo para scripts de prueba)
- **Git** (para clonar el repositorio)

### Sistemas operativos soportados
- Windows 10/11
- Linux (Ubuntu, Debian, etc.)
- macOS

## Cómo reproducir el proyecto

### 1. Clonar el repositorio
```bash
git clone <url-del-repositorio>
cd Final-Concurrente
```

### 2. Compilar los nodos java
```bash
javac -encoding UTF-8 -cp node_java/src node_java/src/*.java
```

### 3. Iniciar el cluster

**En Windows:**
```cmd
run_cluster.bat
```

**En Linux/Mac:**
```bash
chmod +x run_cluster.sh
./run_cluster.sh
```

Esto iniciará 3 nodos Raft en los puertos:
- **JavaNode1**: Puerto 6000 (Monitor web: http://localhost:8001)
- **JavaNode2**: Puerto 6001 (Monitor web: http://localhost:8002)
- **JavaNode3**: Puerto 6002 (Monitor web: http://localhost:8003)

### 4. Conectar un cliente

**Cliente GUI (Recomendado):**
```bash
java -cp node_java/src RaftDesktopClient
```

### 5. Detener el cluster

**En Windows:**
```cmd
kill_cluster.bat
```

**En Linux/Mac:**
```bash
./stop_cluster.sh
```

## Verificación de características del proyecto

### 1. **Distribución de carga (Data parallelism)**

 El líder divide el dataset y delega el entrenamiento a múltiples workers.

**Cómo verificar:**
```bash
python test_1000_records.py
```

**Evidencia esperada:**
- En los logs del **líder** se verá:
  ```
  [JavaNode1] Coordinando entrenamiento distribuido entre 2 workers...
  [JavaNode1] Delegando 500 muestras a 6001
  [JavaNode1] Delegando 500 muestras a 6002
  ```
- En los logs de los **workers** se verá:
  ```
  [JavaNode2] TRAIN_TASK recibido para: modelo_1000_records (MLP)
  [JavaNode2] Entrenando con 500 muestras...
  ```

### 2. **Federated Averaging**

Los pesos de múltiples modelos se promedian matemáticamente.

**Cómo verificar:**
- Ejecuta `test_1000_records.py` con el cluster completo
- En los logs del líder busca:
  ```
  [JavaNode1] Promediando 2 modelos...
  ```

**Código relevante:** `node_java/src/AIModel.java` - Métodos `average()` en líneas 79-80 y 200-201.

### 3. **Persistencia y replicación**

Los modelos entrenados se guardan en disco y se replican en todos los nodos.

**Cómo verificar:**
1. Entrena un modelo usando el cliente GUI
2. Verifica que se crearon archivos `raft_state_JavaNode*.json`
3. Abre los monitores web de cada nodo (http://localhost:8001, 8002, 8003)
4. Todos deben mostrar el mismo modelo en "Modelos Entrenados"
5. Reinicia un nodo - el modelo debe seguir disponible

**Evidencia esperada:**
- Archivos JSON con estado persistido
- Logs: `[JavaNode2] Modelo <nombre> sincronizado correctamente`

### 4. **Tolerancia a fallos (Fault tolerance)**

El sistema continúa funcionando aunque caigan nodos.

**Cómo verificar:**
1. Inicia el cluster completo (3 nodos)
2. Entrena un modelo con el cliente
3. Mata uno de los nodos workers (Ctrl+C en su terminal)
4. Haz predicciones - deben seguir funcionando
5. El líder automáticamente usa los workers restantes

**Evidencia esperada:**
- Logs del líder: `[JavaNode1] Delegando predicción... Predicción completada por worker 6002`
- Las predicciones funcionan aunque un worker esté caído

### 5. **Elección de Líder (Leader Election)**

Cuando el líder cae, se elige uno nuevo automáticamente.

**Cómo verificar:**
1. Identifica quién es el líder actual (en los monitores web)
2. **Mata el proceso del líder**
3. Espera 5-10 segundos
4. Verás en los logs de otro nodo:
   ```
   [JavaNode2] Timeout! Iniciando eleccion Term X
   [JavaNode2] Gane la eleccion! Ahora soy el LIDER.
   ```
5. Entrena un nuevo modelo - el nuevo líder coordinará el entrenamiento

## Monitores web

Cada nodo expone un monitor web que muestra:
- Estado actual (LEADER/FOLLOWER)
- Término de Raft actual
- Lista de peers conectados
- Modelos entrenados disponibles
- Tiempo desde último heartbeat

**URLs:**
- http://localhost:8001 (JavaNode1)
- http://localhost:8002 (JavaNode2)
- http://localhost:8003 (JavaNode3)

## Scripts de prueba

### `test_1000_records.py`
Prueba de carga que entrena un modelo con 1000 registros distribuidos entre workers.

```bash
python test_1000_records.py
```

**Salida esperada:**
```
--- Dataset de 1000 registros generado ---
Enviando entrenamiento distribuido al cluster Java...
Líder actual: puerto 6000
Tiempo total: 0.56s
✓ Input: [0, 0, 0] -> Predicción: 0.0 (Esperado: 0)
✓ Input: [1, 1, 1] -> Predicción: 1.0 (Esperado: 1)
```