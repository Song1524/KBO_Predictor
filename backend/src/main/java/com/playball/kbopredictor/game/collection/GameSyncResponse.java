package com.playball.kbopredictor.game.collection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record GameSyncResponse(
        LocalDate targetDate,
        int sourceRowCount,
        int collectedGameCount,
        int insertedCount,
        int updatedCount,
        int statusChangedCount,
        int finishedCount,
        int cancelledCount,
        int settlementSuccessCount,
        int failedCount,
        List<String> errors,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public GameSyncResponse {
        errors = List.copyOf(errors);
    }

    public GameSyncResponse(
            LocalDate targetDate,
            int sourceRowCount,
            int collectedGameCount,
            int insertedCount,
            int updatedCount,
            int failedCount,
            List<String> errors,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
        this(
                targetDate,
                sourceRowCount,
                collectedGameCount,
                insertedCount,
                updatedCount,
                0,
                0,
                0,
                0,
                failedCount,
                errors,
                startedAt,
                finishedAt
        );
    }
}
