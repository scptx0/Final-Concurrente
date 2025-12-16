import socket
import json
import threading

class NetworkManager:
    def __init__(self, host='0.0.0.0', port=5000):
        self.host = host
        self.port = port
        self.running = False

    def start_server(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(5)
        self.running = True
        print(f"[Python] Servidor escuchando en {self.host}:{self.port}")
        
        while self.running:
            try:
                client_sock, address = self.server_socket.accept()
                print(f"[Python] Conexión aceptada de {address}")
                client_handler = threading.Thread(
                    target=self.handle_client,
                    args=(client_sock,)
                )
                client_handler.start()
            except Exception as e:
                if self.running:
                    print(f"[Python] Error aceptando conexión: {e}")

    def handle_client(self, client_socket):
        with client_socket:
            while True:
                try:
                    # Leemos datos. Asumimos que los mensajes terminan en \n
                    data = ""
                    chunk = client_socket.recv(1024).decode('utf-8')
                    if not chunk:
                        break
                    data += chunk
                    
                    if "\n" in data:
                        for line in data.split("\n"):
                            if line.strip():
                                try:
                                    message = json.loads(line)
                                    print(f"[Python] Recibido JSON: {message}")
                                    # Responder
                                    response = {"status": "ok", "reply": "Hola desde Python"}
                                    client_socket.sendall((json.dumps(response) + "\n").encode('utf-8'))
                                except json.JSONDecodeError:
                                    print(f"[Python] Error decodificando linea: {line}")
                except Exception as e:
                    print(f"[Python] Error manejando cliente: {e}")
                    break
        print(f"[Python] Cliente desconectado")

if __name__ == "__main__":
    nm = NetworkManager()
    nm.start_server()
