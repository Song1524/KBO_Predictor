package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.history.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GameModelComparisonService {

    private final GameRepository gameRepository;
    private final SystemPredictionHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public GameModelComparisonResponse compare(Long gameId) {
        Game game = gameRepository.findById(gameId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found.")
        );
        SystemPredictionHistory baseline = latest(
                gameId, ShadowEvaluationService.BASELINE_MODEL,
                PredictionSource.OPERATIONAL
        );
        SystemPredictionHistory logistic = latest(
                gameId, ShadowEvaluationService.SHADOW_MODEL,
                PredictionSource.SHADOW
        );
        return new GameModelComparisonResponse(
                game.getId(), game.getGameDate(), game.getGameTime(),
                game.getHomeTeam().getName(), game.getAwayTeam().getName(),
                game.getStatus(), game.getResult(), sameSnapshot(baseline, logistic),
                view(baseline), view(logistic)
        );
    }

    private SystemPredictionHistory latest(
            Long gameId,
            String modelVersion,
            PredictionSource source
    ) {
        return historyRepository
                .findTopByGameIdAndModelVersionAndPredictionSourceOrderByGeneratedAtDescIdDesc(
                        gameId, modelVersion, source
                )
                .orElse(null);
    }

    private boolean sameSnapshot(
            SystemPredictionHistory left,
            SystemPredictionHistory right
    ) {
        return left != null && right != null
                && left.getFeatureSnapshot() != null
                && right.getFeatureSnapshot() != null
                && Objects.equals(
                        left.getFeatureSnapshot().getId(),
                        right.getFeatureSnapshot().getId()
                );
    }

    private ModelPredictionView view(SystemPredictionHistory value) {
        if (value == null) return null;
        PredictionFeatureSnapshot snapshot = value.getFeatureSnapshot();
        return new ModelPredictionView(
                value.getModelVersion(), value.getPredictionSource(),
                value.getPredictionStage(), value.getHomeWinProbability(),
                value.getDrawProbability(), value.getAwayWinProbability(),
                value.getPredictedOutcome(), value.getFeatureCoverage(),
                value.getReason(), value.getModelArtifactHash(),
                value.getGeneratedAt(), value.getRecordedAt(),
                snapshot == null ? null : snapshot.getId(),
                snapshot == null ? null : snapshot.getFeatureAsOf(),
                snapshot == null ? null : snapshot.getGenerationMethod().name()
        );
    }
}
