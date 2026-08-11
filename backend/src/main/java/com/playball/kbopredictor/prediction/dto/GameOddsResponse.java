package com.playball.kbopredictor.prediction.dto;

import java.time.LocalDateTime;

public record GameOddsResponse(
        Long gameId,
        long totalBetPoints,
        OutcomeOddsResponse homeWin,
        OutcomeOddsResponse draw,
        OutcomeOddsResponse awayWin,
        boolean bettingOpen,
        boolean finalized,
        LocalDateTime predictionCloseAt,
        LocalDateTime finalizedAt
) {
}
