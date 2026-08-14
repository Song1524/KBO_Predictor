package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;

public record ShadowCalibrationBin(
        String range,
        int sampleCount,
        BigDecimal averageProbability,
        BigDecimal actualRate,
        BigDecimal probabilityMinusActual
) {
}
