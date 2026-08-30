package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;

import java.time.LocalDateTime;

public record GameResultCorrectionResponse(
        Long gameId,
        int settlementRevision,
        GameStatus status,
        GameResult result,
        Integer homeScore,
        Integer awayScore,
        Long winnerTeamId,
        String winnerTeamName,
        Long correctedByUserId,
        String reason,
        LocalDateTime correctedAt
) {
}
