package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SystemPredictionFinalizationService {

    private static final String SHADOW_MODEL = "logistic-v1";

    private final GameRepository gameRepository;
    private final SystemPredictionRepository systemPredictionRepository;
    private final SystemPredictionHistoryRepository historyRepository;
    private final SystemPredictionHistoryRecorder historyRecorder;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeClosedGame(Long gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId).orElse(null);
        if (game == null || game.getPredictionCloseAt() == null
                || LocalDateTime.now(clock).isBefore(game.getPredictionCloseAt())) {
            return false;
        }

        boolean changed = false;
        SystemPrediction prediction = systemPredictionRepository
                .findByGameId(gameId).orElse(null);
        if (prediction != null && !isFinal(
                gameId, prediction.getModelVersion(), PredictionSource.OPERATIONAL
        )) {
            var latest = historyRepository
                    .findTopByGameIdAndModelVersionAndPredictionSourceOrderByGeneratedAtDescIdDesc(
                            gameId,
                            prediction.getModelVersion(),
                            PredictionSource.OPERATIONAL
                    )
                    .orElse(null);
            changed = latest != null
                    ? historyRecorder.finalizeHistory(latest)
                    : historyRecorder.recordOperational(
                            prediction, null, PredictionStage.FINAL
                    );
        }

        if (!isFinal(gameId, SHADOW_MODEL, PredictionSource.SHADOW)) {
            var latestShadow = historyRepository
                    .findTopByGameIdAndModelVersionAndPredictionSourceOrderByGeneratedAtDescIdDesc(
                            gameId, SHADOW_MODEL, PredictionSource.SHADOW
                    )
                    .orElse(null);
            if (latestShadow != null) {
                changed = historyRecorder.finalizeHistory(latestShadow) || changed;
            }
        }
        return changed;
    }

    private boolean isFinal(
            Long gameId,
            String modelVersion,
            PredictionSource source
    ) {
        return historyRepository
                .existsByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
                        gameId, modelVersion, source, PredictionStage.FINAL
                );
    }
}
