package com.playball.kbopredictor.prediction.evaluation;

import java.math.BigDecimal;

public record BenchmarkEvaluationResponse(
        String name,
        int sampleCount,
        int correctCount,
        BigDecimal accuracy
) {
}
