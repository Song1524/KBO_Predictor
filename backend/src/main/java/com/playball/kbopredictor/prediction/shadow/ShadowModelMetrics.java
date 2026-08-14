package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;
import java.util.Map;

public record ShadowModelMetrics(
        String modelVersion,
        int evaluatedGameCount,
        BigDecimal accuracy,
        BigDecimal logLoss,
        BigDecimal brierScore,
        BigDecimal macroF1,
        BigDecimal averageMaxProbability,
        Map<String, BigDecimal> averageProbabilities,
        Map<String, ShadowClassCalibration> calibration,
        Map<String, ShadowClassMetrics> classMetrics,
        Map<String, Map<String, Integer>> confusionMatrix
) {
}
