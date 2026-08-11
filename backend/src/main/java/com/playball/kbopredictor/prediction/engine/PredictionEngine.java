package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

public interface PredictionEngine {

    PredictionEngineResult predict(PredictionFeatures features);
}
