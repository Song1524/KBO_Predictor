package com.playball.kbopredictor.prediction.shadow;

public record ShadowSampleSizeAssessment(
        int commonFinalGameCount,
        int homeWinCount,
        int drawCount,
        int awayWinCount,
        int bootstrapMinimumGameCount,
        boolean bootstrapEligible,
        int advisoryPromotionMinimumGameCount,
        int advisoryPromotionMinimumPerOutcomeCount,
        boolean advisoryPromotionSampleSizeReached,
        int additionalCommonGamesNeeded,
        int additionalDrawsNeeded,
        String recommendation
) {
}
