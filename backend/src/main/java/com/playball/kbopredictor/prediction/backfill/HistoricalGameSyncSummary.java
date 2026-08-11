package com.playball.kbopredictor.prediction.backfill;

import java.util.List;

public record HistoricalGameSyncSummary(
        boolean requested,
        int requestedMonthCount,
        int successfulMonthCount,
        int failedMonthCount,
        int insertedGameCount,
        int updatedGameCount,
        List<String> errors
) {

    public HistoricalGameSyncSummary {
        errors = List.copyOf(errors);
    }

    public static HistoricalGameSyncSummary notRequested() {
        return new HistoricalGameSyncSummary(
                false, 0, 0, 0, 0, 0, List.of()
        );
    }
}
