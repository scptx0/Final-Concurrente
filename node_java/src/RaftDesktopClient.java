import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class RaftDesktopClient extends JFrame {
    private JTextField hostField, portField;
    private JTextArea logArea;

    // Training UI
    private JTextField trainModelIdField;
    private JComboBox<String> modelTypeCombo;
    private JTextArea inputsArea, targetsArea;

    // Prediction UI
    private JTextField predModelIdField;
    private JTextField predDataField;
    private JLabel resultLabel;

    public RaftDesktopClient() {
        setTitle("Distributed AI Raft Client - Distributed Systems Final");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // --- Connection Panel (Top) ---
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connPanel.setBorder(BorderFactory.createTitledBorder("Configuracion de Red"));
        hostField = new JTextField("127.0.0.1", 10);
        portField = new JTextField("5000", 5);
        connPanel.add(new JLabel("Host:"));
        connPanel.add(hostField);
        connPanel.add(new JLabel("Puerto:"));
        connPanel.add(portField);
        add(connPanel, BorderLayout.NORTH);

        // --- Tabs Pane (Center) ---
        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: Training
        tabs.addTab("Entrenamiento", createTrainingPanel());
        // Tab 2: Prediction
        tabs.addTab("Prediccion", createPredictionPanel());

        add(tabs, BorderLayout.CENTER);

        // --- Log Panel (Bottom) ---
        logArea = new JTextArea(8, 20);
        logArea.setEditable(false);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(0, 255, 0));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log de Comunicacion"));
        add(logScroll, BorderLayout.SOUTH);

        setLocationRelativeTo(null);
    }

    private JPanel createTrainingPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        p.add(new JLabel("ID del Modelo:"), gbc);
        trainModelIdField = new JTextField("mi_modelo");
        gbc.gridx = 1;
        p.add(trainModelIdField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        p.add(new JLabel("Tipo de AI:"), gbc);
        modelTypeCombo = new JComboBox<>(new String[] { "PERCEPTRON", "MLP" });
        gbc.gridx = 1;
        p.add(modelTypeCombo, gbc);

        gbc.gridy = 2;
        gbc.gridx = 0;
        p.add(new JLabel("Carga de Datos (Inputs):"), gbc);
        inputsArea = new JTextArea("[[0,0],[0,1],[1,0],[1,1]]", 4, 30);
        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        p.add(new JScrollPane(inputsArea), gbc);

        gbc.gridy = 4;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        p.add(new JLabel("Objetivos (Targets):"), gbc);
        targetsArea = new JTextArea("[0,1,1,1]", 2, 30);
        gbc.gridy = 5;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        p.add(new JScrollPane(targetsArea), gbc);

        gbc.gridy = 6;
        JButton trainBtn = new JButton("Iniciar Entrenamiento Distribuido");
        trainBtn.setBackground(new Color(70, 130, 180));
        trainBtn.setForeground(Color.WHITE);
        trainBtn.addActionListener(e -> handleTrain());
        p.add(trainBtn, gbc);

        return p;
    }

    private JPanel createPredictionPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        p.add(new JLabel("ID del Modelo:"), gbc);
        predModelIdField = new JTextField("mi_modelo");
        gbc.gridx = 1;
        p.add(predModelIdField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        p.add(new JLabel("Datos de entrada (ej: [1,0]):"), gbc);
        predDataField = new JTextField("[1, 0]");
        gbc.gridx = 1;
        p.add(predDataField, gbc);

        gbc.gridy = 2;
        gbc.gridwidth = 2;
        JButton predBtn = new JButton("Solicitar Prediccion al Cluster");
        predBtn.setBackground(new Color(60, 179, 113));
        predBtn.setForeground(Color.WHITE);
        predBtn.addActionListener(e -> handlePredict());
        p.add(predBtn, gbc);

        resultLabel = new JLabel("Resultado: --", SwingConstants.CENTER);
        resultLabel.setFont(new Font("Arial", Font.BOLD, 24));
        gbc.gridy = 3;
        p.add(resultLabel, gbc);

        return p;
    }

    private void handleTrain() {
        String id = trainModelIdField.getText();
        String type = (String) modelTypeCombo.getSelectedItem();
        String inputs = inputsArea.getText().trim();
        String targets = targetsArea.getText().trim();

        String msg = "{\"type\": \"TRAIN\", \"model_id\": \"" + id +
                "\", \"model_type\": \"" + type +
                "\", \"inputs\": \"" + inputs +
                "\", \"targets\": \"" + targets + "\"}";

        sendRequest(msg);
    }

    private void handlePredict() {
        String id = predModelIdField.getText();
        String data = predDataField.getText().trim();

        String msg = "{\"type\": \"PREDICT\", \"model_id\": \"" + id + "\", \"data\": \"" + data + "\"}";
        sendRequest(msg);
    }

    private void sendRequest(String json) {
        String host = hostField.getText();
        int port = Integer.parseInt(portField.getText());

        new Thread(() -> {
            try (Socket socket = new Socket(host, port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                logArea.append(">> ENVIANDO: " + json + "\n");
                out.println(json);
                String response = in.readLine();
                logArea.append("<< RECIBIDO: " + response + "\n");

                if (json.contains("PREDICT") && response != null) {
                    if (response.contains("\"prediction\":")) {
                        // Extraer prediccion simple
                        String predVal = response.split("\"prediction\":")[1].replace("}", "").trim();
                        SwingUtilities.invokeLater(() -> resultLabel.setText("Resultado: " + predVal));
                    } else if (response.contains("error")) {
                        SwingUtilities.invokeLater(() -> resultLabel.setText("Error: Ver Log"));
                    }
                }

            } catch (Exception e) {
                logArea.append("!! ERROR: " + e.getMessage() + "\n");
                SwingUtilities
                        .invokeLater(() -> JOptionPane.showMessageDialog(this, "Error de conexion: " + e.getMessage()));
            }
        }).start();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }

        SwingUtilities.invokeLater(() -> {
            new RaftDesktopClient().setVisible(true);
        });
    }
}
