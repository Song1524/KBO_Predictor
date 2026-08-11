package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Primary
public class ActivePredictionEngine implements PredictionEngine {

    private final ActivePredictionModelProperties properties;
    private final Map<String, PredictionEngine> engines;

    public ActivePredictionEngine(
            ActivePredictionModelProperties properties,
            @Qualifier("baselinePredictionEngine") PredictionEngine baselineV1,
            @Qualifier("baselineV2PredictionEngine") PredictionEngine baselineV2,
            @Qualifier("logisticRegressionPredictionEngine")
            PredictionEngine logisticV1
    ) {
        this.properties = properties;
        this.engines = Map.of(
                "baseline-v1", baselineV1,
                "baseline-v2", baselineV2,
                "logistic-v1", logisticV1
        );
    }

    @Override
    public PredictionEngineResult predict(PredictionFeatures features) {
        return engine(properties.getActiveModel()).predict(features);
    }

    public PredictionEngine engine(String modelVersion) {
        PredictionEngine engine = engines.get(modelVersion);
        if (engine == null) {
            throw new IllegalArgumentException(
                    "Unsupported prediction model: " + modelVersion
            );
        }
        return engine;
    }

    public String activeModel() {
        return properties.getActiveModel();
    }
}
