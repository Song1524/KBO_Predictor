package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.math.BigDecimal;
import java.util.List;

public record PredictionEngineResult(
        BigDecimal homeWinProbability,
        BigDecimal drawProbability,
        BigDecimal awayWinProbability,
        PredictionOutcome predictedOutcome,
        String modelVersion,
        BigDecimal featureCoverage,
        List<String> reasons
) {
}
