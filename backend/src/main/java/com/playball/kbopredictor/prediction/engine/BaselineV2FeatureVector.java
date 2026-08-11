package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

import java.math.BigDecimal;

public record BaselineV2FeatureVector(
        Double seasonWinRateDifference,
        Double recent5WinRateDifference,
        Double recent10WinRateDifference,
        Double recent5RunDifference,
        Double recent10RunDifference,
        Double venueWinRateDifference
) {

    public static BaselineV2FeatureVector from(
            PredictionFeatures features,
            double winRateScale,
            double runDifferenceScale
    ) {
        return new BaselineV2FeatureVector(
                difference(features.home().seasonWinRate(),
                        features.away().seasonWinRate(), winRateScale),
                difference(features.home().recent5WinRate(),
                        features.away().recent5WinRate(), winRateScale),
                difference(features.home().recent10WinRate(),
                        features.away().recent10WinRate(), winRateScale),
                runDifference(
                        features.home().recent5AvgRuns(),
                        features.home().recent5AvgRunsAllowed(),
                        features.away().recent5AvgRuns(),
                        features.away().recent5AvgRunsAllowed(),
                        runDifferenceScale
                ),
                runDifference(
                        features.home().recent10AvgRuns(),
                        features.home().recent10AvgRunsAllowed(),
                        features.away().recent10AvgRuns(),
                        features.away().recent10AvgRunsAllowed(),
                        runDifferenceScale
                ),
                difference(features.home().venueWinRate(),
                        features.away().venueWinRate(), winRateScale)
        );
    }

    public Double value(int index) {
        return switch (index) {
            case 0 -> seasonWinRateDifference;
            case 1 -> recent5WinRateDifference;
            case 2 -> recent10WinRateDifference;
            case 3 -> recent5RunDifference;
            case 4 -> recent10RunDifference;
            case 5 -> venueWinRateDifference;
            default -> throw new IllegalArgumentException("Unknown feature index: " + index);
        };
    }

    private static Double difference(
            BigDecimal home,
            BigDecimal away,
            double scale
    ) {
        if (home == null || away == null) {
            return null;
        }
        return clamp((home.doubleValue() - away.doubleValue()) / scale);
    }

    private static Double runDifference(
            BigDecimal homeRuns,
            BigDecimal homeAllowed,
            BigDecimal awayRuns,
            BigDecimal awayAllowed,
            double scale
    ) {
        if (homeRuns == null || homeAllowed == null
                || awayRuns == null || awayAllowed == null) {
            return null;
        }
        double homeNet = homeRuns.subtract(homeAllowed).doubleValue();
        double awayNet = awayRuns.subtract(awayAllowed).doubleValue();
        return clamp((homeNet - awayNet) / scale);
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
