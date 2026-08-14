package com.playball.kbopredictor.stats.collection;

import java.math.BigDecimal;

public record OfficialTeamStanding(
        int rank,
        String teamCode,
        int games,
        int wins,
        int losses,
        int draws,
        BigDecimal winRate,
        BigDecimal gamesBehind,
        String streak,
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
