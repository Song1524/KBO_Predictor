package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.*;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.evaluation.*;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaselineV2TrainingServiceTest {

    @Mock
    private HistoricalModelDatasetService datasetService;
    @Mock
    private BaselineV2CandidateGenerator candidateGenerator;
    @Mock
    private BaselineV2ParameterOptimizer optimizer;
    @Mock
    private PredictionMetricsCalculator metricsCalculator;

    @Test
    void parameterSearchReceivesOnlyExplicitTrainingPeriod() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 7, 9);
        HistoricalModelSample sample = sample(to);
        BaselineV2Parameters parameters = new BaselineV2ModelProperties()
                .toParameters();
        List<BaselineV2Parameters> candidates = List.of(parameters);
        BaselineV2OptimizationResult optimized =
                new BaselineV2OptimizationResult(
                        parameters, 1,
                        new BigDecimal("0.8"),
                        new BigDecimal("0.5"),
                        new BigDecimal("50.0")
                );
        ModelEvaluationMetrics metrics = new ModelEvaluationMetrics(
                "baseline-v2-trained", 1, 1,
                new BigDecimal("100.00"),
                1, new BigDecimal("100.00"),
                0, null,
                0, null,
                new BigDecimal("0.5"),
                new BigDecimal("0.3")
        );
        when(datasetService.load(from, to)).thenReturn(List.of(sample));
        when(candidateGenerator.generate(1, 7L)).thenReturn(candidates);
        when(optimizer.optimize(any(), eq(candidates))).thenReturn(optimized);
        when(metricsCalculator.evaluate(any(), any(), any())).thenReturn(metrics);
        BaselineV2TrainingService service = new BaselineV2TrainingService(
                datasetService,
                candidateGenerator,
                optimizer,
                new BaselineV2ProbabilityModel(),
                metricsCalculator,
                new BaselineV2ModelProperties()
        );

        BaselineV2TrainingResult result = service.train(from, to, 1, 7L);

        assertThat(result.trainingFrom()).isEqualTo(from);
        assertThat(result.trainingTo()).isEqualTo(to);
        verify(datasetService).load(from, to);
        verify(candidateGenerator).generate(1, 7L);
    }

    private HistoricalModelSample sample(LocalDate date) {
        TeamPredictionFeatures home = team("0.600");
        TeamPredictionFeatures away = team("0.400");
        return new HistoricalModelSample(
                1L,
                date,
                new PredictionFeatures(
                        1L, date, LocalDateTime.of(date, java.time.LocalTime.NOON),
                        home, away
                ),
                PredictionOutcome.HOME_WIN
        );
    }

    private TeamPredictionFeatures team(String rate) {
        BigDecimal value = new BigDecimal(rate);
        return new TeamPredictionFeatures(
                1L, "team", true, LocalDate.of(2026, 7, 8),
                value, value, value,
                BigDecimal.valueOf(5), BigDecimal.valueOf(4),
                BigDecimal.valueOf(5), BigDecimal.valueOf(4),
                null, null, value, null
        );
    }
}
