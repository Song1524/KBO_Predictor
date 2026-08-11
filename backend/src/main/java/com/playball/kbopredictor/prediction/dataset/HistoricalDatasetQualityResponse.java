package com.playball.kbopredictor.prediction.dataset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record HistoricalDatasetQualityResponse(
        LocalDate from,
        LocalDate to,
        String dataSource,
        String historicalCutoffPolicy,
        String excludedEventPolicy,
        DatasetQualitySlice overall,
        List<DatasetQualitySlice> seasons,
        TeamMappingQuality teamMapping
) {

    public record DatasetQualitySlice(
            String label,
            Integer season,
            int totalGameCount,
            int finishedGameCount,
            int cancelledGameCount,
            int featureSnapshotCount,
            int featureGeneratedGameCount,
            int finishedWithoutSnapshotCount,
            int homeWinCount,
            int drawCount,
            int awayWinCount,
            BigDecimal averageFeatureCoverage,
            int recent5AvailableCount,
            BigDecimal recent5AvailableRate,
            int recent10AvailableCount,
            BigDecimal recent10AvailableRate,
            int noPriorGameCount,
            int fewerThan5PriorGamesCount,
            int fewerThan10PriorGamesCount
    ) {
    }

    public record TeamMappingQuality(
            boolean valid,
            Set<String> expectedCodes,
            Set<String> presentCodes,
            Set<String> missingCodes,
            Set<String> unexpectedCodes,
            int teamsWithoutCode
    ) {
    }
}
