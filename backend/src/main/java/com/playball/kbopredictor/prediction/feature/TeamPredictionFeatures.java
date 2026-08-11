package com.playball.kbopredictor.prediction.feature;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TeamPredictionFeatures(
        Long teamId,
        String teamName,
        boolean teamStatsAvailable,
        LocalDate teamStatDate,
        BigDecimal seasonWinRate,
        BigDecimal recent5WinRate,
        BigDecimal recent10WinRate,
        BigDecimal recent5AvgRuns,
        BigDecimal recent5AvgRunsAllowed,
        BigDecimal recent10AvgRuns,
        BigDecimal recent10AvgRunsAllowed,
        BigDecimal battingAverage,
        BigDecimal era,
        BigDecimal venueWinRate,
        StartingPitcherFeatures startingPitcher
) {
}
