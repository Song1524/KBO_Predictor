package com.playball.kbopredictor.prediction.engine;

public record BaselineV2Probability(
        double home,
        double draw,
        double away,
        double strength,
        double featureCoverage
) {
}
