import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.*;
import java.util.concurrent.*;

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
    private Map<String, AIModel> models = new HashMap<>();

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
        loadState();
        resetElectionTimeout();
        this.lastHeartbeat = System.currentTimeMillis();
    }

    private synchronized void saveState() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"currentTerm\":").append(currentTerm).append(",");
            sb.append("\"votedFor\":").append(votedFor == null ? "null" : "\"" + votedFor + "\"").append(",");
            sb.append("\"log\":[");
            for (int i = 0; i < log.size(); i++) {
                sb.append("\"").append(log.get(i).replace("\"", "\\\"")).append("\"");
                if (i < log.size() - 1)
                    sb.append(",");
            }
            sb.append("],\"models\":{");
            int count = 0;
            for (String mid : models.keySet()) {
                AIModel m = models.get(mid);
                sb.append("\"").append(mid).append("\":{")
                        .append("\"type\":\"").append(m.getType()).append("\",")
                        .append("\"weights\":\"").append(m.serialize()).append("\"}")
                        .append(++count < models.size() ? "," : "");
            }
            sb.append("}}");
            Files.write(Paths.get("raft_state_" + nodeId + ".json"), sb.toString().getBytes());
        } catch (Exception e) {
            System.err.println("Error guardando estado: " + e.getMessage());
        }
    }

    private void loadState() {
        try {
            String path = "raft_state_" + nodeId + ".json";
            if (Files.exists(Paths.get(path))) {
                String content = new String(Files.readAllBytes(Paths.get(path)));
                currentTerm = Integer.parseInt(getJsonValue(content, "currentTerm", "0"));
                votedFor = getJsonValue(content, "votedFor");
                if ("null".equals(votedFor))
                    votedFor = null;

                // Carga de log simplificada (buscando el array [])
                int logStart = content.indexOf("\"log\":[") + 7;
                int logEnd = content.lastIndexOf("]");
                if (logStart > 7 && logEnd > logStart) {
                    String logContent = content.substring(logStart, logEnd);
                    if (!logContent.isEmpty()) {
                        String[] items = logContent.split("\",\"");
                        for (String item : items) {
                            log.add(item.replace("\"", "").replace("\\", ""));
                        }
                    }
                }
                System.out.println("[" + nodeId + "] Estado cargado: Term " + currentTerm + ", Votado por " + votedFor
                        + ", Log " + log.size() + " items");

                // Carga de modelos
                int modelsStart = content.indexOf("\"models\":{");
                if (modelsStart != -1) {
                    // Muy simplificado: buscamos los bloques de modelos
                    // En un sistema real usaríamos Jackson/Gson
                    String modelsSection = content.substring(modelsStart + 9, content.length() - 1);
                    if (modelsSection.length() > 2) {
                        String[] modelEntries = modelsSection.split("\\},\"");
                        for (String entry : modelEntries) {
                            try {
                                String cleanEntry = entry.startsWith("{") ? entry.substring(1) : entry;
                                String mId = cleanEntry.split("\":\\{")[0].replace("\"", "");
                                String mType = getJsonValue(entry, "type");
                                String mWeights = getJsonValue(entry, "weights");

                                AIModel model;
                                if ("MLP".equals(mType)) {
                                    model = new AIModel.MLP(mId, 2, 5);
                                } else {
                                    model = new AIModel.Perceptron(mId, 2);
                                }
                                model.deserialize(mWeights);
                                synchronized (this) {
                                    models.put(mId, model);
                                }
                            } catch (Exception ex) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error cargando estado: " + e.getMessage());
        }
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
            saveState();
        }

        if ("HEARTBEAT".equals(type)) {
            if (term >= currentTerm) {
                if (term > currentTerm) {
                    currentTerm = term;
                    saveState();
                }
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
                saveState();
                if (out != null)
                    out.println("{\"type\": \"VOTE_RESPONSE\", \"term\": " + currentTerm + ", \"vote_granted\": true}");
            } else {
                if (out != null)
                    out.println(
                            "{\"type\": \"VOTE_RESPONSE\", \"term\": " + currentTerm + ", \"vote_granted\": false}");
            }
        } else if ("TRAIN".equals(type)) {
            // Permitir entrenamiento si somos LIDER O si recibimos una orden del LIDER
            // (delegación)
            if (!"LEADER".equals(state) && term < currentTerm) {
                if (out != null)
                    out.println("{\"type\": \"TRAIN_RESULT\", \"status\": \"error\", \"message\": \"Not the leader\"}");
                return;
            }
            String modelId = getJsonValue(json, "model_id");
            String modelType = getJsonValue(json, "model_type", "PERCEPTRON");

            // Extraer datos personalizados o usar default
            double[][] inputs;
            double[] targets;

            String customInputs = getJsonValue(json, "inputs");
            String customTargets = getJsonValue(json, "targets");

            if (customInputs != null && customTargets != null) {
                String[] rows = customInputs.replace("[[", "").replace("]]", "").split("\\],\\s*\\[");
                inputs = new double[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    String[] cols = rows[i].split(",");
                    inputs[i] = new double[cols.length];
                    for (int j = 0; j < cols.length; j++)
                        inputs[i][j] = Double.parseDouble(cols[j].trim());
                }
                String[] tVals = customTargets.replace("[", "").replace("]", "").split(",");
                targets = new double[tVals.length];
                for (int i = 0; i < tVals.length; i++)
                    targets[i] = Double.parseDouble(tVals[i].trim());
            } else {
                inputs = new double[][] { { 0, 0 }, { 0, 1 }, { 1, 0 }, { 1, 1 } };
                targets = new double[] { 0, 1, 1, 1 };
            }

            AIModel finalModel;
            int inputSize = inputs[0].length;

            if (peers.isEmpty()) {
                if ("MLP".equals(modelType))
                    finalModel = new AIModel.MLP(modelId, inputSize, 5);
                else
                    finalModel = new AIModel.Perceptron(modelId, inputSize);
                finalModel.train(inputs, targets, 1000, 0.1);
            } else {
                System.out.println("[" + nodeId + "] Distribuyendo entrenamiento entre " + peers.size() + " nodos...");
                int chunkSize = (int) Math.ceil((double) inputs.length / (peers.size() + 1));
                List<AIModel> subModels = new ArrayList<>();

                // 1. Local
                int localEnd = Math.min(chunkSize, inputs.length);
                double[][] localIn = java.util.Arrays.copyOfRange(inputs, 0, localEnd);
                double[] localTarget = java.util.Arrays.copyOfRange(targets, 0, localEnd);
                AIModel localModel;
                if ("MLP".equals(modelType))
                    localModel = new AIModel.MLP(modelId, inputSize, 5);
                else
                    localModel = new AIModel.Perceptron(modelId, inputSize);
                localModel.train(localIn, localTarget, 1000, 0.1);
                subModels.add(localModel);

                // 2. Peers
                for (int i = 0; i < peers.size(); i++) {
                    int start = (i + 1) * chunkSize;
                    if (start >= inputs.length)
                        break;
                    int end = Math.min(start + chunkSize, inputs.length);
                    double[][] peerIn = java.util.Arrays.copyOfRange(inputs, start, end);
                    double[] peerTarget = java.util.Arrays.copyOfRange(targets, start, end);

                    String taskMsg = "{\"type\": \"TRAIN_TASK\", \"model_id\": \"" + modelId +
                            "\", \"model_type\": \"" + modelType +
                            "\", \"inputs\": \"" + java.util.Arrays.deepToString(peerIn).replace(" ", "") +
                            "\", \"targets\": \"" + java.util.Arrays.toString(peerTarget).replace(" ", "") + "\"}";

                    Peer p = peers.get(i);
                    String resp = sendAndReceive(p.host, p.port, taskMsg);
                    if (resp != null) {
                        String w = getJsonValue(resp, "weights");
                        AIModel pm;
                        if ("MLP".equals(modelType))
                            pm = new AIModel.MLP(modelId, inputSize, 5);
                        else
                            pm = new AIModel.Perceptron(modelId, inputSize);
                        pm.deserialize(w);
                        subModels.add(pm);
                    }
                }

                // 3. Average
                if ("MLP".equals(modelType)) {
                    finalModel = AIModel.MLP.average(modelId,
                            subModels.stream().map(m -> (AIModel.MLP) m).toArray(AIModel.MLP[]::new));
                } else {
                    finalModel = AIModel.Perceptron.average(modelId,
                            subModels.stream().map(m -> (AIModel.Perceptron) m).toArray(AIModel.Perceptron[]::new));
                }
            }

            models.put(modelId, finalModel);
            String weights = finalModel.serialize();
            String syncMsg = "{\"type\": \"MODEL_SYNC\", \"model_id\": \"" + modelId +
                    "\", \"model_type\": \"" + modelType + "\", \"weights\": \"" + weights + "\"}";
            log.add("TRAINED_DISTRIBUTED: " + modelId);
            for (Peer p : peers)
                new Thread(() -> sendMessage(p.host, p.port, syncMsg)).start();
            saveState();
            if (out != null)
                out.println("{\"type\": \"TRAIN_RESULT\", \"model_id\": \"" + modelId + "\", \"status\": \"success\"}");
        } else if ("TRAIN_TASK".equals(type)) {
            // Un seguidor recibe una parte del entrenamiento
            String modelId = getJsonValue(json, "model_id");
            String modelType = getJsonValue(json, "model_type", "PERCEPTRON");
            String customInputs = getJsonValue(json, "inputs");
            String customTargets = getJsonValue(json, "targets");

            // Parsear inputs/targets (copiado de TRAIN)
            String[] rows = customInputs.replace("[[", "").replace("]]", "").split("\\],\\s*\\[");
            double[][] inputs = new double[rows.length][];
            for (int i = 0; i < rows.length; i++) {
                String[] cols = rows[i].split(",");
                inputs[i] = new double[cols.length];
                for (int j = 0; j < cols.length; j++)
                    inputs[i][j] = Double.parseDouble(cols[j].trim());
            }
            String[] tVals = customTargets.replace("[", "").replace("]", "").split(",");
            double[] targets = new double[tVals.length];
            for (int i = 0; i < tVals.length; i++)
                targets[i] = Double.parseDouble(tVals[i].trim());

            AIModel model;
            int inputSize = inputs[0].length;
            if ("MLP".equals(modelType)) {
                model = new AIModel.MLP(modelId, inputSize, 5);
            } else {
                model = new AIModel.Perceptron(modelId, inputSize);
            }

            model.train(inputs, targets, 1000, 0.1);

            if (out != null) {
                out.println("{\"type\": \"TRAIN_TASK_RESULT\", \"model_id\": \"" + modelId +
                        "\", \"weights\": \"" + model.serialize() + "\"}");
            }
            return;
        } else if ("PREDICT".equals(type)) {
            String modelId = getJsonValue(json, "model_id");
            System.out.println("[" + nodeId + "] PREDICCION solicitada para: " + modelId);
            AIModel model = models.get(modelId);
            if (model == null) {
                System.out.println("[" + nodeId + "] Error: Modelo " + modelId + " no encontrado.");
                if (out != null)
                    out.println(
                            "{\"type\": \"PREDICT_RESULT\", \"status\": \"error\", \"message\": \"Model not found\"}");
                return;
            }
            // Parsear inputs
            String dataStr = getJsonValue(json, "data");
            System.out.println("[" + nodeId + "] Inputs recibidos: " + dataStr);
            String[] parts = dataStr.replace("[", "").replace("]", "").split(",");
            double[] inputs = new double[parts.length];
            for (int i = 0; i < parts.length; i++)
                inputs[i] = Double.parseDouble(parts[i].trim());

            double prediction = model.predict(inputs);
            System.out.println("[" + nodeId + "] Resultado prediccion: " + prediction);
            if (out != null)
                out.println("{\"type\": \"PREDICT_RESULT\", \"model_id\": \"" + modelId + "\", \"prediction\": "
                        + prediction + "}");
        } else if ("MODEL_SYNC".equals(type)) {
            String modelId = getJsonValue(json, "model_id");
            String modelType = getJsonValue(json, "model_type");
            String weights = getJsonValue(json, "weights");

            System.out.println(
                    "[" + nodeId + "] Recibiendo sincronizacion de modelo: " + modelId + " (" + modelType + ")");

            AIModel m;
            try {
                if ("MLP".equals(modelType)) {
                    // hiddenSize;bOutput;bHidden...
                    int hiddenSize = Integer.parseInt(weights.split(";")[0]);
                    // Necesitamos inputSize. Lo inferimos de la parte final de los pesos.
                    // wHidden está al final.
                    String[] parts = weights.split(";");
                    int totalWHidden = parts[4].split(",").length;
                    int inputSize = totalWHidden / hiddenSize;
                    m = new AIModel.MLP(modelId, inputSize, hiddenSize);
                } else {
                    // bias;w1,w2...
                    int inputSize = weights.split(";")[1].split(",").length;
                    m = new AIModel.Perceptron(modelId, inputSize);
                }
                m.deserialize(weights);
                models.put(modelId, m);
                System.out.println("[" + nodeId + "] Modelo " + modelId + " sincronizado correctamente.");
                log.add("REPLICATED_MODEL: " + modelId);
                saveState();
                if (out != null)
                    out.println("{\"type\": \"MODEL_SYNC_ACK\", \"status\": \"success\"}");
            } catch (Exception e) {
                System.out.println("[" + nodeId + "] Error sincronizando modelo " + modelId + ": " + e.getMessage());
                if (out != null)
                    out.println("{\"type\": \"MODEL_SYNC_ACK\", \"status\": \"error\", \"message\": \"" + e.getMessage()
                            + "\"}");
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
        saveState();

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
        // Saltar espacios y dos puntos
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':')) {
            start++;
        }

        if (start >= json.length())
            return defaultValue;

        char firstChar = json.charAt(start);
        int end = start;

        if (firstChar == '"') {
            // Es un string: leer hasta la siguiente comilla no escapada
            start++; // saltar comilla inicial
            end = start;
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\')
                    end++; // saltar escape
                end++;
            }
        } else if (firstChar == '[' || firstChar == '{') {
            // Es array u objeto: leer hasta balancear corchetes/llaves
            int balance = 0;
            char open = firstChar;
            char close = (open == '[') ? ']' : '}';
            while (end < json.length()) {
                if (json.charAt(end) == open)
                    balance++;
                if (json.charAt(end) == close)
                    balance--;
                end++;
                if (balance == 0)
                    break;
            }
        } else {
            // Es un número o boolean: leer hasta coma o fin de objeto
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                    && json.charAt(end) != ']') {
                end++;
            }
        }

        if (start >= end)
            return defaultValue;
        return json.substring(start, end).trim();
    }

    private void startWebMonitor(int webPort) {
        try (ServerSocket serverSocket = new ServerSocket(webPort)) {
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    in.readLine();

                    final String htmlOutput;
                    synchronized (this) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("<html><head><meta http-equiv='refresh' content='2'>")
                                .append("<style>body{font-family:monospace;background:#222;color:#0f0;padding:20px;}")
                                .append(".card{border:1px solid #444;padding:20px;border-radius:8px;max-width:600px;}")
                                .append(".LEADER{color:#f00;} .FOLLOWER{color:#0f0;}.CANDIDATE{color:#ff0;}</style></head>")
                                .append("<body><div class='card'>")
                                .append("<h1>Monitor nodo: ").append(nodeId).append("</h1>")
                                .append("<p>Estado: <span class='").append(state).append("'>").append(state)
                                .append("</span></p>")
                                .append("<p>Termino: ").append(currentTerm).append("</p>")
                                .append("<p>Voto por: ").append(votedFor).append("</p>")
                                .append("<p>")
                                .append("LEADER".equals(state) ? "Latido enviado hace: " : "Ultimo latido hace: ")
                                .append(String.format("%.2f", (System.currentTimeMillis() - lastHeartbeat) / 1000.0))
                                .append("s</p>")
                                .append("<hr><h3>Modelos Entrenados:</h3><ul>");

                        for (String mid : models.keySet()) {
                            sb.append("<li><b>").append(mid).append("</b> (").append(models.get(mid).getType())
                                    .append(")</li>");
                        }

                        sb.append("</ul><hr><p>Log size: ").append(log.size()).append("</p></div></body></html>");
                        htmlOutput = sb.toString();
                    }

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                            + htmlOutput.length()
                            + "\r\n\r\n" + htmlOutput;
                    client.getOutputStream().write(response.getBytes());
                } catch (Exception e) {
                    // Ignorar errores de conexión individual
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
