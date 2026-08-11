package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SystemPredictionResponse(
        Long id,
        Long gameId,

        Long predictedWinnerTeamId,
        String predictedWinnerTeamName,
        PredictionOutcome predictedOutcome,

        BigDecimal homeWinProbability,
        BigDecimal drawProbability,
        BigDecimal awayWinProbability,

        BigDecimal homeScorePoint,
        BigDecimal awayScorePoint,

        String reason,
        String modelVersion,
        BigDecimal featureCoverage,
        LocalDateTime createdAt,
        LocalDateTime generatedAt
) {

    public static SystemPredictionResponse from(
            SystemPrediction prediction
    ) {
        return new SystemPredictionResponse(
                prediction.getId(),
                prediction.getGame().getId(),

                prediction.getPredictedWinnerTeam() == null
                        ? null
                        : prediction.getPredictedWinnerTeam().getId(),

                prediction.getPredictedWinnerTeam() == null
                        ? null
                        : prediction.getPredictedWinnerTeam().getName(),

                prediction.getPredictedOutcome(),

                prediction.getHomeWinProbability(),
                prediction.getDrawProbability(),
                prediction.getAwayWinProbability(),

                prediction.getHomeScorePoint(),
                prediction.getAwayScorePoint(),

                prediction.getReason(),
                prediction.getModelVersion(),
                prediction.getFeatureCoverage(),
                prediction.getCreatedAt(),
                prediction.getGeneratedAt()
        );
    }
}
