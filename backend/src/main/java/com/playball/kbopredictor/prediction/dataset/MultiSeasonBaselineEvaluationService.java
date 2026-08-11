package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.evaluation.*;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@Transactional(readOnly = true)
public class MultiSeasonBaselineEvaluationService {

    private static final double FIXED_DRAW_PROBABILITY = 0.03;
    private static final double FIXED_HOME_PROBABILITY = 0.50;
    private static final double FIXED_AWAY_PROBABILITY = 0.47;

    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final HistoricalDatasetMapper mapper;
    private final PredictionMetricsCalculator metricsCalculator;
    private final PredictionEngine baselineV1;

    public MultiSeasonBaselineEvaluationService(
            PredictionFeatureSnapshotRepository snapshotRepository,
            HistoricalDatasetMapper mapper,
            PredictionMetricsCalculator metricsCalculator,
            @Qualifier("baselinePredictionEngine") PredictionEngine baselineV1
    ) {
        this.snapshotRepository = snapshotRepository;
        this.mapper = mapper;
        this.metricsCalculator = metricsCalculator;
        this.baselineV1 = baselineV1;
    }

    public MultiSeasonBaselineEvaluationResponse evaluate(
            LocalDate from,
            LocalDate to
    ) {
        validate(from, to);
        List<PredictionFeatureSnapshot> snapshots =
                snapshotRepository.findEvaluationSnapshots(from, to);
        List<EvaluationEntry> entries = snapshots.stream()
                .map(snapshot -> new EvaluationEntry(
                        snapshot.getGame().getSeason(),
                        mapper.toRow(snapshot),
                        new HistoricalModelSample(
                                snapshot.getGame().getId(),
                                snapshot.getGame().getGameDate(),
                                snapshot.toPredictionFeatures(),
                                PredictionOutcome.valueOf(
                                        snapshot.getGame().getResult().name()
                                )
                        )
                ))
                .filter(entry -> entry.row().availableFeatureCount() > 0)
                .toList();

        Map<Integer, List<EvaluationEntry>> bySeason = new TreeMap<>();
        for (EvaluationEntry entry : entries) {
            bySeason.computeIfAbsent(entry.season(), ignored ->
                    new java.util.ArrayList<>()).add(entry);
        }
        List<MultiSeasonBaselineEvaluationResponse.SeasonEvaluation> seasons =
                bySeason.entrySet().stream()
                        .map(entry -> evaluateSlice(
                                String.valueOf(entry.getKey()),
                                entry.getKey(),
                                entry.getValue()
                        ))
                        .toList();

        return new MultiSeasonBaselineEvaluationResponse(
                from,
                to,
                snapshots.size(),
                entries.size(),
                snapshots.size() - entries.size(),
                "always-home uses fixed HOME/DRAW/AWAY probabilities 0.50/0.03/0.47; season-win-rate keeps DRAW=0.03 and the existing logistic scale 4.0",
                seasons,
                evaluateSlice("ALL", null, entries)
        );
    }

    private MultiSeasonBaselineEvaluationResponse.SeasonEvaluation evaluateSlice(
            String label,
            Integer season,
            List<EvaluationEntry> entries
    ) {
        List<HistoricalModelSample> samples = entries.stream()
                .map(EvaluationEntry::sample)
                .toList();
        List<ModelEvaluationMetrics> metrics = List.of(
                metricsCalculator.evaluate(
                        "always-home",
                        samples,
                        ignored -> ModelProbabilities.of(
                                FIXED_HOME_PROBABILITY,
                                FIXED_DRAW_PROBABILITY,
                                FIXED_AWAY_PROBABILITY,
                                PredictionOutcome.HOME_WIN
                        )
                ),
                metricsCalculator.evaluate(
                        "season-win-rate",
                        samples,
                        this::seasonWinRate
                ),
                metricsCalculator.evaluate(
                        "baseline-v1",
                        samples,
                        sample -> ModelProbabilities.from(
                                baselineV1.predict(sample.features())
                        )
                )
        );
        return new MultiSeasonBaselineEvaluationResponse.SeasonEvaluation(
                label,
                season,
                samples.size(),
                metrics
        );
    }

    private ModelProbabilities seasonWinRate(HistoricalModelSample sample) {
        BigDecimal homeRate = sample.features().home().seasonWinRate();
        BigDecimal awayRate = sample.features().away().seasonWinRate();
        if (homeRate == null || awayRate == null) {
            return ModelProbabilities.of(
                    FIXED_HOME_PROBABILITY,
                    FIXED_DRAW_PROBABILITY,
                    FIXED_AWAY_PROBABILITY,
                    PredictionOutcome.HOME_WIN
            );
        }
        double difference = homeRate.doubleValue() - awayRate.doubleValue();
        double homeShare = 1.0 / (1.0 + Math.exp(-4.0 * difference));
        return ModelProbabilities.of(
                (1.0 - FIXED_DRAW_PROBABILITY) * homeShare,
                FIXED_DRAW_PROBABILITY,
                (1.0 - FIXED_DRAW_PROBABILITY) * (1.0 - homeShare),
                difference >= 0.0
                        ? PredictionOutcome.HOME_WIN
                        : PredictionOutcome.AWAY_WIN
        );
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid evaluation period.");
        }
    }

    private record EvaluationEntry(
            Integer season,
            HistoricalMlDatasetRow row,
            HistoricalModelSample sample
    ) {
    }
}
