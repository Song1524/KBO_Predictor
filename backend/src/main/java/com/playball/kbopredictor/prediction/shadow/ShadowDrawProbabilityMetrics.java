package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;

public record ShadowDrawProbabilityMetrics(
        String modelVersion,
        int actualDrawCount,
        BigDecimal averageOnActualDraw,
        BigDecimal averageOnNonDraw,
        BigDecimal minimumOnActualDraw,
        BigDecimal percentile25OnActualDraw,
        BigDecimal medianOnActualDraw,
        BigDecimal percentile75OnActualDraw,
        BigDecimal maximumOnActualDraw
) {
}
