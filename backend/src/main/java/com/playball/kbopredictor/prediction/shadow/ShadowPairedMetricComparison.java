package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;

public record ShadowPairedMetricComparison(
        String metric,
        String preferredDirection,
        BigDecimal baseline,
        BigDecimal logistic,
        BigDecimal logisticMinusBaseline,
        BigDecimal bootstrap95Lower,
        BigDecimal bootstrap95Upper,
        int bootstrapRepetitions
) {
}
