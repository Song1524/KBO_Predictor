package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameStatus;

public record OfficialGameState(
        String externalGameId,
        String awayTeamCode,
        String homeTeamCode,
        GameStatus status,
        Integer awayScore,
        Integer homeScore
) {
}
