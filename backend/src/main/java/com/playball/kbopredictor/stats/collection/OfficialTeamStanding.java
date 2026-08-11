package com.playball.kbopredictor.stats.collection;

import java.math.BigDecimal;

public record OfficialTeamStanding(
        String teamCode,
        int wins,
        int losses,
        int draws,
        BigDecimal winRate,
        int recent10Wins,
        int recent10Losses,
        int recent10Draws,
        int homeWins,
        int homeLosses,
        int homeDraws,
        int awayWins,
        int awayLosses,
        int awayDraws
) {
}
