import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RaftNode {
    private String nodeId;
    private int port;
    private int peerPort; // Puerto del otro servidor (Python)
    private String state = "FOLLOWER";
    private int currentTerm = 0;

    public RaftNode(String nodeId, int port, int peerPort) {
        this.nodeId = nodeId;
        this.port = port;
        this.peerPort = peerPort;
    }

    public void start() {
        // 1. Iniciar Servidor (Escucha)
        new Thread(this::startServer).start();

        // 2. Demo: Forzar este nodo a ser LIDER para probar Heartbeats
        // En la vida real, esto pasaría tras una elección.
        System.out.println("[" + nodeId + "] Iniciando. Me autoproclamo LEADER para la demo.");
        this.state = "LEADER";
        this.currentTerm = 1;

        startHeartbeatSender();
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[" + nodeId + "] Escuchando en puerto " + port);
            while (true) {
                Socket client = serverSocket.accept();
                // Manejar conexión rapida sin hilos para no complicar el demo
                handleClient(client);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line = in.readLine();
            if (line != null) {
                System.out.println("[" + nodeId + "] Recibido: " + line);
                // Aquí procesaríamos votos o latidos si fueramos Follower
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startHeartbeatSender() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            if (this.state.equals("LEADER")) {
                sendHeartbeat();
            }
        }, 0, 2, TimeUnit.SECONDS); // Enviar cada 2 segundos
    }

    private void sendHeartbeat() {
        String json = String.format("{\"type\": \"HEARTBEAT\", \"term\": %d, \"leader_id\": \"%s\"}",
                currentTerm, nodeId);
        System.out.println("[" + nodeId + "] Enviando Heartbeat -> Puerto " + peerPort);
        sendMessage("127.0.0.1", peerPort, json);
    }

    public static void sendMessage(String host, int port, String json) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(json);
        } catch (Exception e) {
            // Es normal que falle si el otro nodo no está levantado
            System.out.println("Error enviando a " + host + ":" + port + " - " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Config: Java en 6000, manda a Python en 5000
        RaftNode node = new RaftNode("JavaNode", 6000, 5000);
        node.start();
    }
}
