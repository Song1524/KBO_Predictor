package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.prediction.history.PredictionStage;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ShadowPredictionWriter {

    private final GameRepository gameRepository;
    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final SystemPredictionHistoryRecorder historyRecorder;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(
            Long gameId,
            Long featureSnapshotId,
            PredictionStage stage,
            PredictionEngineResult result,
            String artifactHash,
            LocalDateTime generatedAt
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (game == null || game.getStatus() != GameStatus.SCHEDULED
                || (game.getPredictionCloseAt() != null
                && !now.isBefore(game.getPredictionCloseAt()))) {
            return false;
        }
        var snapshot = snapshotRepository.findById(featureSnapshotId).orElse(null);
        if (snapshot == null || !gameId.equals(snapshot.getGame().getId())) {
            return false;
        }
        return historyRecorder.recordShadow(
                game, snapshot, result, stage, artifactHash, generatedAt
        );
    }
}
