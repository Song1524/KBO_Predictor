package com.playball.kbopredictor.prediction.evaluation;

import java.math.BigDecimal;

public record DrawFeatureDifferenceResponse(
        BigDecimal seasonWinRate,
        BigDecimal recent5WinRate,
        BigDecimal recent10WinRate,
        BigDecimal recent5RunDifferential,
        BigDecimal recent10RunDifferential,
        BigDecimal venueWinRate
) {
}
