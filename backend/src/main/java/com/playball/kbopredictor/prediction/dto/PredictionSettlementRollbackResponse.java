package com.playball.kbopredictor.prediction.dto;

import java.time.LocalDateTime;

public record PredictionSettlementRollbackResponse(
        Long gameId,
        int settlementRevision,
        boolean alreadyRolledBack,
        int restoredPredictionCount,
        int reversedPointHistoryCount,
        long reversedPointTotal,
        Long rolledBackByUserId,
        LocalDateTime rolledBackAt
) {
}
