package com.playball.kbopredictor.prediction.evaluation;

import java.math.BigDecimal;

public record ModelEvaluationMetrics(
        String model,
        int sampleCount,
        int correctCount,
        BigDecimal accuracy,
        int homeWinSampleCount,
        BigDecimal homeWinAccuracy,
        int drawSampleCount,
        BigDecimal drawAccuracy,
        int awayWinSampleCount,
        BigDecimal awayWinAccuracy,
        BigDecimal logLoss,
        BigDecimal brierScore
) {
}
