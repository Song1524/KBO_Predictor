package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.PredictionSource;
import com.playball.kbopredictor.prediction.history.PredictionStage;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ModelPredictionView(
        String modelVersion,
        PredictionSource source,
        PredictionStage stage,
        BigDecimal homeWinProbability,
        BigDecimal drawProbability,
        BigDecimal awayWinProbability,
        PredictionOutcome predictedOutcome,
        BigDecimal featureCoverage,
        String reason,
        String artifactSha256,
        LocalDateTime generatedAt,
        LocalDateTime recordedAt,
        Long featureSnapshotId,
        LocalDateTime featureAsOf,
        String featureGenerationMethod
) {
}
