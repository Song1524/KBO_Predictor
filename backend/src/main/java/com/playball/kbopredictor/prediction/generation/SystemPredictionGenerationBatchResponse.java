package com.playball.kbopredictor.prediction.generation;

import java.time.LocalDate;
import java.util.List;

public record SystemPredictionGenerationBatchResponse(
        LocalDate date,
        int targetCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failedCount,
        List<SystemPredictionGenerationResponse> results
) {
    public static SystemPredictionGenerationBatchResponse from(
            LocalDate date,
            List<SystemPredictionGenerationResponse> results
    ) {
        return new SystemPredictionGenerationBatchResponse(
                date,
                results.size(),
                count(results, SystemPredictionGenerationStatus.CREATED),
                count(results, SystemPredictionGenerationStatus.UPDATED),
                (int) results.stream()
                        .filter(result -> result.status()
                                == SystemPredictionGenerationStatus.SKIPPED_CLOSED
                                || result.status()
                                == SystemPredictionGenerationStatus.SKIPPED_NOT_SCHEDULED)
                        .count(),
                count(results, SystemPredictionGenerationStatus.FAILED),
                List.copyOf(results)
        );
    }

    private static int count(
            List<SystemPredictionGenerationResponse> results,
            SystemPredictionGenerationStatus status
    ) {
        return (int) results.stream()
                .filter(result -> result.status() == status)
                .count();
    }
}
