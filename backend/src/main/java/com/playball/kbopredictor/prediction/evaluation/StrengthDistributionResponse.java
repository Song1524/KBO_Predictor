package com.playball.kbopredictor.prediction.evaluation;

import java.math.BigDecimal;

public record StrengthDistributionResponse(
        int sampleCount,
        BigDecimal averageAbsoluteStrength,
        BigDecimal medianAbsoluteStrength,
        BigDecimal minimumAbsoluteStrength,
        BigDecimal maximumAbsoluteStrength
) {
}
