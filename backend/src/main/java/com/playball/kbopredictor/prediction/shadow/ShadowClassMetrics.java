package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;

public record ShadowClassMetrics(
        BigDecimal precision,
        BigDecimal recall,
        BigDecimal f1,
        int support
) {
}
