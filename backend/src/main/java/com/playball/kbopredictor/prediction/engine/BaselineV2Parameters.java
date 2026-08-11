package com.playball.kbopredictor.prediction.engine;

public record BaselineV2Parameters(
        double seasonWinRateWeight,
        double recent5WinRateWeight,
        double recent10WinRateWeight,
        double recent5RunDiffWeight,
        double recent10RunDiffWeight,
        double venueWinRateWeight,
        double homeAdvantage,
        double logisticScale,
        double drawMinProbability,
        double drawMaxProbability,
        double drawStrengthExponent,
        double lowCoverageShrink
) {

    public BaselineV2Parameters {
        double weightSum = seasonWinRateWeight
                + recent5WinRateWeight
                + recent10WinRateWeight
                + recent5RunDiffWeight
                + recent10RunDiffWeight
                + venueWinRateWeight;
        if (weightSum <= 0.0) {
            throw new IllegalArgumentException("Feature weight sum must be positive.");
        }
        if (logisticScale <= 0.0) {
            throw new IllegalArgumentException("Logistic scale must be positive.");
        }
        if (drawMinProbability < 0.0
                || drawMaxProbability < drawMinProbability
                || drawMaxProbability >= 1.0) {
            throw new IllegalArgumentException("Invalid draw probability range.");
        }
        if (drawStrengthExponent <= 0.0) {
            throw new IllegalArgumentException("Draw exponent must be positive.");
        }
        if (lowCoverageShrink < 0.0 || lowCoverageShrink > 1.0) {
            throw new IllegalArgumentException("Coverage shrink must be between 0 and 1.");
        }
    }

    public double totalWeight() {
        return seasonWinRateWeight
                + recent5WinRateWeight
                + recent10WinRateWeight
                + recent5RunDiffWeight
                + recent10RunDiffWeight
                + venueWinRateWeight;
    }
}
