package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.history.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShadowEvaluationService {

    public static final String BASELINE_MODEL = "baseline-v1";
    public static final String SHADOW_MODEL = "logistic-v1";

    private final SystemPredictionHistoryRepository historyRepository;
    private final ShadowMetricCalculator metricCalculator;
    private final LogisticModelArtifactLoader artifactLoader;

    @Transactional(readOnly = true)
    public ShadowEvaluationResponse evaluate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to.");
        }
        Map<Long, SystemPredictionHistory> baseline = histories(
                BASELINE_MODEL, PredictionSource.OPERATIONAL, from, to
        );
        Map<Long, SystemPredictionHistory> logistic = histories(
                SHADOW_MODEL, PredictionSource.SHADOW, from, to
        );
        List<ShadowMetricCalculator.EvaluationPrediction> baselineValues =
                new ArrayList<>();
        List<ShadowMetricCalculator.EvaluationPrediction> logisticValues =
                new ArrayList<>();
        int mismatches = 0;
        int artifactMismatches = 0;
        int agreement = 0;
        int logisticOnly = 0;
        int baselineOnly = 0;
        int bothCorrect = 0;
        int bothWrong = 0;

        List<Long> commonIds = baseline.keySet().stream()
                .filter(logistic::containsKey).sorted().toList();
        for (Long gameId : commonIds) {
            SystemPredictionHistory baselineHistory = baseline.get(gameId);
            SystemPredictionHistory logisticHistory = logistic.get(gameId);
            if (!artifactLoader.artifactSha256().equalsIgnoreCase(
                    Objects.toString(logisticHistory.getModelArtifactHash(), "")
            )) {
                artifactMismatches++;
                continue;
            }
            if (!sameSnapshot(baselineHistory, logisticHistory)) {
                mismatches++;
                continue;
            }
            PredictionOutcome actual = PredictionOutcome.valueOf(
                    baselineHistory.getGame().getResult().name()
            );
            baselineValues.add(new ShadowMetricCalculator.EvaluationPrediction(
                    actual, baselineHistory
            ));
            logisticValues.add(new ShadowMetricCalculator.EvaluationPrediction(
                    actual, logisticHistory
            ));
            boolean baselineCorrect = baselineHistory.getPredictedOutcome() == actual;
            boolean logisticCorrect = logisticHistory.getPredictedOutcome() == actual;
            if (baselineHistory.getPredictedOutcome()
                    == logisticHistory.getPredictedOutcome()) agreement++;
            if (baselineCorrect && logisticCorrect) bothCorrect++;
            else if (baselineCorrect) baselineOnly++;
            else if (logisticCorrect) logisticOnly++;
            else bothWrong++;
        }
        int count = baselineValues.size();
        return new ShadowEvaluationResponse(
                from,
                to,
                count,
                mismatches,
                artifactMismatches,
                metricCalculator.calculate(BASELINE_MODEL, baselineValues),
                metricCalculator.calculate(SHADOW_MODEL, logisticValues),
                ratio(agreement, count),
                logisticOnly,
                baselineOnly,
                bothCorrect,
                bothWrong,
                metricCalculator.drawMetrics(BASELINE_MODEL, baselineValues),
                metricCalculator.drawMetrics(SHADOW_MODEL, logisticValues)
        );
    }

    private Map<Long, SystemPredictionHistory> histories(
            String model,
            PredictionSource source,
            LocalDate from,
            LocalDate to
    ) {
        return historyRepository.findForEvaluation(
                        model, source, PredictionStage.FINAL, from, to
                ).stream()
                .filter(value -> value.getGame().getStatus() == GameStatus.FINISHED)
                .filter(value -> value.getGame().getResult() != null)
                .collect(Collectors.toMap(
                        value -> value.getGame().getId(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private boolean sameSnapshot(
            SystemPredictionHistory baseline,
            SystemPredictionHistory logistic
    ) {
        return baseline.getFeatureSnapshot() != null
                && logistic.getFeatureSnapshot() != null
                && Objects.equals(
                        baseline.getFeatureSnapshot().getId(),
                        logistic.getFeatureSnapshot().getId()
                );
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(6);
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
