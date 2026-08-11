package com.playball.kbopredictor.prediction.engine;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@ConfigurationProperties(prefix = "app.prediction.baseline-v2")
@Getter
@Setter
public class BaselineV2ModelProperties {

    private String modelVersion = "baseline-v2";
    private LocalDate trainingFrom = LocalDate.of(2026, 5, 1);
    private LocalDate trainingTo = LocalDate.of(2026, 7, 9);
    private LocalDate validationFrom = LocalDate.of(2026, 7, 16);
    private LocalDate validationTo = LocalDate.of(2026, 8, 1);
    private String objective = "LOG_LOSS_THEN_BRIER_THEN_ACCURACY";
    private int searchCandidateCount = 20000;
    private long searchSeed = 20260811L;
    private double objectiveScore = 0.774869224;

    private double seasonWinRateWeight = 0.437500;
    private double recent5WinRateWeight = 0.062500;
    private double recent10WinRateWeight = 0.075000;
    private double recent5RunDiffWeight = 0.062500;
    private double recent10RunDiffWeight = 0.112500;
    private double venueWinRateWeight = 0.250000;
    private double homeAdvantage = 0.06;
    private double logisticScale = 0.80;
    private double drawMinProbability = 0.020;
    private double drawMaxProbability = 0.025;
    private double drawStrengthExponent = 3.0;
    private double lowCoverageShrink = 0.50;

    private double winRateDifferenceScale = 0.25;
    private double runDifferenceScale = 4.0;
    private int maxReasons = 4;
    private boolean operationalExtraFeaturesEnabled = false;

    public BaselineV2Parameters toParameters() {
        return new BaselineV2Parameters(
                seasonWinRateWeight,
                recent5WinRateWeight,
                recent10WinRateWeight,
                recent5RunDiffWeight,
                recent10RunDiffWeight,
                venueWinRateWeight,
                homeAdvantage,
                logisticScale,
                drawMinProbability,
                drawMaxProbability,
                drawStrengthExponent,
                lowCoverageShrink
        );
    }
}
