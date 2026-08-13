package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;

import java.time.LocalDate;
import java.time.LocalTime;

public record CollectedGame(
        String externalGameId,
        int season,
        LocalDate gameDate,
        LocalTime gameTime,
        String awayTeamCode,
        String homeTeamCode,
        String stadium,
        GameStatus status,
        Integer awayScore,
        Integer homeScore,
        GameResult result,
        boolean finalScoreConfirmed,
        String cancelReason
) {
}
