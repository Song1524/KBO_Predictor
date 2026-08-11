package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BaselineV2PredictionEngine implements PredictionEngine {

    private final BaselineV2ModelProperties properties;
    private final BaselineV2PredictionCalculator calculator;

    @Override
    public PredictionEngineResult predict(PredictionFeatures features) {
        return calculator.predict(
                features,
                properties.toParameters(),
                properties.getModelVersion(),
                properties.getWinRateDifferenceScale(),
                properties.getRunDifferenceScale(),
                properties.getMaxReasons()
        );
    }
}
