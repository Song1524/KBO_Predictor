package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

public interface PredictionEngine {

    /** Unknown identities are conservatively recalculated. */
    default String modelVersion() { return null; }

    PredictionEngineResult predict(PredictionFeatures features);
}
