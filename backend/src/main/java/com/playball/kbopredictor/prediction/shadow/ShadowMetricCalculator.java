package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class ShadowMetricCalculator {

    private static final List<PredictionOutcome> ORDER = List.of(
            PredictionOutcome.HOME_WIN,
            PredictionOutcome.DRAW,
            PredictionOutcome.AWAY_WIN
    );
    private static final double EPSILON = 1.0e-15;

    public ShadowModelMetrics calculate(
            String modelVersion,
            List<EvaluationPrediction> values
    ) {
        int n = values.size();
        int correct = 0;
        double logLoss = 0.0;
        double brier = 0.0;
        double maxProbability = 0.0;
        int[][] matrix = new int[ORDER.size()][ORDER.size()];
        for (EvaluationPrediction value : values) {
            int actualIndex = ORDER.indexOf(value.actual());
            int predictedIndex = ORDER.indexOf(value.history().getPredictedOutcome());
            matrix[actualIndex][predictedIndex]++;
            if (actualIndex == predictedIndex) correct++;
            double actualProbability = probability(value.history(), value.actual());
            logLoss -= Math.log(Math.max(EPSILON, actualProbability));
            double highest = 0.0;
            for (PredictionOutcome outcome : ORDER) {
                double probability = probability(value.history(), outcome);
                double expected = outcome == value.actual() ? 1.0 : 0.0;
                brier += Math.pow(probability - expected, 2.0);
                highest = Math.max(highest, probability);
            }
            maxProbability += highest;
        }

        Map<String, ShadowClassMetrics> classes = new LinkedHashMap<>();
        double macroF1 = 0.0;
        for (int index = 0; index < ORDER.size(); index++) {
            int tp = matrix[index][index];
            int support = Arrays.stream(matrix[index]).sum();
            int predicted = 0;
            for (int row = 0; row < ORDER.size(); row++) {
                predicted += matrix[row][index];
            }
            double precision = predicted == 0 ? 0.0 : (double) tp / predicted;
            double recall = support == 0 ? 0.0 : (double) tp / support;
            double f1 = precision + recall == 0.0
                    ? 0.0 : 2.0 * precision * recall / (precision + recall);
            macroF1 += f1;
            classes.put(ORDER.get(index).name(), new ShadowClassMetrics(
                    decimal(precision), decimal(recall), decimal(f1), support
            ));
        }
        return new ShadowModelMetrics(
                modelVersion,
                n,
                decimal(n == 0 ? 0.0 : (double) correct / n),
                decimal(n == 0 ? 0.0 : logLoss / n),
                decimal(n == 0 ? 0.0 : brier / n),
                decimal(macroF1 / ORDER.size()),
                decimal(n == 0 ? 0.0 : maxProbability / n),
                Collections.unmodifiableMap(classes),
                confusion(matrix)
        );
    }

    public ShadowDrawProbabilityMetrics drawMetrics(
            String modelVersion,
            List<EvaluationPrediction> values
    ) {
        List<Double> draws = new ArrayList<>();
        List<Double> nonDraws = new ArrayList<>();
        for (EvaluationPrediction value : values) {
            double probability = probability(value.history(), PredictionOutcome.DRAW);
            (value.actual() == PredictionOutcome.DRAW ? draws : nonDraws)
                    .add(probability);
        }
        draws.sort(Double::compareTo);
        return new ShadowDrawProbabilityMetrics(
                modelVersion,
                draws.size(),
                average(draws),
                average(nonDraws),
                percentile(draws, 0.0),
                percentile(draws, 0.25),
                percentile(draws, 0.50),
                percentile(draws, 0.75),
                percentile(draws, 1.0)
        );
    }

    public double probability(
            SystemPredictionHistory history,
            PredictionOutcome outcome
    ) {
        BigDecimal percentage = switch (outcome) {
            case HOME_WIN -> history.getHomeWinProbability();
            case DRAW -> history.getDrawProbability();
            case AWAY_WIN -> history.getAwayWinProbability();
        };
        return percentage.doubleValue() / 100.0;
    }

    private Map<String, Map<String, Integer>> confusion(int[][] matrix) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (int row = 0; row < ORDER.size(); row++) {
            Map<String, Integer> predictions = new LinkedHashMap<>();
            for (int column = 0; column < ORDER.size(); column++) {
                predictions.put(ORDER.get(column).name(), matrix[row][column]);
            }
            result.put(ORDER.get(row).name(), Collections.unmodifiableMap(predictions));
        }
        return Collections.unmodifiableMap(result);
    }

    private BigDecimal average(List<Double> values) {
        return values.isEmpty() ? null
                : decimal(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private BigDecimal percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) return null;
        double position = quantile * (values.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double value = values.get(lower);
        if (upper != lower) {
            value += (values.get(upper) - value) * (position - lower);
        }
        return decimal(value);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    public record EvaluationPrediction(
            PredictionOutcome actual,
            SystemPredictionHistory history
    ) {
    }
}
