package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class PredictionMetricsCalculator {

    private static final double MIN_PROBABILITY = 1.0e-15;

    public ModelEvaluationMetrics evaluate(
            String model,
            List<HistoricalModelSample> samples,
            Function<HistoricalModelSample, ModelProbabilities> predictor
    ) {
        Map<PredictionOutcome, Integer> counts = counts();
        Map<PredictionOutcome, Integer> correctCounts = counts();
        int correct = 0;
        double logLoss = 0.0;
        double brier = 0.0;
        for (HistoricalModelSample sample : samples) {
            ModelProbabilities probability = predictor.apply(sample);
            PredictionOutcome actual = sample.actualOutcome();
            counts.merge(actual, 1, Integer::sum);
            if (probability.predictedOutcome() == actual) {
                correct++;
                correctCounts.merge(actual, 1, Integer::sum);
            }
            double actualProbability = switch (actual) {
                case HOME_WIN -> probability.home();
                case DRAW -> probability.draw();
                case AWAY_WIN -> probability.away();
            };
            logLoss += -Math.log(Math.max(MIN_PROBABILITY, actualProbability));
            brier += square(probability.home() - indicator(actual, PredictionOutcome.HOME_WIN))
                    + square(probability.draw() - indicator(actual, PredictionOutcome.DRAW))
                    + square(probability.away() - indicator(actual, PredictionOutcome.AWAY_WIN));
        }
        int size = samples.size();
        return new ModelEvaluationMetrics(
                model,
                size,
                correct,
                percentage(correct, size),
                counts.get(PredictionOutcome.HOME_WIN),
                percentage(correctCounts.get(PredictionOutcome.HOME_WIN),
                        counts.get(PredictionOutcome.HOME_WIN)),
                counts.get(PredictionOutcome.DRAW),
                percentage(correctCounts.get(PredictionOutcome.DRAW),
                        counts.get(PredictionOutcome.DRAW)),
                counts.get(PredictionOutcome.AWAY_WIN),
                percentage(correctCounts.get(PredictionOutcome.AWAY_WIN),
                        counts.get(PredictionOutcome.AWAY_WIN)),
                average(logLoss, size, 6),
                average(brier, size, 6)
        );
    }

    private Map<PredictionOutcome, Integer> counts() {
        Map<PredictionOutcome, Integer> counts =
                new EnumMap<>(PredictionOutcome.class);
        for (PredictionOutcome outcome : PredictionOutcome.values()) {
            counts.put(outcome, 0);
        }
        return counts;
    }

    private BigDecimal percentage(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal average(double total, int count, int scale) {
        return count == 0
                ? null
                : BigDecimal.valueOf(total / count)
                .setScale(scale, RoundingMode.HALF_UP);
    }

    private double indicator(PredictionOutcome actual, PredictionOutcome target) {
        return actual == target ? 1.0 : 0.0;
    }

    private double square(double value) {
        return value * value;
    }
}
