package com.playball.kbopredictor.admin.dto;

import java.time.LocalDate;

public record AdminDashboardSummaryResponse(
        LocalDate date,
        long totalGameCount,
        long scheduledGameCount,
        long inProgressGameCount,
        long finishedGameCount,
        long cancelledGameCount,
        long systemPredictionCount,
        long shadowPredictionGameCount,
        long pendingUserPredictionCount,
        String productionModelVersion,
        String shadowModelVersion,
        String shadowArtifactSha256
) {
}
