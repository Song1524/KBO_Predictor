package com.playball.kbopredictor.stats.collection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TeamStatsSyncResponse(
        LocalDate statDate,
        int sourceTeamCount,
        int insertedCount,
        int updatedCount,
        int failedCount,
        List<String> errors,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
