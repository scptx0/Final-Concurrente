import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class RaftClient {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Raft AI Client ---");
        System.out.print("Conectar a (IP): ");
        String host = scanner.nextLine();
        if (host.isEmpty())
            host = "127.0.0.1";
        System.out.print("Puerto: ");
        int port = Integer.parseInt(scanner.nextLine());

        while (true) {
            System.out.println("\nSeleccione operacion:");
            System.out.println("1. Entrenar nuevo modelo (OR Gate)");
            System.out.println("2. Predecir con modelo existente");
            System.out.println("3. Salir");
            String choice = scanner.nextLine();

            if ("1".equals(choice)) {
                System.out.print("ID del modelo: ");
                String id = scanner.nextLine();
                System.out.print("Tipo (PERCEPTRON/MLP): ");
                String type = scanner.nextLine().toUpperCase();

                String msg = "{\"type\": \"TRAIN\", \"model_id\": \"" + id + "\", \"model_type\": \"" + type + "\"}";
                sendRequest(host, port, msg);
            } else if ("2".equals(choice)) {
                System.out.print("ID del modelo: ");
                String id = scanner.nextLine();
                System.out.print("Input data (ej: [1, 0]): ");
                String data = scanner.nextLine();

                String msg = "{\"type\": \"PREDICT\", \"model_id\": \"" + id + "\", \"data\": \"" + data + "\"}";
                sendRequest(host, port, msg);
            } else if ("3".equals(choice)) {
                break;
            }
        }
        scanner.close();
    }

    private static void sendRequest(String host, int port, String json) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println(">> Enviando: " + json);
            out.println(json);
            String response = in.readLine();
            System.out.println("<< Recibido: " + response);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
