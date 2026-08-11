package com.playball.kbopredictor.prediction.engine;

import java.util.List;
import java.util.Map;

public record LogisticModelArtifact(
        String modelVersion,
        List<String> features,
        List<String> classes,
        double[] imputerStatistics,
        double[] scalerMean,
        double[] scalerScale,
        double[][] coefficients,
        double[] intercepts,
        List<VerificationSample> verificationSamples
) {

    public record VerificationSample(
            Long gameId,
            Map<String, Double> features,
            Map<String, Double> probabilities
    ) {
    }
}
