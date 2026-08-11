package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
public class LogisticModelArtifactLoader {

    public static final String PINNED_LOGISTIC_V1_SHA256 =
            "7B2663ACD7465FFB45FB05C245DDF35C46B8D74B1F06C75B6C0CFF383C22EADB";

    private final LogisticModelArtifact artifact;
    private final String artifactSha256;

    public LogisticModelArtifactLoader(
            ObjectMapper objectMapper,
            @Value("${app.prediction.logistic-v1.artifact:classpath:models/logistic-v1.json}")
            Resource resource,
            @Value("${app.prediction.logistic-v1.expected-sha256}")
            String expectedSha256
    ) {
        byte[] bytes = read(resource);
        this.artifactSha256 = sha256(bytes);
        if (!artifactSha256.equals(PINNED_LOGISTIC_V1_SHA256)) {
            throw new IllegalStateException(
                    "logistic-v1 artifact differs from its immutable version identity. "
                            + "Create logistic-v2 instead of replacing logistic-v1."
            );
        }
        if (!artifactSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException(
                    "logistic-v1 artifact SHA-256 mismatch. Expected "
                            + expectedSha256 + " but was " + artifactSha256
            );
        }
        this.artifact = load(objectMapper, bytes, resource);
        validate(artifact);
    }

    public LogisticModelArtifact artifact() {
        return artifact;
    }

    public String artifactSha256() {
        return artifactSha256;
    }

    private byte[] read(Resource resource) {
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read logistic-v1 model artifact: " + resource,
                    exception
            );
        }
    }

    private LogisticModelArtifact load(
            ObjectMapper objectMapper,
            byte[] bytes,
            Resource resource
    ) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            return new LogisticModelArtifact(
                    root.path("modelVersion").stringValue(""),
                    strings(root.path("features")),
                    strings(root.path("classes")),
                    doubles(root.path("imputer").path("statistics")),
                    doubles(root.path("scaler").path("mean")),
                    doubles(root.path("scaler").path("scale")),
                    matrix(root.path("coefficients")),
                    doubles(root.path("intercepts")),
                    verificationSamples(root.path("verificationSamples"))
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Cannot load logistic-v1 model artifact: " + resource,
                    exception
            );
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void validate(LogisticModelArtifact value) {
        if (!"logistic-v1".equals(value.modelVersion())) {
            throw new IllegalStateException("Unexpected logistic model version.");
        }
        if (value.features().size() != LogisticFeatureValues.SUPPORTED_FEATURES.size()
                || !new HashSet<>(LogisticFeatureValues.SUPPORTED_FEATURES)
                .equals(new HashSet<>(value.features()))) {
            throw new IllegalStateException("Artifact feature set is unsupported.");
        }
        Set<String> outcomes = new HashSet<>();
        for (PredictionOutcome outcome : PredictionOutcome.values()) {
            outcomes.add(outcome.name());
        }
        if (value.classes().size() != outcomes.size()
                || !outcomes.equals(new HashSet<>(value.classes()))) {
            throw new IllegalStateException("Artifact class order is invalid.");
        }
        int featureCount = value.features().size();
        int classCount = value.classes().size();
        if (value.imputerStatistics().length != featureCount
                || value.scalerMean().length != featureCount
                || value.scalerScale().length != featureCount
                || value.coefficients().length != classCount
                || value.intercepts().length != classCount) {
            throw new IllegalStateException("Artifact matrix dimensions are invalid.");
        }
        for (int index = 0; index < featureCount; index++) {
            if (value.scalerScale()[index] <= 0.0
                    || !Double.isFinite(value.scalerScale()[index])) {
                throw new IllegalStateException("Artifact scaler contains an invalid scale.");
            }
        }
        for (double[] coefficients : value.coefficients()) {
            if (coefficients.length != featureCount) {
                throw new IllegalStateException("Artifact coefficient dimensions are invalid.");
            }
        }
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.stringValue(""));
        }
        return List.copyOf(values);
    }

    private double[] doubles(JsonNode array) {
        double[] values = new double[array.size()];
        for (int index = 0; index < array.size(); index++) {
            values[index] = array.get(index).doubleValue();
        }
        return values;
    }

    private double[][] matrix(JsonNode array) {
        double[][] values = new double[array.size()][];
        for (int index = 0; index < array.size(); index++) {
            values[index] = doubles(array.get(index));
        }
        return values;
    }

    private List<LogisticModelArtifact.VerificationSample> verificationSamples(
            JsonNode array
    ) {
        List<LogisticModelArtifact.VerificationSample> samples =
                new ArrayList<>();
        for (JsonNode node : array) {
            samples.add(new LogisticModelArtifact.VerificationSample(
                    node.path("gameId").longValue(),
                    numberMap(node.path("features"), true),
                    numberMap(node.path("probabilities"), false)
            ));
        }
        return List.copyOf(samples);
    }

    private Map<String, Double> numberMap(
            JsonNode object,
            boolean nullable
    ) {
        Map<String, Double> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(
                    field.getKey(),
                    nullable && field.getValue().isNull()
                            ? null
                            : field.getValue().doubleValue()
            );
        }
        return Collections.unmodifiableMap(values);
    }
}
