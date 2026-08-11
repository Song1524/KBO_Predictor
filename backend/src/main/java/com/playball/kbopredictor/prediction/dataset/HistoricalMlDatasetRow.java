package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A training/evaluation-only row. actualResult must never be copied to the
 * operational PredictionFeatures contract.
 */
public record HistoricalMlDatasetRow(
        Long gameId,
        Integer season,
        LocalDate gameDate,
        BigDecimal seasonWinRateDiff,
        BigDecimal recent5WinRateDiff,
        BigDecimal recent10WinRateDiff,
        BigDecimal recent5RunDiff,
        BigDecimal recent10RunDiff,
        BigDecimal homeAwayWinRateDiff,
        int availableFeatureCount,
        BigDecimal featureCoverage,
        PredictionOutcome actualResult
) {
}
