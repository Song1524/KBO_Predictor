package com.playball.kbopredictor.prediction.feature;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StartingPitcherFeatures(
        Long playerId,
        String kboPlayerId,
        String playerName,
        boolean announcedBeforeGame,
        boolean statsAvailable,
        LocalDate statDate,
        BigDecimal era,
        Integer wins,
        Integer losses,
        String innings,
        BigDecimal whip
) {
}
