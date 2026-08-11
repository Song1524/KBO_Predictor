package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShadowFinishedGameEvaluationService {

    private final GameRepository gameRepository;
    private final SystemPredictionHistoryRepository historyRepository;
    private final LogisticModelArtifactLoader artifactLoader;
    private final ShadowMetricCalculator metricCalculator;

    @Transactional(readOnly = true)
    public boolean evaluateAndLog(Long gameId) {
        var game = gameRepository.findById(gameId).orElse(null);
        if (game == null || game.getStatus() != GameStatus.FINISHED
                || game.getResult() == null) return false;
        var baseline = finalHistory(
                gameId, ShadowEvaluationService.BASELINE_MODEL,
                PredictionSource.OPERATIONAL
        );
        var logistic = finalHistory(
                gameId, ShadowEvaluationService.SHADOW_MODEL,
                PredictionSource.SHADOW
        );
        if (baseline == null || logistic == null) return false;
        if (baseline.getFeatureSnapshot() == null
                || logistic.getFeatureSnapshot() == null
                || !Objects.equals(
                        baseline.getFeatureSnapshot().getId(),
                        logistic.getFeatureSnapshot().getId()
                )) {
            throw new IllegalStateException(
                    "Operational and shadow FINAL histories use different feature snapshots."
            );
        }
        if (!artifactLoader.artifactSha256().equalsIgnoreCase(
                Objects.toString(logistic.getModelArtifactHash(), "")
        )) {
            throw new IllegalStateException("Shadow history artifact SHA-256 mismatch.");
        }
        PredictionOutcome actual = PredictionOutcome.valueOf(game.getResult().name());
        log.info(
                "Shadow result evaluated: gameId={}, actual={}, baselineOutcome={}, logisticOutcome={}, baselineActualProbability={}, logisticActualProbability={}, snapshotId={}",
                gameId,
                actual,
                baseline.getPredictedOutcome(),
                logistic.getPredictedOutcome(),
                metricCalculator.probability(baseline, actual),
                metricCalculator.probability(logistic, actual),
                baseline.getFeatureSnapshot().getId()
        );
        return true;
    }

    private SystemPredictionHistory finalHistory(
            Long gameId,
            String modelVersion,
            PredictionSource source
    ) {
        return historyRepository
                .findByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
                        gameId, modelVersion, source, PredictionStage.FINAL
                )
                .orElse(null);
    }
}
