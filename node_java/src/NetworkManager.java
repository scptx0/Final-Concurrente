import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class NetworkManager {
    private int port;

    public NetworkManager(int port) {
        this.port = port;
    }

    public void startServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("[Java] Servidor escuchando en puerto " + port);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try (
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                System.out.println("[Java] Recibido: " + inputLine);
                if (inputLine.contains("Hola")) {
                    String jsonResponse = "{\"status\": \"ok\", \"reply\": \"Hola desde Java\"}";
                    out.println(jsonResponse);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendMessage(String host, int port, String jsonMessage) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("[Java Client] Enviando: " + jsonMessage);
            out.println(jsonMessage);

            String response = in.readLine();
            System.out.println("[Java Client] Respuesta: " + response);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Ejecutar como cliente que llama a Python
        try {
            System.out.println("[Java] Intentando conectar al nodo Python en localhost:5000...");
            sendMessage("127.0.0.1", 5000, "{\"sender\": \"Java_Node\", \"content\": \"Hola Python, soy Java\"}");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
