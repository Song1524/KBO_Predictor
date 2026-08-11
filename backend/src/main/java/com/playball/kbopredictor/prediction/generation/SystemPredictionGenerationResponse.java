package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SystemPredictionGenerationResponse(
        Long gameId,
        SystemPredictionGenerationStatus status,
        PredictionOutcome predictedOutcome,
        BigDecimal homeWinProbability,
        BigDecimal drawProbability,
        BigDecimal awayWinProbability,
        String modelVersion,
        BigDecimal featureCoverage,
        LocalDateTime generatedAt,
        String message
) {
    public static SystemPredictionGenerationResponse skipped(
            Long gameId,
            SystemPredictionGenerationStatus status,
            String message
    ) {
        return new SystemPredictionGenerationResponse(
                gameId,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                message
        );
    }

    public static SystemPredictionGenerationResponse failed(
            Long gameId,
            String message
    ) {
        return skipped(
                gameId,
                SystemPredictionGenerationStatus.FAILED,
                message
        );
    }
}
