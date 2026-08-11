package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivePredictionEngineTest {

    @Test
    void defaultsToV1AndCanSelectEveryVersionWithoutChangingTheEngines() {
        ActivePredictionModelProperties properties =
                new ActivePredictionModelProperties();
        PredictionEngine v1 = mock(PredictionEngine.class);
        PredictionEngine v2 = mock(PredictionEngine.class);
        PredictionEngine logistic = mock(PredictionEngine.class);
        PredictionFeatures features = mock(PredictionFeatures.class);
        PredictionEngineResult v1Result = mock(PredictionEngineResult.class);
        PredictionEngineResult v2Result = mock(PredictionEngineResult.class);
        PredictionEngineResult logisticResult = mock(PredictionEngineResult.class);
        when(v1.predict(features)).thenReturn(v1Result);
        when(v2.predict(features)).thenReturn(v2Result);
        when(logistic.predict(features)).thenReturn(logisticResult);
        ActivePredictionEngine active = new ActivePredictionEngine(
                properties, v1, v2, logistic
        );

        assertThat(active.activeModel()).isEqualTo("baseline-v1");
        assertThat(active.predict(features)).isSameAs(v1Result);

        properties.setActiveModel("baseline-v2");
        assertThat(active.predict(features)).isSameAs(v2Result);

        properties.setActiveModel("logistic-v1");
        assertThat(active.predict(features)).isSameAs(logisticResult);
        verify(v1).predict(features);
        verify(v2).predict(features);
        verify(logistic).predict(features);
    }
}
