package com.playball.kbopredictor.prediction.backfill;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.history.HistoricalPredictionFeatureBuilder;
import com.playball.kbopredictor.prediction.history.HistoricalPredictionFeatures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PredictionBackfillService {

    private static final long MAX_RANGE_DAYS = 1_500;

    private final HistoricalGameDataSynchronizer gameDataSynchronizer;
    private final GameRepository gameRepository;
    private final HistoricalPredictionFeatureBuilder featureBuilder;
    private final BacktestPredictionWriter predictionWriter;
    private final Clock clock;

    public PredictionBackfillResponse backfill(
            LocalDate from,
            LocalDate to,
            boolean syncGames
    ) {
        validateRange(from, to);
        LocalDateTime startedAt = LocalDateTime.now(clock);
        HistoricalGameSyncSummary gameSync = syncGames
                ? gameDataSynchronizer.syncRange(from, to)
                : HistoricalGameSyncSummary.notRequested();

        List<Game> games = gameRepository.findByStatusAndGameDateBetweenWithTeams(
                GameStatus.FINISHED,
                from,
                to
        );
        int snapshotCreated = 0;
        int snapshotExisting = 0;
        int historyCreated = 0;
        int historyExisting = 0;
        List<String> errors = new ArrayList<>(gameSync.errors());

        for (Game game : games) {
            try {
                HistoricalPredictionFeatures features = featureBuilder.build(
                        game.getId()
                );
                BacktestWriteResult result = predictionWriter.write(features);
                if (result.snapshotCreated()) {
                    snapshotCreated++;
                } else {
                    snapshotExisting++;
                }
                if (result.historyCreated()) {
                    historyCreated++;
                } else {
                    historyExisting++;
                }
            } catch (RuntimeException exception) {
                String message = "gameId=" + game.getId() + ": "
                        + safeMessage(exception);
                errors.add(message);
                log.warn("Historical prediction backfill failed: {}", message);
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        PredictionBackfillResponse response = new PredictionBackfillResponse(
                from,
                to,
                gameSync,
                games.size(),
                snapshotCreated,
                snapshotExisting,
                historyCreated,
                historyExisting,
                errors.size() - gameSync.errors().size(),
                errors,
                startedAt,
                finishedAt
        );
        log.info(
                "Historical prediction backfill complete: from={}, to={}, games={}, snapshotsCreated={}, historiesCreated={}, failures={}, elapsedMs={}",
                from,
                to,
                games.size(),
                snapshotCreated,
                historyCreated,
                response.failedGameCount(),
                Duration.between(startedAt, finishedAt).toMillis()
        );
        return response;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "from은 to보다 늦을 수 없습니다."
            );
        }
        if (Duration.between(
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay()
        ).toDays() > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "한 번의 backfill 기간은 1500일을 초과할 수 없습니다."
            );
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
