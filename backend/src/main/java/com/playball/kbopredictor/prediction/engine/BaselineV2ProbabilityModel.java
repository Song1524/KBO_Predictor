package com.playball.kbopredictor.prediction.engine;

import org.springframework.stereotype.Component;

@Component
public class BaselineV2ProbabilityModel {

    public BaselineV2Probability predict(
            BaselineV2FeatureVector vector,
            BaselineV2Parameters parameters
    ) {
        double[] weights = {
                parameters.seasonWinRateWeight(),
                parameters.recent5WinRateWeight(),
                parameters.recent10WinRateWeight(),
                parameters.recent5RunDiffWeight(),
                parameters.recent10RunDiffWeight(),
                parameters.venueWinRateWeight()
        };
        double availableWeight = 0.0;
        double weightedDifference = 0.0;
        for (int index = 0; index < weights.length; index++) {
            Double value = vector.value(index);
            if (value == null || weights[index] <= 0.0) {
                continue;
            }
            availableWeight += weights[index];
            weightedDifference += weights[index] * value;
        }

        double normalizedStrength = availableWeight == 0.0
                ? 0.0
                : weightedDifference / availableWeight;
        double coverage = availableWeight == 0.0
                ? 0.0
                : Math.min(1.0, availableWeight / parameters.totalWeight());
        double reliability = parameters.lowCoverageShrink()
                + (1.0 - parameters.lowCoverageShrink()) * coverage;
        double strength = clamp(
                normalizedStrength * reliability + parameters.homeAdvantage(),
                -1.0,
                1.0
        );
        double closeness = Math.pow(
                Math.max(0.0, 1.0 - Math.abs(strength)),
                parameters.drawStrengthExponent()
        );
        double draw = parameters.drawMinProbability()
                + (parameters.drawMaxProbability()
                - parameters.drawMinProbability()) * closeness;
        double homeShare = 1.0 / (1.0
                + Math.exp(-parameters.logisticScale() * strength));
        double home = (1.0 - draw) * homeShare;
        return new BaselineV2Probability(
                home,
                draw,
                1.0 - home - draw,
                strength,
                coverage
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
