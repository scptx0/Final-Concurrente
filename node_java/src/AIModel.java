import java.io.Serializable;
import java.util.Random;

public abstract class AIModel implements Serializable {
    protected String modelId;
    protected String type;

    public abstract double predict(double[] inputs);

    public abstract void train(double[][] inputs, double[] targets, int epochs, double lr);

    public abstract String serialize();

    public abstract void deserialize(String data);

    public String getModelId() {
        return modelId;
    }

    public String getType() {
        return type;
    }

    // --- Implementación del Perceptrón Simple ---
    public static class Perceptron extends AIModel {
        private double[] weights;
        private double bias;

        public Perceptron(String id, int inputSize) {
            this.modelId = id;
            this.type = "PERCEPTRON";
            this.weights = new double[inputSize];
            Random r = new Random();
            for (int i = 0; i < inputSize; i++)
                weights[i] = r.nextDouble() * 2 - 1;
            this.bias = r.nextDouble() * 2 - 1;
        }

        @Override
        public double predict(double[] inputs) {
            double sum = bias;
            for (int i = 0; i < weights.length; i++)
                sum += inputs[i] * weights[i];
            return sum > 0 ? 1.0 : 0.0; // Activación Step
        }

        @Override
        public void train(double[][] inputs, double[] targets, int epochs, double lr) {
            for (int e = 0; e < epochs; e++) {
                for (int i = 0; i < inputs.length; i++) {
                    double prediction = predict(inputs[i]);
                    double error = targets[i] - prediction;
                    for (int j = 0; j < weights.length; j++) {
                        weights[j] += lr * error * inputs[i][j];
                    }
                    bias += lr * error;
                }
            }
        }

        @Override
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append(bias).append(";");
            for (int i = 0; i < weights.length; i++) {
                sb.append(weights[i]).append(i == weights.length - 1 ? "" : ",");
            }
            return sb.toString();
        }

        @Override
        public void deserialize(String data) {
            String[] parts = data.split(";");
            this.bias = Double.parseDouble(parts[0]);
            String[] wParts = parts[1].split(",");
            for (int i = 0; i < wParts.length; i++)
                weights[i] = Double.parseDouble(wParts[i]);
        }
    }

    // --- Implementación de MLP (1 capa oculta) ---
    public static class MLP extends AIModel {
        private double[][] wHidden; // [inputs][hidden]
        private double[] wOutput; // [hidden]
        private double[] bHidden;
        private double bOutput;
        private int hiddenSize;

        public MLP(String id, int inputSize, int hiddenSize) {
            this.modelId = id;
            this.type = "MLP";
            this.hiddenSize = hiddenSize;
            Random r = new Random();
            this.wHidden = new double[inputSize][hiddenSize];
            this.bHidden = new double[hiddenSize];
            for (int i = 0; i < inputSize; i++)
                for (int j = 0; j < hiddenSize; j++)
                    wHidden[i][j] = r.nextDouble() * 2 - 1;
            for (int i = 0; i < hiddenSize; i++)
                bHidden[i] = r.nextDouble() * 2 - 1;

            this.wOutput = new double[hiddenSize];
            for (int i = 0; i < hiddenSize; i++)
                wOutput[i] = r.nextDouble() * 2 - 1;
            this.bOutput = r.nextDouble() * 2 - 1;
        }

        private double sigmoid(double x) {
            return 1.0 / (1.0 + Math.exp(-x));
        }

        @Override
        public double predict(double[] inputs) {
            double[] hiddenOut = new double[hiddenSize];
            for (int j = 0; j < hiddenSize; j++) {
                double sum = bHidden[j];
                for (int i = 0; i < inputs.length; i++)
                    sum += inputs[i] * wHidden[i][j];
                hiddenOut[j] = sigmoid(sum);
            }
            double finalSum = bOutput;
            for (int j = 0; j < hiddenSize; j++)
                finalSum += hiddenOut[j] * wOutput[j];
            return sigmoid(finalSum) > 0.5 ? 1.0 : 0.0;
        }

        @Override
        public void train(double[][] inputs, double[] targets, int epochs, double lr) {
            // Implementación simplificada de Backpropagation
            for (int e = 0; e < epochs; e++) {
                for (int i = 0; i < inputs.length; i++) {
                    // Forward pass
                    double[] hOut = new double[hiddenSize];
                    for (int j = 0; j < hiddenSize; j++) {
                        double sum = bHidden[j];
                        for (int k = 0; k < inputs[0].length; k++)
                            sum += inputs[i][k] * wHidden[k][j];
                        hOut[j] = sigmoid(sum);
                    }
                    double outSum = bOutput;
                    for (int j = 0; j < hiddenSize; j++)
                        outSum += hOut[j] * wOutput[j];
                    double prediction = sigmoid(outSum);

                    // Backward pass
                    double dOut = (targets[i] - prediction) * prediction * (1 - prediction);
                    for (int j = 0; j < hiddenSize; j++) {
                        double dHidden = dOut * wOutput[j] * hOut[j] * (1 - hOut[j]);
                        for (int k = 0; k < inputs[0].length; k++) {
                            wHidden[k][j] += lr * dHidden * inputs[i][k];
                        }
                        bHidden[j] += lr * dHidden;
                        wOutput[j] += lr * dOut * hOut[j];
                    }
                    bOutput += lr * dOut;
                }
            }
        }

        @Override
        public String serialize() {
            // Formato:
            // hiddenSize;bOutput;bHidden1,bHidden2;wOutput1,wOutput2;wHidden00,wHidden01...
            StringBuilder sb = new StringBuilder();
            sb.append(hiddenSize).append(";").append(bOutput).append(";");
            for (int i = 0; i < bHidden.length; i++)
                sb.append(bHidden[i]).append(i == bHidden.length - 1 ? "" : ",");
            sb.append(";");
            for (int i = 0; i < wOutput.length; i++)
                sb.append(wOutput[i]).append(i == wOutput.length - 1 ? "" : ",");
            sb.append(";");
            for (int i = 0; i < wHidden.length; i++) {
                for (int j = 0; j < wHidden[0].length; j++) {
                    sb.append(wHidden[i][j]).append(",");
                }
            }
            return sb.toString();
        }

        @Override
        public void deserialize(String data) {
            String[] parts = data.split(";");
            this.hiddenSize = Integer.parseInt(parts[0]);
            this.bOutput = Double.parseDouble(parts[1]);
            String[] bhParts = parts[2].split(",");
            for (int i = 0; i < bhParts.length; i++)
                bHidden[i] = Double.parseDouble(bhParts[i]);
            String[] woParts = parts[3].split(",");
            for (int i = 0; i < woParts.length; i++)
                wOutput[i] = Double.parseDouble(woParts[i]);
            String[] whParts = parts[4].split(",");
            int k = 0;
            for (int i = 0; i < wHidden.length; i++) {
                for (int j = 0; j < wHidden[0].length; j++) {
                    wHidden[i][j] = Double.parseDouble(whParts[k++]);
                }
            }
        }
    }
}
