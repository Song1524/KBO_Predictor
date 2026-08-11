package com.playball.kbopredictor.stats.collection;

import java.math.BigDecimal;

public record CollectedTeamStat(
        OfficialTeamStanding standing,
        BigDecimal battingAverage,
        BigDecimal era
) {
    public String teamCode() {
        return standing.teamCode();
    }
}
