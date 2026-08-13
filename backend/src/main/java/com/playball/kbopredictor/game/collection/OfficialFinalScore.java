package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;

public record OfficialFinalScore(
        String externalGameId,
        String awayTeamCode,
        String homeTeamCode,
        int awayScore,
        int homeScore,
        GameResult result
) {
}
