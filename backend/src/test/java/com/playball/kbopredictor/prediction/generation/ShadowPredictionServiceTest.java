package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.history.PredictionStage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ShadowPredictionServiceTest {

    @Test
    void usesTheExactOperationalFeatureObjectAndStoresArtifactHash() {
        PredictionEngine engine = mock(PredictionEngine.class);
        LogisticModelArtifactLoader loader = mock(LogisticModelArtifactLoader.class);
        ShadowPredictionWriter writer = mock(ShadowPredictionWriter.class);
        PredictionFeatures features = mock(PredictionFeatures.class);
        when(features.gameId()).thenReturn(10L);
        PredictionEngineResult result = new PredictionEngineResult(
                new BigDecimal("52.00"), new BigDecimal("3.00"),
                new BigDecimal("45.00"), PredictionOutcome.HOME_WIN,
                "logistic-v1", new BigDecimal("1.000"), List.of("reason")
        );
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 11, 17, 0);
        SystemPredictionWriteResult operational = new SystemPredictionWriteResult(
                new SystemPredictionGenerationResponse(
                        10L, SystemPredictionGenerationStatus.CREATED,
                        PredictionOutcome.HOME_WIN, new BigDecimal("58.00"),
                        new BigDecimal("8.00"), new BigDecimal("34.00"),
                        "baseline-v1", new BigDecimal("1.000"), generatedAt, "ok"
                ),
                99L,
                PredictionStage.INITIAL
        );
        when(engine.predict(features)).thenReturn(result);
        when(loader.artifactSha256()).thenReturn("ABC123");
        when(writer.write(anyLong(), anyLong(), any(), any(), anyString(), any()))
                .thenReturn(true);

        ShadowPredictionService service = new ShadowPredictionService(
                engine, loader, writer
        );

        assertThat(service.generate(features, operational)).isTrue();
        verify(engine).predict(same(features));
        verify(writer).write(
                10L, 99L, PredictionStage.INITIAL,
                result, "ABC123", generatedAt
        );
    }
}
