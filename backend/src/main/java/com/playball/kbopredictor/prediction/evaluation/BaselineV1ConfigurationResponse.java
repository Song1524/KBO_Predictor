package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.BaselineV1ModelProperties;

public record BaselineV1ConfigurationResponse(
        String modelVersion,
        double seasonWinRateWeight,
        double recent5WinRateWeight,
        double recent10WinRateWeight,
        double recent5RunDiffWeight,
        double recent10RunDiffWeight,
        double venueWinRateWeight,
        double battingAverageWeight,
        double teamEraWeight,
        double starterEraWeight,
        double starterWhipWeight,
        double homeAdvantage,
        double logisticScale,
        double drawMinProbability,
        double drawMaxProbability,
        double fullStrengthCoverage
) {
    public static BaselineV1ConfigurationResponse from(
            BaselineV1ModelProperties value
    ) {
        return new BaselineV1ConfigurationResponse(
                value.getModelVersion(),
                value.getSeasonWinRateWeight(),
                value.getRecent5WinRateWeight(),
                value.getRecent10WinRateWeight(),
                value.getRecent5RunDiffWeight(),
                value.getRecent10RunDiffWeight(),
                value.getVenueWinRateWeight(),
                value.getBattingAverageWeight(),
                value.getTeamEraWeight(),
                value.getStarterEraWeight(),
                value.getStarterWhipWeight(),
                value.getHomeAdvantage(),
                value.getLogisticScale(),
                value.getDrawMinProbability(),
                value.getDrawMaxProbability(),
                value.getFullStrengthCoverage()
        );
    }
}
