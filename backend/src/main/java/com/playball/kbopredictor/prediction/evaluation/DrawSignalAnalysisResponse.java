package com.playball.kbopredictor.prediction.evaluation;

public record DrawSignalAnalysisResponse(
        String analysisDataset,
        StrengthDistributionResponse drawStrength,
        StrengthDistributionResponse nonDrawStrength,
        DrawFeatureDifferenceResponse drawAverageAbsoluteDifferences,
        DrawFeatureDifferenceResponse nonDrawAverageAbsoluteDifferences,
        String conclusion
) {
}
