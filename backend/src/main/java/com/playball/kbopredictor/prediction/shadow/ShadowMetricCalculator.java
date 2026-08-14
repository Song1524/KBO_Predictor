package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class ShadowMetricCalculator {

    public static final int MIN_BOOTSTRAP_GAME_COUNT = 30;
    public static final int BOOTSTRAP_REPETITIONS = 10_000;

    private static final List<PredictionOutcome> ORDER = List.of(
            PredictionOutcome.HOME_WIN,
            PredictionOutcome.DRAW,
            PredictionOutcome.AWAY_WIN
    );
    private static final double EPSILON = 1.0e-15;
    private static final long BOOTSTRAP_SEED = 20260814L;

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
        double[] probabilitySums = new double[ORDER.size()];
        for (EvaluationPrediction value : values) {
            int actualIndex = ORDER.indexOf(value.actual());
            int predictedIndex = ORDER.indexOf(value.history().getPredictedOutcome());
            matrix[actualIndex][predictedIndex]++;
            if (actualIndex == predictedIndex) correct++;
            double actualProbability = probability(value.history(), value.actual());
            logLoss -= Math.log(Math.max(EPSILON, actualProbability));
            double highest = 0.0;
            for (int index = 0; index < ORDER.size(); index++) {
                PredictionOutcome outcome = ORDER.get(index);
                double probability = probability(value.history(), outcome);
                double expected = outcome == value.actual() ? 1.0 : 0.0;
                brier += Math.pow(probability - expected, 2.0);
                highest = Math.max(highest, probability);
                probabilitySums[index] += probability;
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
        Map<String, BigDecimal> averageProbabilities = new LinkedHashMap<>();
        Map<String, ShadowClassCalibration> calibration = new LinkedHashMap<>();
        for (int index = 0; index < ORDER.size(); index++) {
            PredictionOutcome outcome = ORDER.get(index);
            averageProbabilities.put(
                    outcome.name(),
                    n == 0 ? null : decimal(probabilitySums[index] / n)
            );
            calibration.put(outcome.name(), calibration(values, outcome));
        }
        return new ShadowModelMetrics(
                modelVersion,
                n,
                decimal(n == 0 ? 0.0 : (double) correct / n),
                decimal(n == 0 ? 0.0 : logLoss / n),
                decimal(n == 0 ? 0.0 : brier / n),
                decimal(macroF1 / ORDER.size()),
                decimal(n == 0 ? 0.0 : maxProbability / n),
                Collections.unmodifiableMap(averageProbabilities),
                Collections.unmodifiableMap(calibration),
                Collections.unmodifiableMap(classes),
                confusion(matrix)
        );
    }

    public Map<String, BigDecimal> actualOutcomeRates(
            List<EvaluationPrediction> values
    ) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (PredictionOutcome outcome : ORDER) {
            long count = values.stream()
                    .filter(value -> value.actual() == outcome)
                    .count();
            rates.put(
                    outcome.name(),
                    values.isEmpty() ? null
                            : decimal((double) count / values.size())
            );
        }
        return Collections.unmodifiableMap(rates);
    }

    public Map<String, ShadowPairedMetricComparison> pairedMetrics(
            List<EvaluationPrediction> baseline,
            List<EvaluationPrediction> logistic
    ) {
        if (baseline.size() != logistic.size()) {
            throw new IllegalArgumentException(
                    "Paired model evaluation requires equal sample counts."
            );
        }
        int size = baseline.size();
        double[] baselineAccuracy = new double[size];
        double[] logisticAccuracy = new double[size];
        double[] baselineLogLoss = new double[size];
        double[] logisticLogLoss = new double[size];
        double[] baselineBrier = new double[size];
        double[] logisticBrier = new double[size];
        for (int index = 0; index < size; index++) {
            EvaluationPrediction baselineValue = baseline.get(index);
            EvaluationPrediction logisticValue = logistic.get(index);
            if (baselineValue.actual() != logisticValue.actual()) {
                throw new IllegalArgumentException(
                        "Paired model evaluation requires aligned actual outcomes."
                );
            }
            baselineAccuracy[index] = correct(baselineValue);
            logisticAccuracy[index] = correct(logisticValue);
            baselineLogLoss[index] = logLoss(baselineValue);
            logisticLogLoss[index] = logLoss(logisticValue);
            baselineBrier[index] = brier(baselineValue);
            logisticBrier[index] = brier(logisticValue);
        }

        Map<String, ShadowPairedMetricComparison> result = new LinkedHashMap<>();
        result.put("accuracy", pairedMetric(
                "Accuracy", "HIGHER_IS_BETTER",
                baselineAccuracy, logisticAccuracy, BOOTSTRAP_SEED
        ));
        result.put("logLoss", pairedMetric(
                "Log Loss", "LOWER_IS_BETTER",
                baselineLogLoss, logisticLogLoss, BOOTSTRAP_SEED + 1
        ));
        result.put("brierScore", pairedMetric(
                "Brier Score", "LOWER_IS_BETTER",
                baselineBrier, logisticBrier, BOOTSTRAP_SEED + 2
        ));
        return Collections.unmodifiableMap(result);
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

    private ShadowClassCalibration calibration(
            List<EvaluationPrediction> values,
            PredictionOutcome outcome
    ) {
        int[] counts = new int[10];
        int[] actualCounts = new int[10];
        double[] probabilitySums = new double[10];
        int totalActual = 0;
        double totalProbability = 0.0;
        for (EvaluationPrediction value : values) {
            double probability = probability(value.history(), outcome);
            int bin = Math.max(0, Math.min(9, (int) Math.floor(probability * 10.0)));
            counts[bin]++;
            probabilitySums[bin] += probability;
            totalProbability += probability;
            if (value.actual() == outcome) {
                actualCounts[bin]++;
                totalActual++;
            }
        }

        List<ShadowCalibrationBin> bins = new ArrayList<>();
        double weightedGap = 0.0;
        for (int index = 0; index < 10; index++) {
            if (counts[index] == 0) {
                bins.add(new ShadowCalibrationBin(
                        range(index), 0, null, null, null
                ));
                continue;
            }
            double averageProbability = probabilitySums[index] / counts[index];
            double actualRate = (double) actualCounts[index] / counts[index];
            weightedGap += counts[index]
                    * Math.abs(averageProbability - actualRate);
            bins.add(new ShadowCalibrationBin(
                    range(index),
                    counts[index],
                    decimal(averageProbability),
                    decimal(actualRate),
                    decimal(averageProbability - actualRate)
            ));
        }
        int size = values.size();
        return new ShadowClassCalibration(
                outcome.name(),
                size == 0 ? null : decimal(totalProbability / size),
                size == 0 ? null : decimal((double) totalActual / size),
                size == 0 ? null : decimal(weightedGap / size),
                bins
        );
    }

    private ShadowPairedMetricComparison pairedMetric(
            String metric,
            String direction,
            double[] baseline,
            double[] logistic,
            long seed
    ) {
        if (baseline.length == 0) {
            return new ShadowPairedMetricComparison(
                    metric, direction, null, null, null,
                    null, null, 0
            );
        }
        double[] differences = new double[baseline.length];
        for (int index = 0; index < baseline.length; index++) {
            differences[index] = logistic[index] - baseline[index];
        }
        double[] interval = baseline.length < MIN_BOOTSTRAP_GAME_COUNT
                ? null : bootstrapInterval(differences, seed);
        return new ShadowPairedMetricComparison(
                metric,
                direction,
                decimal(average(baseline)),
                decimal(average(logistic)),
                decimal(average(differences)),
                interval == null ? null : decimal(interval[0]),
                interval == null ? null : decimal(interval[1]),
                interval == null ? 0 : BOOTSTRAP_REPETITIONS
        );
    }

    private double[] bootstrapInterval(double[] values, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        double[] means = new double[BOOTSTRAP_REPETITIONS];
        for (int repetition = 0; repetition < BOOTSTRAP_REPETITIONS; repetition++) {
            double total = 0.0;
            for (int index = 0; index < values.length; index++) {
                total += values[random.nextInt(values.length)];
            }
            means[repetition] = total / values.length;
        }
        Arrays.sort(means);
        return new double[]{quantile(means, 0.025), quantile(means, 0.975)};
    }

    private double quantile(double[] sorted, double value) {
        double position = value * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double result = sorted[lower];
        if (upper != lower) {
            result += (sorted[upper] - result) * (position - lower);
        }
        return result;
    }

    private double correct(EvaluationPrediction value) {
        return value.history().getPredictedOutcome() == value.actual() ? 1.0 : 0.0;
    }

    private double logLoss(EvaluationPrediction value) {
        return -Math.log(Math.max(
                EPSILON,
                probability(value.history(), value.actual())
        ));
    }

    private double brier(EvaluationPrediction value) {
        double score = 0.0;
        for (PredictionOutcome outcome : ORDER) {
            double expected = outcome == value.actual() ? 1.0 : 0.0;
            score += Math.pow(
                    probability(value.history(), outcome) - expected,
                    2.0
            );
        }
        return score;
    }

    private double average(double[] values) {
        return Arrays.stream(values).average().orElse(0.0);
    }

    private String range(int index) {
        return "%d-%d%%".formatted(index * 10, (index + 1) * 10);
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
