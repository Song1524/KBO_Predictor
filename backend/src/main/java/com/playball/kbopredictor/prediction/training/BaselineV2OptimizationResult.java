package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;

import java.math.BigDecimal;

public record BaselineV2OptimizationResult(
        BaselineV2Parameters parameters,
        int candidateCount,
        BigDecimal logLoss,
        BigDecimal brierScore,
        BigDecimal accuracy
) {
}
