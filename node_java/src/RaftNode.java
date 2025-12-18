import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RaftNode {
    private String nodeId;
    private int port;
    private List<Peer> peers;
    private String state = "FOLLOWER";
    private int currentTerm = 0;
    private String votedFor = null;
    private long lastHeartbeat;
    private long electionTimeout;
    private boolean running = true;
    private List<String> votesReceived = new ArrayList<>();
    private List<String> log = new ArrayList<>();

    private static class Peer {
        String host;
        int port;

        Peer(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public RaftNode(String nodeId, int port, List<Peer> peers) {
        this.nodeId = nodeId;
        this.port = port;
        this.peers = peers;
        resetElectionTimeout();
        this.lastHeartbeat = System.currentTimeMillis();
    }

    private void resetElectionTimeout() {
        this.electionTimeout = 3000 + new Random().nextInt(2000); // 3-5 seconds
    }

    public void start() {
        // 1. Thread del Servidor Raft
        new Thread(this::startServer).start();

        // 2. Thread del Monitor Web
        int webPort = port + 2001;
        new Thread(() -> startWebMonitor(webPort)).start();

        // 3. Thread del Election Timer
        new Thread(this::runElectionTimer).start();

        // 4. Thread del Lider (Heartbeats)
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::runLeaderLoop, 0, 2, TimeUnit.SECONDS);

        System.out
                .println("[" + nodeId + "] Nodo iniciado. Port: " + port + ", Web: " + webPort + ", Estado: " + state);
    }

    private synchronized void processMessage(String json, PrintWriter out) {
        // Parsing manual ultra-básico para evitar librerías pesadas
        String type = getJsonValue(json, "type");
        int term = Integer.parseInt(getJsonValue(json, "term", "0"));

        if (term > currentTerm) {
            currentTerm = term;
            state = "FOLLOWER";
            votedFor = null;
        }

        if ("HEARTBEAT".equals(type)) {
            if (term >= currentTerm) {
                currentTerm = term;
                state = "FOLLOWER";
                lastHeartbeat = System.currentTimeMillis();
            }
            if (out != null)
                out.println("{\"type\": \"HEARTBEAT_ACK\", \"term\": " + currentTerm + "}");
        } else if ("VOTE_REQUEST".equals(type)) {
            String candidateId = getJsonValue(json, "candidate_id");
            boolean canVote = (votedFor == null || votedFor.equals(candidateId)) && (term >= currentTerm);

            if (canVote) {
                votedFor = candidateId;
                currentTerm = term;
                lastHeartbeat = System.currentTimeMillis();
                if (out != null)
                    out.println("{\"type\": \"VOTE_RESPONSE\", \"term\": " + currentTerm + ", \"vote_granted\": true}");
            } else {
                if (out != null)
                    out.println(
                            "{\"type\": \"VOTE_RESPONSE\", \"term\": " + currentTerm + ", \"vote_granted\": false}");
            }
        }
    }

    private void runElectionTimer() {
        while (running) {
            try {
                Thread.sleep(500);
                synchronized (this) {
                    if (!"LEADER".equals(state)) {
                        long elapsed = System.currentTimeMillis() - lastHeartbeat;
                        if (elapsed > electionTimeout) {
                            System.out
                                    .println("[" + nodeId + "] Timeout! Iniciando eleccion Term " + (currentTerm + 1));
                            startElection();
                        }
                    }
                }
            } catch (InterruptedException e) {
            }
        }
    }

    private synchronized void startElection() {
        state = "CANDIDATE";
        currentTerm++;
        votedFor = nodeId;
        votesReceived.clear();
        votesReceived.add(nodeId);
        lastHeartbeat = System.currentTimeMillis();
        resetElectionTimeout();

        for (Peer peer : peers) {
            new Thread(() -> {
                String msg = "{\"type\": \"VOTE_REQUEST\", \"term\": " + currentTerm + ", \"candidate_id\": \"" + nodeId
                        + "\"}";
                String response = sendAndReceive(peer.host, peer.port, msg);
                if (response != null && response.contains("VOTE_RESPONSE")) {
                    synchronized (this) {
                        if ("CANDIDATE".equals(state)
                                && Integer.parseInt(getJsonValue(response, "term", "0")) == currentTerm) {
                            if ("true".equals(getJsonValue(response, "vote_granted"))) {
                                votesReceived.add(peer.host + ":" + peer.port);
                                if (votesReceived.size() > (peers.size() + 1) / 2) {
                                    System.out.println("[" + nodeId + "] Gane la eleccion! Ahora soy el LIDER.");
                                    state = "LEADER";
                                }
                            }
                        }
                    }
                }
            }).start();
        }
    }

    private void runLeaderLoop() {
        synchronized (this) {
            if (!"LEADER".equals(state))
                return;
            lastHeartbeat = System.currentTimeMillis(); // Líder reinicia su propio contador al enviar
        }
        for (Peer peer : peers) {
            String msg = "{\"type\": \"HEARTBEAT\", \"term\": " + currentTerm + ", \"leader_id\": \"" + nodeId + "\"}";
            sendMessage(peer.host, peer.port, msg);
        }
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (running) {
                Socket client = serverSocket.accept();
                new Thread(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
                        String line = in.readLine();
                        if (line != null) {
                            processMessage(line, out);
                        }
                    } catch (Exception e) {
                    }
                }).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String sendAndReceive(String host, int port, String json) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            socket.setSoTimeout(1000);
            out.println(json);
            return in.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private void sendMessage(String host, int port, String json) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(json);
        } catch (Exception e) {
        }
    }

    // Helper manual para JSON
    private String getJsonValue(String json, String key) {
        return getJsonValue(json, key, null);
    }

    private String getJsonValue(String json, String key, String defaultValue) {
        String search = "\"" + key + "\":";
        int index = json.indexOf(search);
        if (index == -1)
            return defaultValue;
        int start = index + search.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':'
                || json.charAt(start) == '"' || json.charAt(start) == '{')) {
            start++;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != ' ') {
            end++;
        }
        String val = json.substring(start, end).replace("\"", "").trim();
        return val.isEmpty() ? defaultValue : val;
    }

    private void startWebMonitor(int webPort) {
        try (ServerSocket serverSocket = new ServerSocket(webPort)) {
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    in.readLine();

                    String html;
                    synchronized (this) {
                        html = "<html><head><meta http-equiv='refresh' content='2'>" +
                                "<style>body{font-family:monospace;background:#222;color:#0f0;padding:20px;}" +
                                ".card{border:1px solid #444;padding:20px;border-radius:8px;max-width:600px;}" +
                                ".LEADER{color:#f00;} .FOLLOWER{color:#0f0;}.CANDIDATE{color:#ff0;}</style></head>" +
                                "<body><div class='card'>" +
                                "<h1>Monitor nodo: " + nodeId + "</h1>" +
                                "<p>Estado: <span class='" + state + "'>" + state + "</span></p>" +
                                "<p>Termino: " + currentTerm + "</p>" +
                                "<p>Voto por: " + votedFor + "</p>" +
                                "<p>" + ("LEADER".equals(state) ? "Latido enviado hace: " : "Ultimo latido hace: ") +
                                String.format("%.2f", (System.currentTimeMillis() - lastHeartbeat) / 1000.0) + "s</p>" +
                                "<p>Log size: " + log.size() + "</p>" +
                                "</div></body></html>";
                    }

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + html.length()
                            + "\r\n\r\n" + html;
                    client.getOutputStream().write(response.getBytes());
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
        }
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "JavaNode_1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
        List<Peer> peers = new ArrayList<>();
        if (args.length > 2) {
            for (int i = 2; i < args.length; i++) {
                peers.add(new Peer("127.0.0.1", Integer.parseInt(args[i])));
            }
        } else {
            peers.add(new Peer("127.0.0.1", 5000)); // Default PythonNode
        }
        new RaftNode(id, port, peers).start();
    }
}
