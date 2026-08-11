package com.playball.kbopredictor.prediction.backfill;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.history.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BacktestPredictionWriter {

    private final GameRepository gameRepository;
    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final SystemPredictionHistoryRepository historyRepository;
    private final PredictionEngine predictionEngine;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BacktestWriteResult write(
            HistoricalPredictionFeatures historical
    ) {
        Game game = gameRepository.findByIdForUpdate(
                        historical.features().gameId()
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        LocalDateTime featureAsOf = historical.features()
                .gameStartAt()
                .minusSeconds(1);
        PredictionFeatureSnapshot snapshot = snapshotRepository
                .findByGameIdAndFeatureAsOfAndGenerationMethod(
                        game.getId(),
                        featureAsOf,
                        historical.generationMethod()
                )
                .orElse(null);
        boolean snapshotCreated = snapshot == null;
        if (snapshotCreated) {
            snapshot = PredictionFeatureSnapshot.create(
                    game,
                    historical,
                    LocalDateTime.now(clock)
            );
            snapshotRepository.saveAndFlush(snapshot);
        }

        PredictionEngineResult result = predictionEngine.predict(
                snapshot.toPredictionFeatures()
        );
        String historyKey = "BACKTEST:%d:%s:FINAL".formatted(
                game.getId(),
                result.modelVersion()
        );
        boolean historyCreated = historyRepository
                .findByDeduplicationKey(historyKey)
                .isEmpty();
        if (historyCreated) {
            historyRepository.saveAndFlush(SystemPredictionHistory.fromBacktest(
                    game,
                    snapshot,
                    result,
                    historyKey,
                    LocalDateTime.now(clock)
            ));
        }
        return new BacktestWriteResult(
                game.getId(),
                snapshotCreated,
                historyCreated
        );
    }
}
