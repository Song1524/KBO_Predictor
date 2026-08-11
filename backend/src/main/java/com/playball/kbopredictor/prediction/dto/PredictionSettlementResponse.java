package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.game.entity.GameResult;

public record PredictionSettlementResponse(
        Long gameId,
        GameResult result,
        boolean cancelled,
        Long winnerTeamId,
        String winnerTeamName,
        int totalCount,
        int correctCount,
        int incorrectCount,
        int refundedCount,
        long totalPaidPoints
) {
}
