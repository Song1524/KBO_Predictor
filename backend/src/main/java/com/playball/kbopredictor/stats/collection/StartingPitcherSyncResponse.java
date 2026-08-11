package com.playball.kbopredictor.stats.collection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record StartingPitcherSyncResponse(
        LocalDate gameDate,
        int sourceGameCount,
        int collectedPitcherCount,
        int insertedCount,
        int updatedCount,
        int pitcherStatSavedCount,
        int failedCount,
        List<String> errors,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
