import socket
import json
import threading
import time
import random

# Estados de Raft
FOLLOWER = "FOLLOWER"
CANDIDATE = "CANDIDATE"
LEADER = "LEADER"

class RaftNode:
    def __init__(self, node_id, port, peers):
        self.node_id = node_id
        self.port = port
        self.peers = peers # Lista de tuplas (ip, port) de los otros nodos
        
        # Estado persistente (Debería guardarse en disco, por ahora en memoria)
        self.current_term = 0
        self.voted_for = None
        self.log = []

        # Estado volátil
        self.state = FOLLOWER
        self.last_heartbeat = time.time()
        self.election_timeout = random.uniform(3.0, 5.0) 
        self.running = True
        self.lock = threading.Lock()
        self.votes_received = set()

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
        with client_sock:
            try:
                data = client_sock.recv(16384).decode('utf-8')
                if not data: return
                
                for line in data.split('\n'):
                    if not line.strip(): continue
                    try:
                        msg = json.loads(line)
                        response = self.process_message(msg)
                        if response:
                            client_sock.sendall((json.dumps(response) + "\n").encode('utf-8'))
                    except json.JSONDecodeError:
                        pass
            except Exception as e:
                pass

    def process_message(self, msg):
        msg_type = msg.get("type")
        term = msg.get("term", 0)

        with self.lock:
            # Si recibimos un término mayor, volvemos a follower
            if term > self.current_term:
                self.current_term = term
                self.state = FOLLOWER
                self.voted_for = None
            
            if msg_type == "HEARTBEAT":
                if term >= self.current_term:
                    self.current_term = term
                    self.state = FOLLOWER
                    self.last_heartbeat = time.time()
                return {"type": "HEARTBEAT_ACK", "term": self.current_term}

            elif msg_type == "VOTE_REQUEST":
                candidate_id = msg.get("candidate_id")
                can_vote = (self.voted_for is None or self.voted_for == candidate_id) and (term >= self.current_term)
                
                if can_vote:
                    self.voted_for = candidate_id
                    self.current_term = term
                    self.last_heartbeat = time.time() # Reset timer upon voting
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

            elif msg_type == "TRAIN":
                if self.state != LEADER:
                    return {"type": "TRAIN_RESULT", "status": "error", "message": "Not the leader"}
                
                # Mock de entrenamiento (Fase 3 se encargará de esto)
                model_id = msg.get("model_id")
                data = msg.get("data", [])
                loss = sum(x * x for x in data)
                self.log.append(f"Model {model_id} loss: {loss}")
                return {"type": "TRAIN_RESULT", "model_id": model_id, "status": "success", "loss": loss}

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
                s.settimeout(1.0)
                s.connect((ip, port))
                s.sendall((json.dumps(msg) + "\n").encode('utf-8'))
                # Esperar respuesta opcional
                data = s.recv(1024).decode('utf-8')
                if data:
                    return json.loads(data.split('\n')[0])
        except:
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

