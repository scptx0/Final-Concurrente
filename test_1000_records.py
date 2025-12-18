import socket
import json
import time
import random

# Puertos del cluster Java
CLUSTER_PORTS = [6000, 6001, 6002]

def send_msg(port, msg):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(30.0) # Entrenar 1000 registros puede tardar
            s.connect(("127.0.0.1", port))
            
            payload = json.dumps(msg) + "\n"
            s.sendall(payload.encode('utf-8'))
            
            # Recibir respuesta
            buffer = b""
            while True:
                chunk = s.recv(16384)
                if not chunk: break
                buffer += chunk
                if b"\n" in buffer: break
            
            if not buffer:
                return {"status": "error", "message": "Servidor cerró conexión sin responder"}
                
            return json.loads(buffer.decode('utf-8').split('\n')[0])
    except Exception as e:
        return {"status": "error", "message": f"Error de red: {str(e)}"}

def send_to_leader(msg):
    """Intenta enviar mensaje al líder, probando todos los nodos del cluster"""
    for port in CLUSTER_PORTS:
        resp = send_msg(port, msg)
        if resp.get('status') != 'error' or 'Not the leader' not in resp.get('message', ''):
            return resp, port
    return {"status": "error", "message": "No leader found in cluster"}, None

# 1. Generar un dataset de 1000 registros (Paridad de 3 bits con ruido)
inputs = []
targets = []
for _ in range(1000):
    b1 = random.randint(0, 1)
    b2 = random.randint(0, 1)
    b3 = random.randint(0, 1)
    inputs.append([float(b1), float(b2), float(b3)])
    targets.append(float((b1 + b2 + b3) % 2))

print(f"--- Dataset de {len(inputs)} registros generado ---")

# 2. Enviar entrenamiento al cluster
train_msg = {
    "type": "TRAIN",
    "model_id": "modelo_1000_records",
    "model_type": "MLP",
    "inputs": inputs,
    "targets": targets
}

print("Enviando entrenamiento distribuido al cluster Java...")
start_time = time.time()
res, leader_port = send_to_leader(train_msg)
end_time = time.time()

print(f"Respuesta del cluster: {res}")
if leader_port:
    print(f"Líder actual: puerto {leader_port}")
print(f"Tiempo total: {end_time - start_time:.2f}s")

# 3. Validar con algunas predicciones
if res.get("status") == "success":
    print("\n--- Validando predicciones ---")
    tests = [[0,0,0], [1,1,1], [1,0,1], [0,1,0]]
    for t in tests:
        pred_msg = {"type": "PREDICT", "model_id": "modelo_1000_records", "data": str(t)}
        pres, _ = send_to_leader(pred_msg)
        expected = sum(t) % 2
        prediction = pres.get('prediction', 'N/A')
        status = "✓" if (prediction == 1.0 and expected == 1) or (prediction == 0.0 and expected == 0) else "✗"
        print(f"{status} Input: {t} -> Predicción: {prediction} (Esperado: {expected})")
else:
    print(f"\n❌ Error en entrenamiento: {res.get('message', 'Unknown error')}")
