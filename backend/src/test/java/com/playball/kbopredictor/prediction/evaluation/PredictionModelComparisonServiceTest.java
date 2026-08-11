package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.*;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PredictionModelComparisonServiceTest {

    @Test
    void validationMustStartStrictlyAfterTraining() {
        HistoricalModelDatasetService dataset =
                mock(HistoricalModelDatasetService.class);
        PredictionModelComparisonService service =
                new PredictionModelComparisonService(
                        dataset,
                        mock(PredictionMetricsCalculator.class),
                        mock(PredictionEngine.class),
                        mock(PredictionEngine.class),
                        new BaselineV1ModelProperties(),
                        new BaselineV2ModelProperties(),
                        mock(ActivePredictionEngine.class),
                        mock(BaselineV2TrainingService.class),
                        new BaselineV2ProbabilityModel()
                );

        assertThatThrownBy(() -> service.compare(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 8, 1),
                false
        )).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dataset);
    }
}
