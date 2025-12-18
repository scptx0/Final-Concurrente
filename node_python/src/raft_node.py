import socket
import json
import threading
import time
import random
import math

# Estados de Raft
FOLLOWER = "FOLLOWER"
CANDIDATE = "CANDIDATE"
LEADER = "LEADER"

class RaftNode:
    def __init__(self, node_id, port, peers):
        self.node_id = node_id
        self.port = port
        self.peers = peers # Lista de tuplas (ip, port) de los otros nodos
        self.state_file = f"raft_state_{self.node_id}.json"
        
        # Estado persistente (Ahora se carga desde disco)
        self.current_term = 0
        self.voted_for = None
        self.log = []
        self.models = {} # Meta-información de modelos para el monitor
        self.load_state()

        # Estado volátil
        self.state = FOLLOWER
        self.last_heartbeat = time.time()
        self.election_timeout = random.uniform(3.0, 5.0) 
        self.running = True
        self.lock = threading.Lock()
        self.votes_received = set()

    def save_state(self):
        """Guarda el estado persistente en disco."""
        state = {
            "current_term": self.current_term,
            "voted_for": self.voted_for,
            "log": self.log,
            "models": self.models
        }
        try:
            with open(self.state_file, "w") as f:
                json.dump(state, f)
        except Exception as e:
            print(f"Error guardando estado: {e}")

    def load_state(self):
        """Carga el estado persistente desde disco."""
        try:
            import os
            if os.path.exists(self.state_file):
                with open(self.state_file, "r") as f:
                    state = json.load(f)
                    self.current_term = state.get("current_term", 0)
                    self.voted_for = state.get("voted_for")
                    self.log = state.get("log", [])
                    self.models = state.get("models", {})
                print(f"[{self.node_id}] Estado cargado: Term {self.current_term}, Votado por {self.voted_for}, Log {len(self.log)} items")
        except Exception as e:
            print(f"Error cargando estado: {e}")

    def start(self):
        # 1. Hilo del Servidor Raft
        server_thread = threading.Thread(target=self.start_server, daemon=True)
        server_thread.start()

        # 2. Hilo del Timeout (Election Timer)
        timer_thread = threading.Thread(target=self.run_election_timer, daemon=True)
        timer_thread.start()

        # 3. Hilo del Servidor Web (Monitor)
        self.web_port = self.port + 3000
        web_thread = threading.Thread(target=self.start_web_monitor, daemon=True)
        web_thread.start()

        # 4. Hilo del Lider (Solo activo si es LIDER)
        leader_thread = threading.Thread(target=self.run_leader_loop, daemon=True)
        leader_thread.start()

        print(f"[{self.node_id}] Nodo iniciado. Raft-Port: {self.port}, Web-Port: {self.web_port}, Estado: {self.state}")
        
        try:
            while self.running:
                time.sleep(1)
        except KeyboardInterrupt:
            print("Cerrando nodo...")

    def start_web_monitor(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('0.0.0.0', self.web_port))
            s.listen()
            while self.running:
                try:
                    client, addr = s.accept()
                    request = client.recv(1024).decode('utf-8')
                    
                    with self.lock:
                        html_content = f"""
                        <html>
                        <head>
                            <meta http-equiv="refresh" content="2">
                            <style>
                                body {{ font-family: monospace; background: #222; color: #0f0; padding: 20px; }}
                                .card {{ border: 1px solid #444; padding: 20px; border-radius: 8px; max-width: 600px; }}
                                h1 {{ color: #fff; }}
                                .state {{ font-size: 2em; font-weight: bold; }}
                                .leader {{ color: #f00; }}
                                .follower {{ color: #0f0; }}
                                .candidate {{ color: #ff0; }}
                            </style>
                        </head>
                        <body>
                            <div class="card">
                                <h1>Monitor nodo: {self.node_id}</h1>
                                <p>Estado actual:</p>
                                <div class="state {self.state.lower()}">{self.state}</div>
                                <hr>
                                <p>Termino actual: {self.current_term}</p>
                                <p>Voto por: {self.voted_for}</p>
                                <p>{ "Latido enviado hace" if self.state == LEADER else "Ultimo latido recibido hace" }: {time.time() - self.last_heartbeat:.2f}s</p>
                                <p>Peers: {self.peers}</p>
                                <h3>Log de entradas:</h3>
                                <pre>{json.dumps(self.log, indent=2)}</pre>
                            </div>
                        </body>
                        </html>
                        """
                    
                    response = (
                        "HTTP/1.1 200 OK\r\n"
                        "Content-Type: text/html\r\n"
                        f"Content-Length: {len(html_content)}\r\n"
                        "Connection: close\r\n\r\n"
                        + html_content
                    )
                    client.sendall(response.encode('utf-8'))
                    client.close()
                except Exception as e:
                    pass

    def start_server(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('0.0.0.0', self.port))
            s.listen()
            while self.running:
                client, addr = s.accept()
                threading.Thread(target=self.handle_client, args=(client,)).start()

    def handle_client(self, client_sock):
        addr = client_sock.getpeername()
        try:
            with client_sock:
                buffer = ""
                while True:
                    try:
                        chunk = client_sock.recv(16384).decode('utf-8')
                        if not chunk: break
                        buffer += chunk
                        if "\n" in buffer: break
                    except socket.timeout:
                        break
                
                if not buffer: return
                
                for line in buffer.split('\n'):
                    if not line.strip(): continue
                    try:
                        msg = json.loads(line)
                        response = self.process_message(msg)
                        if response:
                            client_sock.sendall((json.dumps(response) + "\n").encode('utf-8'))
                        else:
                            # Enviar error por defecto para que el cliente no se quede esperando
                            err = {"status": "error", "message": "No response from node"}
                            client_sock.sendall((json.dumps(err) + "\n").encode('utf-8'))
                    except json.JSONDecodeError as e:
                        print(f"[{self.node_id}] JSON Error: {e}")
                        err = {"status": "error", "message": f"Invalid JSON: {e}"}
                        client_sock.sendall((json.dumps(err) + "\n").encode('utf-8'))
        except Exception as e:
            print(f"[{self.node_id}] Error handling client {addr}: {e}")

    def process_message(self, msg):
        msg_type = msg.get("type")
        term = msg.get("term", 0)

        with self.lock:
            # Si recibimos un término mayor, volvemos a follower
            if term > self.current_term:
                self.current_term = term
                self.state = FOLLOWER
                self.voted_for = None
                self.save_state()
            
            if msg_type == "HEARTBEAT":
                if term >= self.current_term:
                    if term > self.current_term:
                        self.current_term = term
                        self.save_state()
                    self.state = FOLLOWER
                    self.last_heartbeat = time.time()
                return {"type": "HEARTBEAT_ACK", "term": self.current_term}

            elif msg_type == "TRAIN":
                if self.state != LEADER:
                    return {"type": "TRAIN_RESULT", "status": "error", "message": "Not the leader"}
                
                model_id = msg.get("model_id")
                model_type = msg.get("model_type", "PERCEPTRON")
                inputs = msg.get("inputs")
                targets = msg.get("targets")

                if isinstance(inputs, str): inputs = json.loads(inputs)
                if isinstance(targets, str): targets = json.loads(targets)

                # DISTRIBUIR CARGA (Paralelismo de Datos)
                print(f"[{self.node_id}] Distribuyendo entrenamiento {model_id} entre peers...")
                
                java_peers = [p for p in self.peers]
                if not java_peers:
                    return {"type": "TRAIN_RESULT", "status": "error", "message": "No workers available"}

                chunk_size = math.ceil(len(inputs) / len(java_peers))
                weights_to_average = []
                threads = []
                lock = threading.Lock()

                def send_task(p_ip, p_port, sub_in, sub_tar):
                    java_task = {
                        "type": "TRAIN_TASK",
                        "model_id": model_id,
                        "model_type": model_type,
                        "inputs": json.dumps(sub_in),
                        "targets": json.dumps(sub_tar)
                    }
                    resp = self.send_json(p_ip, p_port, java_task)
                    if resp and resp.get("weights"):
                        with lock:
                            weights_to_average.append(resp.get("weights"))

                for i, (p_ip, p_port) in enumerate(java_peers):
                    start = i * chunk_size
                    if start >= len(inputs): break
                    end = min(start + chunk_size, len(inputs))
                    
                    t = threading.Thread(target=send_task, args=(p_ip, p_port, inputs[start:end], targets[start:end]))
                    threads.append(t)
                    t.start()

                for t in threads: t.join() # Esperar a que todos terminen

                # Para simplificar en Python (que no tiene AIModel), delegamos la agregación al primer worker
                # o simplemente replicamos el promedio si lo hiciéramos aquí. 
                # Por ahora, haremos que el MODEL_SYNC final lo haga el sistema.
                # En un sistema real, Python promediaría pesos. Aquí, enviaremos un SYNC final.
                
                if weights_to_average:
                    # En vez de promediar en Python, enviaremos los pesos del primer worker como 'ganador'
                    final_weights = weights_to_average[0] 
                    sync_msg = {
                        "type": "MODEL_SYNC",
                        "model_id": model_id,
                        "model_type": model_type,
                        "weights": final_weights
                    }
                    print(f"[{self.node_id}] Sincronizando modelo final con el cluster...")
                    for p_ip, p_port in self.peers:
                        self.send_json(p_ip, p_port, sync_msg)

                self.log.append(f"TRAINED_DISTRIBUTED: {model_id}")
                self.models[model_id] = {"type": model_type}
                self.save_state()
                return {"type": "TRAIN_RESULT", "model_id": model_id, "status": "success"}

            elif msg_type == "PREDICT":
                # Delegar predicción al primer peer disponible (Java)
                model_id = msg.get("model_id")
                data = msg.get("data")
                print(f"[{self.node_id}] Delegando predicción de {model_id}...")
                
                predict_task = {"type": "PREDICT", "model_id": model_id, "data": data}
                for p_ip, p_port in self.peers:
                    resp = self.send_json(p_ip, p_port, predict_task)
                    if resp: return resp
                return {"type": "PREDICT_RESULT", "status": "error", "message": "No workers responded"}

            elif msg_type == "MODEL_SYNC":
                mid = msg.get("model_id")
                mtype = msg.get("model_type")
                self.models[mid] = {"type": mtype}
                self.log.append(f"SYNC: {mid}")
                self.save_state()
                return {"type": "SYNC_ACK"}

            elif msg_type == "VOTE_REQUEST":
                candidate_id = msg.get("candidate_id")
                can_vote = (self.voted_for is None or self.voted_for == candidate_id) and (term >= self.current_term)
                
                if can_vote:
                    self.voted_for = candidate_id
                    self.current_term = term
                    self.last_heartbeat = time.time() # Reset timer upon voting
                    self.save_state()
                    return {"type": "VOTE_RESPONSE", "term": self.current_term, "vote_granted": True}
                else:
                    return {"type": "VOTE_RESPONSE", "term": self.current_term, "vote_granted": False}

            elif msg_type == "VOTE_RESPONSE":
                if self.state == CANDIDATE and term == self.current_term:
                    if msg.get("vote_granted"):
                        # Usamos el set de IDs para no contar dos veces si hay reintentos
                        # (Aunque en sockets TCP es raro, es buena practica)
                        # Nota: msg no trae id por defecto en la respuesta, lo asumimos del flujo o lo agregamos
                        pass 
                return None

        return None

    def run_election_timer(self):
        while self.running:
            time.sleep(0.5)
            with self.lock:
                if self.state != LEADER:
                    elapsed = time.time() - self.last_heartbeat
                    if elapsed > self.election_timeout:
                        print(f"[{self.node_id}] Timeout! Iniciando eleccion para Term {self.current_term + 1}")
                        self.start_election()

    def start_election(self):
        self.state = CANDIDATE
        self.current_term += 1
        self.voted_for = self.node_id
        self.votes_received = {self.node_id}
        self.last_heartbeat = time.time()
        self.election_timeout = random.uniform(3.0, 5.0)
        self.save_state()

        # Enviar VOTE_REQUEST a todos los peers
        for peer_ip, peer_port in self.peers:
            threading.Thread(target=self.request_vote_from_peer, args=(peer_ip, peer_port)).start()

    def request_vote_from_peer(self, ip, port):
        msg = {
            "type": "VOTE_REQUEST",
            "term": self.current_term,
            "candidate_id": self.node_id
        }
        response = self.send_json(ip, port, msg)
        if response and response.get("type") == "VOTE_RESPONSE":
            with self.lock:
                if self.state == CANDIDATE and response.get("term") == self.current_term:
                    if response.get("vote_granted"):
                        # Como no enviamos ID en la respuesta, simplemente contamos votos únicos por conexión
                        # Para ser más exacto, el mensaje de respuesta debería incluir el peer_id
                        self.votes_received.add(f"{ip}:{port}")
                        
                        if len(self.votes_received) > (len(self.peers) + 1) / 2:
                            print(f"[{self.node_id}] Gane la eleccion! Ahora soy el LIDER.")
                            self.state = LEADER

    def run_leader_loop(self):
        while self.running:
            if self.state == LEADER:
                with self.lock:
                    self.last_heartbeat = time.time() # El lider reinicia su propio contador al enviar
                # Enviar Heartbeats
                for peer_ip, peer_port in self.peers:
                    threading.Thread(target=self.send_heartbeat, args=(peer_ip, peer_port)).start()
            time.sleep(2)

    def send_heartbeat(self, ip, port):
        msg = {
            "type": "HEARTBEAT",
            "term": self.current_term,
            "leader_id": self.node_id
        }
        self.send_json(ip, port, msg)

    def send_json(self, ip, port, msg):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(15.0) # Mayor timeout para Big Data
                s.connect((ip, port))
                s.sendall((json.dumps(msg) + "\n").encode('utf-8'))
                
                buffer = ""
                while True:
                    chunk = s.recv(8192).decode('utf-8')
                    if not chunk: break
                    buffer += chunk
                    if "\n" in buffer: break
                
                if buffer:
                    return json.loads(buffer.split('\n')[0])
        except Exception as e:
            print(f"[{self.node_id}] Connection error with {port}: {e}")
            return None
        return None

if __name__ == "__main__":
    # Configuración para pruebas locales
    import sys
    node_id = sys.argv[1] if len(sys.argv) > 1 else "PythonNode_1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5000
    
    # Peers: Si corremos varios en local, pasamos los otros puertos
    peers = []
    if len(sys.argv) > 3:
        for p in sys.argv[3:]:
            peers.append(('127.0.0.1', int(p)))
    else:
        # Por defecto asume que hay un JavaNode en 6000
        peers = [('127.0.0.1', 6000)]
        
    node = RaftNode(node_id, port, peers)
    node.start()

