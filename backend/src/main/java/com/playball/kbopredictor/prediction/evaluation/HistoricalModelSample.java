package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

import java.time.LocalDate;

public record HistoricalModelSample(
        Long gameId,
        LocalDate gameDate,
        PredictionFeatures features,
        PredictionOutcome actualOutcome
) {
}
