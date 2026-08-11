package com.playball.kbopredictor.prediction.engine;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.prediction.baseline-v1")
@Getter
@Setter
public class BaselineV1ModelProperties {

    private String modelVersion = "baseline-v1";

    private double seasonWinRateWeight = 0.18;
    private double recent5WinRateWeight = 0.10;
    private double recent10WinRateWeight = 0.07;
    private double recent5RunDiffWeight = 0.12;
    private double recent10RunDiffWeight = 0.08;
    private double venueWinRateWeight = 0.10;
    private double battingAverageWeight = 0.10;
    private double teamEraWeight = 0.10;
    private double starterEraWeight = 0.10;
    private double starterWhipWeight = 0.05;

    private double winRateDifferenceScale = 0.25;
    private double runDifferenceScale = 4.0;
    private double battingAverageDifferenceScale = 0.040;
    private double teamEraDifferenceScale = 2.50;
    private double starterEraDifferenceScale = 2.50;
    private double starterWhipDifferenceScale = 0.50;

    private double homeAdvantage = 0.04;
    private double logisticScale = 1.80;
    private double drawMinProbability = 0.05;
    private double drawMaxProbability = 0.12;
    private double fullStrengthCoverage = 0.50;
    private double lowDataCoverage = 0.50;
    private int maxReasons = 4;
}
