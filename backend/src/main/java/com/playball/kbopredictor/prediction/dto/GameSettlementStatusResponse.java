package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.entity.GameSettlementSource;
import com.playball.kbopredictor.prediction.entity.GameSettlementState;

import java.time.LocalDateTime;

public record GameSettlementStatusResponse(
        Long gameId,
        GameStatus currentGameStatus,
        GameResult currentGameResult,
        Integer currentHomeScore,
        Integer currentAwayScore,
        Integer latestRevision,
        GameSettlementState settlementState,
        GameSettlementSource settlementSource,
        GameStatus settledGameStatus,
        GameResult settledGameResult,
        Integer settledHomeScore,
        Integer settledAwayScore,
        int predictionCount,
        long settledPredictionCount,
        long pendingPredictionCount,
        Long settledByUserId,
        LocalDateTime settledAt,
        Long rolledBackByUserId,
        LocalDateTime rolledBackAt,
        String rollbackReason,
        Long resultCorrectedByUserId,
        LocalDateTime resultCorrectedAt,
        String resultCorrectionReason,
        GameStatus correctedGameStatus,
        GameResult correctedGameResult,
        Integer correctedHomeScore,
        Integer correctedAwayScore,
        boolean correctionReviewRequired,
        boolean recoveryPending
) {
}
