package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

public record SystemPredictionReconciliationResult(
        PredictionFeatures features, SystemPredictionWriteResult write
) {}
