package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component("logisticRegressionPredictionEngine")
public class LogisticRegressionPredictionEngine implements PredictionEngine {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    private final LogisticModelArtifact artifact;

    public LogisticRegressionPredictionEngine(
            LogisticModelArtifactLoader artifactLoader
    ) {
        this.artifact = artifactLoader.artifact();
    }

    @Override
    public PredictionEngineResult predict(PredictionFeatures features) {
        LogisticRawPrediction raw = predictRaw(features);
        Map<PredictionOutcome, BigDecimal> percentages = percentages(raw);
        return new PredictionEngineResult(
                percentages.get(PredictionOutcome.HOME_WIN),
                percentages.get(PredictionOutcome.DRAW),
                percentages.get(PredictionOutcome.AWAY_WIN),
                raw.predictedOutcome(),
                artifact.modelVersion(),
                BigDecimal.valueOf(raw.availableFeatureCount())
                        .divide(
                                BigDecimal.valueOf(artifact.features().size()),
                                3,
                                RoundingMode.HALF_UP
                        ),
                reasons(raw)
        );
    }

    public LogisticRawPrediction predictRaw(PredictionFeatures features) {
        Map<String, Double> rawValues = LogisticFeatureValues.from(features);
        int featureCount = artifact.features().size();
        int available = 0;
        double[] standardized = new double[featureCount];
        for (int index = 0; index < featureCount; index++) {
            String featureName = artifact.features().get(index);
            Double raw = rawValues.get(featureName);
            if (raw != null) {
                available++;
            } else {
                raw = artifact.imputerStatistics()[index];
            }
            standardized[index] = (
                    raw - artifact.scalerMean()[index]
            ) / artifact.scalerScale()[index];
        }

        double[] scores = new double[artifact.classes().size()];
        double maxScore = Double.NEGATIVE_INFINITY;
        for (int classIndex = 0; classIndex < scores.length; classIndex++) {
            double score = artifact.intercepts()[classIndex];
            for (int featureIndex = 0; featureIndex < featureCount; featureIndex++) {
                score += artifact.coefficients()[classIndex][featureIndex]
                        * standardized[featureIndex];
            }
            scores[classIndex] = score;
            maxScore = Math.max(maxScore, score);
        }

        double total = 0.0;
        double[] exponentials = new double[scores.length];
        for (int index = 0; index < scores.length; index++) {
            exponentials[index] = Math.exp(scores[index] - maxScore);
            total += exponentials[index];
        }
        Map<PredictionOutcome, Double> probabilities =
                new EnumMap<>(PredictionOutcome.class);
        PredictionOutcome predicted = null;
        double highest = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < exponentials.length; index++) {
            PredictionOutcome outcome = PredictionOutcome.valueOf(
                    artifact.classes().get(index)
            );
            double probability = exponentials[index] / total;
            probabilities.put(outcome, probability);
            if (probability > highest) {
                highest = probability;
                predicted = outcome;
            }
        }
        return new LogisticRawPrediction(
                Collections.unmodifiableMap(probabilities),
                Objects.requireNonNull(predicted),
                available,
                standardized
        );
    }

    private Map<PredictionOutcome, BigDecimal> percentages(
            LogisticRawPrediction raw
    ) {
        Map<PredictionOutcome, BigDecimal> values =
                new EnumMap<>(PredictionOutcome.class);
        BigDecimal sum = BigDecimal.ZERO;
        for (PredictionOutcome outcome : PredictionOutcome.values()) {
            BigDecimal percentage = BigDecimal.valueOf(
                            raw.probabilities().get(outcome) * 100.0
                    )
                    .setScale(2, RoundingMode.HALF_UP);
            values.put(outcome, percentage);
            sum = sum.add(percentage);
        }
        BigDecimal remainder = ONE_HUNDRED.subtract(sum);
        values.put(
                raw.predictedOutcome(),
                values.get(raw.predictedOutcome()).add(remainder)
        );
        return values;
    }

    private List<String> reasons(LogisticRawPrediction raw) {
        int classIndex = artifact.classes().indexOf(
                raw.predictedOutcome().name()
        );
        List<Contribution> contributions = new ArrayList<>();
        for (int index = 0; index < artifact.features().size(); index++) {
            contributions.add(new Contribution(
                    artifact.features().get(index),
                    artifact.coefficients()[classIndex][index]
                            * raw.standardizedFeatures()[index]
            ));
        }
        List<String> reasons = new ArrayList<>(contributions.stream()
                .sorted(Comparator.comparingDouble(
                        value -> -Math.abs(value.contribution())
                ))
                .limit(3)
                .map(value -> label(value.feature())
                        + "가 logistic-v1 예측에 큰 영향을 주었습니다.")
                .toList());
        if (raw.availableFeatureCount() < artifact.features().size()) {
            reasons.add("누락 지표는 2023~2025 최종 학습 데이터의 중앙값으로 대체했습니다.");
        }
        return List.copyOf(reasons);
    }

    private String label(String feature) {
        return switch (feature) {
            case LogisticFeatureValues.SEASON_WIN_RATE_DIFF -> "시즌 승률 차이";
            case LogisticFeatureValues.RECENT_5_WIN_RATE_DIFF -> "최근 5경기 승률 차이";
            case LogisticFeatureValues.RECENT_10_WIN_RATE_DIFF -> "최근 10경기 승률 차이";
            case LogisticFeatureValues.RECENT_5_RUN_DIFF -> "최근 5경기 득실 차이";
            case LogisticFeatureValues.RECENT_10_RUN_DIFF -> "최근 10경기 득실 차이";
            case LogisticFeatureValues.HOME_AWAY_WIN_RATE_DIFF -> "홈·원정 승률 차이";
            default -> feature;
        };
    }

    private record Contribution(String feature, double contribution) {
    }
}
