package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShadowMetricCalculatorTest {

    @Test
    void producesDeterministicPairedBootstrapAfterMinimumSampleCount() {
        ShadowMetricCalculator calculator = new ShadowMetricCalculator();
        List<ShadowMetricCalculator.EvaluationPrediction> baseline =
                new ArrayList<>();
        List<ShadowMetricCalculator.EvaluationPrediction> logistic =
                new ArrayList<>();
        for (int index = 0;
             index < ShadowMetricCalculator.MIN_BOOTSTRAP_GAME_COUNT;
             index++) {
            PredictionOutcome actual = PredictionOutcome.values()[index % 3];
            baseline.add(value(actual, history(actual)));
            logistic.add(value(actual, history(actual)));
        }

        var comparison = calculator.pairedMetrics(baseline, logistic);

        assertThat(comparison.get("accuracy").bootstrapRepetitions())
                .isEqualTo(ShadowMetricCalculator.BOOTSTRAP_REPETITIONS);
        assertThat(comparison.get("accuracy").bootstrap95Lower())
                .isEqualByComparingTo("0.000000");
        assertThat(comparison.get("accuracy").bootstrap95Upper())
                .isEqualByComparingTo("0.000000");
        assertThat(comparison.get("logLoss").logisticMinusBaseline())
                .isEqualByComparingTo("0.000000");
        assertThat(comparison.get("brierScore").logisticMinusBaseline())
                .isEqualByComparingTo("0.000000");
    }

    private ShadowMetricCalculator.EvaluationPrediction value(
            PredictionOutcome actual,
            SystemPredictionHistory history
    ) {
        return new ShadowMetricCalculator.EvaluationPrediction(actual, history);
    }

    private SystemPredictionHistory history(PredictionOutcome predicted) {
        SystemPredictionHistory history = mock(SystemPredictionHistory.class);
        when(history.getPredictedOutcome()).thenReturn(predicted);
        when(history.getHomeWinProbability()).thenReturn(new BigDecimal("60.00"));
        when(history.getDrawProbability()).thenReturn(new BigDecimal("5.00"));
        when(history.getAwayWinProbability()).thenReturn(new BigDecimal("35.00"));
        return history;
    }
}
