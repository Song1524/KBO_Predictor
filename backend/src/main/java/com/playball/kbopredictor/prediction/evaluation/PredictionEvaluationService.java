package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionEvaluationService {

    private static final double MIN_PROBABILITY = 1.0e-15;
    private static final LocalDate ALL_FROM = LocalDate.of(1900, 1, 1);
    private static final LocalDate ALL_TO = LocalDate.of(2999, 12, 31);

    private final GameRepository gameRepository;
    private final SystemPredictionHistoryRepository historyRepository;

    public PredictionEvaluationResponse evaluate() {
        return evaluate(ALL_FROM, ALL_TO, "baseline-v1");
    }

    public PredictionEvaluationResponse evaluate(
            LocalDate from,
            LocalDate to,
            String modelVersion
    ) {
        validate(from, to, modelVersion);
        List<Game> finishedGames = gameRepository
                .findByStatusAndGameDateBetweenWithTeams(
                        GameStatus.FINISHED,
                        from,
                        to
                );
        Map<Long, SystemPredictionHistory> histories = new HashMap<>();
        for (SystemPredictionHistory history : historyRepository.findForEvaluation(
                modelVersion,
                PredictionSource.BACKTEST,
                PredictionStage.FINAL,
                from,
                to
        )) {
            histories.putIfAbsent(history.getGame().getId(), history);
        }

        Map<PredictionOutcome, Integer> samples = counts();
        Map<PredictionOutcome, Integer> correctByOutcome = counts();
        List<EvaluationSample> evaluableSamples = new ArrayList<>();
        int featureGenerated = 0;
        int starterData = 0;
        int teamOnly = 0;
        int correct = 0;
        double featureCoverageTotal = 0.0;
        double logLossTotal = 0.0;
        double brierTotal = 0.0;
        Map<String, Integer> missingFeatureCounts = new TreeMap<>();

        for (Game game : finishedGames) {
            if (game.getResult() == null) {
                continue;
            }
            SystemPredictionHistory history = histories.get(game.getId());
            if (history == null || history.getFeatureSnapshot() == null) {
                continue;
            }
            featureGenerated++;
            history.getFeatureSnapshot().missingFeatureList().forEach(
                    feature -> missingFeatureCounts.merge(feature, 1, Integer::sum)
            );
            if (history.getFeatureCoverage() == null
                    || history.getFeatureCoverage().signum() <= 0) {
                continue;
            }

            PredictionOutcome actual = PredictionOutcome.valueOf(
                    game.getResult().name()
            );
            PredictionFeatureSnapshot snapshot = history.getFeatureSnapshot();
            EvaluationSample sample = new EvaluationSample(
                    history,
                    snapshot,
                    actual
            );
            evaluableSamples.add(sample);
            featureCoverageTotal += history.getFeatureCoverage().doubleValue();
            if (snapshot.hasStartingPitcherData()) {
                starterData++;
            } else {
                teamOnly++;
            }
            samples.compute(actual, (key, value) -> value + 1);
            if (history.getPredictedOutcome() == actual) {
                correct++;
                correctByOutcome.compute(actual, (key, value) -> value + 1);
            }

            double home = probability(history.getHomeWinProbability());
            double draw = probability(history.getDrawProbability());
            double away = probability(history.getAwayWinProbability());
            double actualProbability = switch (actual) {
                case HOME_WIN -> home;
                case DRAW -> draw;
                case AWAY_WIN -> away;
            };
            logLossTotal += -Math.log(Math.max(
                    MIN_PROBABILITY,
                    actualProbability
            ));
            brierTotal += square(home - indicator(actual, PredictionOutcome.HOME_WIN))
                    + square(draw - indicator(actual, PredictionOutcome.DRAW))
                    + square(away - indicator(actual, PredictionOutcome.AWAY_WIN));
        }

        int evaluable = evaluableSamples.size();
        List<BenchmarkEvaluationResponse> benchmarks = List.of(
                benchmark(modelVersion, evaluable, correct),
                alwaysHomeBenchmark(evaluableSamples),
                higherSeasonWinRateBenchmark(evaluableSamples)
        );
        return new PredictionEvaluationResponse(
                modelVersion,
                from,
                to,
                finishedGames.size(),
                featureGenerated,
                evaluable,
                finishedGames.size() - evaluable,
                percentage(evaluable, finishedGames.size()),
                percentageFromRatio(featureCoverageTotal, evaluable),
                starterData,
                teamOnly,
                missingFeatureCounts,
                correct,
                percentage(correct, evaluable),
                samples.get(PredictionOutcome.HOME_WIN),
                percentage(
                        correctByOutcome.get(PredictionOutcome.HOME_WIN),
                        samples.get(PredictionOutcome.HOME_WIN)
                ),
                samples.get(PredictionOutcome.DRAW),
                percentage(
                        correctByOutcome.get(PredictionOutcome.DRAW),
                        samples.get(PredictionOutcome.DRAW)
                ),
                samples.get(PredictionOutcome.AWAY_WIN),
                percentage(
                        correctByOutcome.get(PredictionOutcome.AWAY_WIN),
                        samples.get(PredictionOutcome.AWAY_WIN)
                ),
                average(logLossTotal, evaluable, 6),
                average(brierTotal, evaluable, 6),
                benchmarks
        );
    }

    private BenchmarkEvaluationResponse alwaysHomeBenchmark(
            List<EvaluationSample> samples
    ) {
        int correct = (int) samples.stream()
                .filter(sample -> sample.actual() == PredictionOutcome.HOME_WIN)
                .count();
        return benchmark("always-home-win", samples.size(), correct);
    }

    private BenchmarkEvaluationResponse higherSeasonWinRateBenchmark(
            List<EvaluationSample> samples
    ) {
        int sampleCount = 0;
        int correct = 0;
        for (EvaluationSample sample : samples) {
            BigDecimal home = sample.snapshot().getHomeSeasonWinRate();
            BigDecimal away = sample.snapshot().getAwaySeasonWinRate();
            if (home == null || away == null) {
                continue;
            }
            sampleCount++;
            PredictionOutcome predicted = home.compareTo(away) >= 0
                    ? PredictionOutcome.HOME_WIN
                    : PredictionOutcome.AWAY_WIN;
            if (predicted == sample.actual()) {
                correct++;
            }
        }
        return benchmark("higher-season-win-rate", sampleCount, correct);
    }

    private BenchmarkEvaluationResponse benchmark(
            String name,
            int samples,
            int correct
    ) {
        return new BenchmarkEvaluationResponse(
                name,
                samples,
                correct,
                percentage(correct, samples)
        );
    }

    private void validate(LocalDate from, LocalDate to, String modelVersion) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("유효한 평가 기간을 지정해야 합니다.");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion이 필요합니다.");
        }
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
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(total / count)
                .setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal percentageFromRatio(double total, int count) {
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(total * 100.0 / count)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private double probability(BigDecimal percentage) {
        return percentage.doubleValue() / 100.0;
    }

    private double indicator(
            PredictionOutcome actual,
            PredictionOutcome target
    ) {
        return actual == target ? 1.0 : 0.0;
    }

    private double square(double value) {
        return value * value;
    }

    private record EvaluationSample(
            SystemPredictionHistory history,
            PredictionFeatureSnapshot snapshot,
            PredictionOutcome actual
    ) {
    }
}
