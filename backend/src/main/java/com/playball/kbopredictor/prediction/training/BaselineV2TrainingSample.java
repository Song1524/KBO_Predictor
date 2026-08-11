package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.BaselineV2FeatureVector;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.time.LocalDate;

public record BaselineV2TrainingSample(
        LocalDate gameDate,
        BaselineV2FeatureVector features,
        PredictionOutcome actualOutcome
) {
}
