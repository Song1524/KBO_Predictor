package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;
import java.util.List;

public record ShadowClassCalibration(
        String outcome,
        BigDecimal averageProbability,
        BigDecimal actualRate,
        BigDecimal expectedCalibrationError,
        List<ShadowCalibrationBin> bins
) {
    public ShadowClassCalibration {
        bins = List.copyOf(bins);
    }
}
