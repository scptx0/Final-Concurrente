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
        # 1. Hilo del Servidor (Escuchar mensajes)
        server_thread = threading.Thread(target=self.start_server, daemon=True)
        server_thread.start()

        # 2. Hilo del Timeout (Election Timer)
        timer_thread = threading.Thread(target=self.run_election_timer, daemon=True)
        timer_thread.start()

        print(f"[{self.node_id}] Nodo iniciado en puerto {self.port} como {self.state}")
        
        # Mantener el main vivo
        try:
            while self.running:
                time.sleep(1)
        except KeyboardInterrupt:
            print("Cerrando nodo...")

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
                print(f"[{self.node_id}] Heartbeat recibido de {leader_id} (Term {term}). Reiniciando timeout.")
            
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
        print(f"[{self.node_id}] (Demo) Volviendo a FOLLOWER para esperar latidos.")
        self.state = FOLLOWER

if __name__ == "__main__":
    # Configuración de ejemplo: Soy el nodo Python en puerto 5000
    # Mis peers (el nodo Java) estaría en el 6000
    peers = [('127.0.0.1', 6000)]
    node = RaftNode("PythonNode", 5000, peers)
    node.start()
