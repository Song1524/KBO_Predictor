package com.playball.kbopredictor.stats.collection;

import java.math.BigDecimal;

public record CollectedPitcherSeasonStat(
        Integer season,
        BigDecimal era,
        Integer wins,
        Integer losses,
        String innings,
        BigDecimal whip
) {
}
