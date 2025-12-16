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
        
        # Estado persistente
        self.current_term = 0
        self.voted_for = None
        self.log = []

        # Estado volatil
        self.state = FOLLOWER
        self.last_heartbeat = time.time()
        # Timeout aleatorio entre 3 y 5 segundos para pruebas visuales (en prod es ms)
        self.election_timeout = random.uniform(3.0, 5.0) 
        self.running = True

    def start(self):
        # 1. Hilo del Servidor Raft (Escuchar mensajes de otros nodos)
        server_thread = threading.Thread(target=self.start_server, daemon=True)
        server_thread.start()

        # 2. Hilo del Timeout (Election Timer)
        timer_thread = threading.Thread(target=self.run_election_timer, daemon=True)
        timer_thread.start()

        # 3. Hilo del Servidor Web (Monitor)
        # Usaremos el puerto del nodo + 1000 para el web (ej: 5000 -> 6000, pero mejor definimos uno fijo para probar)
        self.web_port = self.port + 3000 # Ej: 5000 -> 8000
        web_thread = threading.Thread(target=self.start_web_monitor, daemon=True)
        web_thread.start()

        print(f"[{self.node_id}] Nodo iniciado. Raft-Port: {self.port}, Web-Port: {self.web_port}, Estado: {self.state}")
        
        # Mantener el main vivo
        try:
            while self.running:
                time.sleep(1)
        except KeyboardInterrupt:
            print("Cerrando nodo...")

    def start_web_monitor(self):
        """
        Servidor HTTP ultra-básico hecho con sockets puros para cumplir requisitos.
        Solo responde a GET / con un HTML de estado.
        """
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('0.0.0.0', self.web_port))
            s.listen()
            print(f"[{self.node_id}] Monitor Web escuchando en http://localhost:{self.web_port}")
            
            while self.running:
                try:
                    client, addr = s.accept()
                    # Leemos la petición (aunque no nos importa mucho qué pide, siempre damos el status)
                    request = client.recv(1024).decode('utf-8')
                    
                    # Generamos HTML dinámica
                    html_content = f"""
                    <html>
                    <head>
                        <meta http-equiv="refresh" content="2"> <!-- Auto-reload cada 2s -->
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
                            <p>Ultimo latido hace: {time.time() - self.last_heartbeat:.2f}s</p>
                            <p>Peers: {self.peers}</p>
                            <h3>Log de entradas:</h3>
                            <pre>{json.dumps(self.log, indent=2)}</pre>
                        </div>
                    </body>
                    </html>
                    """
                    
                    # Headers HTTP/1.1 para que el navegador entienda
                    response = (
                        "HTTP/1.1 200 OK\r\n"
                        "Content-Type: text/html\r\n"
                        f"Content-Length: {len(html_content)}\r\n"
                        "Connection: close\r\n"
                        "\r\n"
                        + html_content
                    )
                    
                    client.sendall(response.encode('utf-8'))
                    client.close()
                except Exception as e:
                    print(f"Error en monitor web: {e}")

    def start_server(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(('0.0.0.0', self.port))
            s.listen()
            while self.running:
                client, addr = s.accept()
                threading.Thread(target=self.handle_client, args=(client,)).start()

    def handle_client(self, client_sock):
        with client_sock:
            try:
                data = client_sock.recv(4096).decode('utf-8')
                if not data: return
                
                # Manejar multiples mensajes pegados si es necesario (simple split por now)
                for line in data.split('\n'):
                    if not line.strip(): continue
                    msg = json.loads(line)
                    self.process_message(msg)
            except Exception as e:
                print(f"Error recibiendo datos: {e}")

    def process_message(self, msg):
        msg_type = msg.get("type")
        
        if msg_type == "HEARTBEAT":
            term = msg.get("term")
            leader_id = msg.get("leader_id")
            
            if term >= self.current_term:
                self.current_term = term
                self.state = FOLLOWER
                self.last_heartbeat = time.time()
                # print(f"[{self.node_id}] Heartbeat recibido de {leader_id} (Term {term}).") 
                # Comentamos el print para no ensuciar tanto la consola
                pass
            
        elif msg_type == "VOTE_REQUEST":
            print(f"[{self.node_id}] Peticion de voto recibida (Aun no implementado)")

    def run_election_timer(self):
        while self.running:
            if self.state == FOLLOWER:
                elapsed = time.time() - self.last_heartbeat
                if elapsed > self.election_timeout:
                    print(f"[{self.node_id}] TIMEOUT! El lider ha muerto. Iniciando eleccion...")
                    self.start_election()
            time.sleep(0.5) # Chequeo cada 500ms

    def start_election(self):
        self.state = CANDIDATE
        self.current_term += 1
        self.voted_for = self.node_id
        self.last_heartbeat = time.time() # Reiniciar timer para evitar split vote inmediato
        print(f"[{self.node_id}] Convirtiendose en CANDIDATO (Term {self.current_term})")
        # Aqui enviariamos RequestVote a self.peers
        # Por ahora, volvemos a follower para no spammear en esta demo
        time.sleep(2) 
        # print(f"[{self.node_id}] (Demo) Volviendo a FOLLOWER para esperar latidos.")
        self.state = FOLLOWER

if __name__ == "__main__":
    # Configuración: Python en 5000 (Raft) -> Monitor en 8000
    peers = [('127.0.0.1', 6000)]
    node = RaftNode("PythonNode", 5000, peers)
    node.start()
