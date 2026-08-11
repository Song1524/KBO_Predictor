package com.playball.kbopredictor.stats.entity;

import java.math.BigDecimal;

public record TeamRecentFormValues(
        BigDecimal recent5WinRate,
        BigDecimal recent10WinRate,
        BigDecimal recent5AvgRuns,
        BigDecimal recent5AvgRunsAllowed,
        BigDecimal recent10AvgRuns,
        BigDecimal recent10AvgRunsAllowed
) {
}
