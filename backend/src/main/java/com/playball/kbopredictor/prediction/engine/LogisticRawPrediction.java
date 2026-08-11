package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.util.Map;

public record LogisticRawPrediction(
        Map<PredictionOutcome, Double> probabilities,
        PredictionOutcome predictedOutcome,
        int availableFeatureCount,
        double[] standardizedFeatures
) {
}
