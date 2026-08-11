package com.playball.kbopredictor.prediction.evaluation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PredictionEvaluationResponse(
        String modelVersion,
        LocalDate from,
        LocalDate to,
        int finishedGameCount,
        int featureGeneratedGameCount,
        int evaluableGameCount,
        int skippedGameCount,
        BigDecimal dataCoverage,
        BigDecimal averageFeatureCoverage,
        int starterDataGameCount,
        int teamOnlyGameCount,
        Map<String, Integer> missingFeatureCounts,
        int correctCount,
        BigDecimal overallAccuracy,
        int homeWinSampleCount,
        BigDecimal homeWinAccuracy,
        int drawSampleCount,
        BigDecimal drawAccuracy,
        int awayWinSampleCount,
        BigDecimal awayWinAccuracy,
        BigDecimal logLoss,
        BigDecimal brierScore,
        List<BenchmarkEvaluationResponse> benchmarks
) {

    public PredictionEvaluationResponse {
        benchmarks = List.copyOf(benchmarks);
        missingFeatureCounts = Map.copyOf(missingFeatureCounts);
    }
}
