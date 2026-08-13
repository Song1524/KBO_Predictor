package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;

public record SettlementCorrectionResponse(
        SettlementCorrectionStatus status,
        Long predictionId,
        Long userId,
        String externalGameId,
        Boolean isCorrect,
        Boolean settled,
        PredictionSettlementStatus settlementStatus,
        int rewardPoint,
        int currentPoint
) {
}
