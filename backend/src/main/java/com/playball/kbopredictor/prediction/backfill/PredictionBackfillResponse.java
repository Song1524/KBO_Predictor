package com.playball.kbopredictor.prediction.backfill;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PredictionBackfillResponse(
        LocalDate from,
        LocalDate to,
        HistoricalGameSyncSummary gameSync,
        int finishedGameCount,
        int snapshotCreatedCount,
        int snapshotExistingCount,
        int historyCreatedCount,
        int historyExistingCount,
        int failedGameCount,
        List<String> errors,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public PredictionBackfillResponse {
        errors = List.copyOf(errors);
    }
}
