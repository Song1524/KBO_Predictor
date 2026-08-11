package com.playball.kbopredictor.prediction.dataset;

import java.time.LocalDate;
import java.util.List;

public record HistoricalMlDatasetResponse(
        LocalDate from,
        LocalDate to,
        int snapshotCount,
        int exportedCount,
        int excludedForNoFeaturesCount,
        List<HistoricalMlDatasetRow> rows
) {
}
