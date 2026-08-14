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
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShadowEvaluationService {

    public static final String BASELINE_MODEL = "baseline-v1";
    public static final String SHADOW_MODEL = "logistic-v1";
    private static final int ADVISORY_PROMOTION_MIN_GAME_COUNT = 200;
    private static final int ADVISORY_PROMOTION_MIN_PER_OUTCOME_COUNT = 10;

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
        int nonOperationalSnapshots = 0;
        int pregameCutoffViolations = 0;
        int artifactMismatches = 0;
        int agreement = 0;
        int logisticOnly = 0;
        int baselineOnly = 0;
        int bothCorrect = 0;
        int bothWrong = 0;
        String artifactHash = artifactLoader.artifactSha256();

        List<Long> commonIds = baseline.keySet().stream()
                .filter(logistic::containsKey).sorted().toList();
        for (Long gameId : commonIds) {
            SystemPredictionHistory baselineHistory = baseline.get(gameId);
            SystemPredictionHistory logisticHistory = logistic.get(gameId);
            if (!artifactHash.equalsIgnoreCase(
                    Objects.toString(logisticHistory.getModelArtifactHash(), "")
            )) {
                artifactMismatches++;
                continue;
            }
            if (!sameSnapshot(baselineHistory, logisticHistory)) {
                mismatches++;
                continue;
            }
            if (!operationalSnapshot(baselineHistory, logisticHistory)) {
                nonOperationalSnapshots++;
                continue;
            }
            if (!beforeGameStart(baselineHistory, logisticHistory)) {
                pregameCutoffViolations++;
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
                "OPERATIONAL_STORED_FINAL_ONLY",
                from,
                to,
                BASELINE_MODEL,
                SHADOW_MODEL,
                artifactHash,
                baseline.size(),
                logistic.size(),
                count,
                mismatches,
                nonOperationalSnapshots,
                pregameCutoffViolations,
                artifactMismatches,
                metricCalculator.actualOutcomeRates(baselineValues),
                metricCalculator.calculate(BASELINE_MODEL, baselineValues),
                metricCalculator.calculate(SHADOW_MODEL, logisticValues),
                metricCalculator.pairedMetrics(baselineValues, logisticValues),
                sampleSizeAssessment(baselineValues),
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

    private boolean operationalSnapshot(
            SystemPredictionHistory baseline,
            SystemPredictionHistory logistic
    ) {
        return baseline.getFeatureSnapshot().getGenerationMethod()
                == PredictionGenerationMethod.OPERATIONAL_PREGAME
                && logistic.getFeatureSnapshot().getGenerationMethod()
                == PredictionGenerationMethod.OPERATIONAL_PREGAME;
    }

    private boolean beforeGameStart(
            SystemPredictionHistory baseline,
            SystemPredictionHistory logistic
    ) {
        var game = baseline.getGame();
        if (game.getGameDate() == null || game.getGameTime() == null) {
            return false;
        }
        LocalDateTime gameStart = LocalDateTime.of(
                game.getGameDate(), game.getGameTime()
        );
        return before(baseline.getFeatureSnapshot().getFeatureAsOf(), gameStart)
                && before(logistic.getFeatureSnapshot().getFeatureAsOf(), gameStart)
                && before(baseline.getGeneratedAt(), gameStart)
                && before(logistic.getGeneratedAt(), gameStart);
    }

    private boolean before(LocalDateTime value, LocalDateTime cutoff) {
        return value != null && value.isBefore(cutoff);
    }

    private ShadowSampleSizeAssessment sampleSizeAssessment(
            List<ShadowMetricCalculator.EvaluationPrediction> values
    ) {
        int home = count(values, PredictionOutcome.HOME_WIN);
        int draw = count(values, PredictionOutcome.DRAW);
        int away = count(values, PredictionOutcome.AWAY_WIN);
        int total = values.size();
        boolean bootstrapEligible = total
                >= ShadowMetricCalculator.MIN_BOOTSTRAP_GAME_COUNT;
        boolean promotionSizeReached = total
                >= ADVISORY_PROMOTION_MIN_GAME_COUNT
                && Math.min(home, Math.min(draw, away))
                >= ADVISORY_PROMOTION_MIN_PER_OUTCOME_COUNT;
        String recommendation;
        if (total == 0) {
            recommendation = "NO_COMMON_OPERATIONAL_FINAL_GAMES";
        } else if (!bootstrapEligible) {
            recommendation = "TOO_FEW_GAMES_FOR_PAIRED_BOOTSTRAP";
        } else if (!promotionSizeReached) {
            recommendation = "CONTINUE_SHADOW_COLLECTION";
        } else {
            recommendation = "SAMPLE_SIZE_GATE_REACHED_REVIEW_CI_AND_CALIBRATION";
        }
        return new ShadowSampleSizeAssessment(
                total,
                home,
                draw,
                away,
                ShadowMetricCalculator.MIN_BOOTSTRAP_GAME_COUNT,
                bootstrapEligible,
                ADVISORY_PROMOTION_MIN_GAME_COUNT,
                ADVISORY_PROMOTION_MIN_PER_OUTCOME_COUNT,
                promotionSizeReached,
                Math.max(0, ADVISORY_PROMOTION_MIN_GAME_COUNT - total),
                Math.max(0, ADVISORY_PROMOTION_MIN_PER_OUTCOME_COUNT - draw),
                recommendation
        );
    }

    private int count(
            List<ShadowMetricCalculator.EvaluationPrediction> values,
            PredictionOutcome outcome
    ) {
        return (int) values.stream()
                .filter(value -> value.actual() == outcome)
                .count();
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(6);
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
