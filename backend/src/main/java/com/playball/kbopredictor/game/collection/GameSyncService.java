package com.playball.kbopredictor.game.collection;

import lombok.RequiredArgsConstructor;
import com.playball.kbopredictor.prediction.shadow.ShadowFinishedGameEvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GameSyncService {

    private static final String SOURCE = "KBO_OFFICIAL_SCHEDULE";
    private static final Logger log = LoggerFactory.getLogger(
            GameSyncService.class
    );

    private final GameDataCollector gameDataCollector;
    private final GameUpsertService gameUpsertService;
    private final GameSettlementCoordinator gameSettlementCoordinator;
    private final ShadowFinishedGameEvaluationService shadowEvaluationService;
    private final Clock clock;

    public GameSyncResponse sync(LocalDate date) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        log.info(
                "KBO 경기 동기화 시작: source={}, targetDate={}",
                SOURCE,
                date
        );

        GameCollectionBatch batch;
        try {
            batch = gameDataCollector.collect(date);
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 경기 동기화 실패: source={}, targetDate={}, error={}, elapsedMs={}",
                    SOURCE,
                    date,
                    exception.getMessage(),
                    Duration.between(
                            startedAt,
                            LocalDateTime.now(clock)
                    ).toMillis(),
                    exception
            );
            throw exception;
        }

        return processBatch(date, batch, startedAt);
    }

    public List<GameSyncResponse> syncDates(List<LocalDate> dates) {
        List<LocalDate> targets = dates.stream().distinct().toList();
        if (targets.isEmpty()) {
            return List.of();
        }

        LocalDateTime startedAt = LocalDateTime.now(clock);
        log.info(
                "KBO 경기 다중 날짜 동기화 시작: source={}, targetDates={}",
                SOURCE,
                targets
        );

        Map<LocalDate, GameCollectionBatch> batches;
        try {
            batches = gameDataCollector.collectDates(targets);
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 경기 다중 날짜 동기화 실패: source={}, targetDates={}, error={}, elapsedMs={}",
                    SOURCE,
                    targets,
                    exception.getMessage(),
                    Duration.between(
                            startedAt,
                            LocalDateTime.now(clock)
                    ).toMillis(),
                    exception
            );
            throw exception;
        }

        List<GameSyncResponse> responses = new ArrayList<>();
        for (LocalDate target : targets) {
            GameCollectionBatch batch = batches.get(target);
            if (batch == null) {
                batch = new GameCollectionBatch(
                        0,
                        List.of(),
                        List.of("수집 결과에 대상 날짜가 없습니다: " + target)
                );
            }
            responses.add(processBatch(target, batch, startedAt));
        }
        return List.copyOf(responses);
    }

    private GameSyncResponse processBatch(
            LocalDate date,
            GameCollectionBatch batch,
            LocalDateTime startedAt
    ) {

        int insertedCount = 0;
        int updatedCount = 0;
        int statusChangedCount = 0;
        int finishedCount = 0;
        int cancelledCount = 0;
        int settlementSuccessCount = 0;
        List<String> errors = new ArrayList<>(batch.errors());

        for (CollectedGame game : batch.games()) {
            GameUpsertResult result;
            try {
                result = gameUpsertService.upsert(game);
                if (result.inserted()) {
                    insertedCount++;
                } else {
                    updatedCount++;
                }
                if (result.statusChanged()) {
                    statusChangedCount++;
                }
                if (result.reachedFinished()) {
                    finishedCount++;
                }
                if (result.reachedCancelled()) {
                    cancelledCount++;
                }
            } catch (RuntimeException exception) {
                String error = game.externalGameId()
                        + ": "
                        + safeMessage(exception);
                errors.add(error);
                log.warn(
                        "KBO 경기 단건 저장 실패: source={}, targetDate={}, externalGameId={}, error={}",
                        SOURCE,
                        date,
                        game.externalGameId(),
                        exception.getMessage()
                );
                continue;
            }

            try {
                GameSettlementTriggerResult settlementResult =
                        gameSettlementCoordinator.settleIfNecessary(result);
                if (settlementResult == GameSettlementTriggerResult.SETTLED) {
                    settlementSuccessCount++;
                }
            } catch (RuntimeException exception) {
                String error = game.externalGameId()
                        + " 정산 실패: "
                        + safeMessage(exception);
                errors.add(error);
                log.error(
                        "KBO 경기 자동 정산 실패: source={}, targetDate={}, externalGameId={}, gameId={}, error={}",
                        SOURCE,
                        date,
                        game.externalGameId(),
                        result.gameId(),
                        exception.getMessage(),
                        exception
                );
            }

            if (result.reachedFinished() && result.finalScoreConfirmed()) {
                try {
                    shadowEvaluationService.evaluateAndLog(result.gameId());
                } catch (RuntimeException exception) {
                    log.warn(
                            "Shadow evaluation failed without affecting game settlement: gameId={}, error={}",
                            result.gameId(), exception.getMessage(), exception
                    );
                }
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        GameSyncResponse response = new GameSyncResponse(
                date,
                batch.sourceRowCount(),
                batch.games().size(),
                insertedCount,
                updatedCount,
                statusChangedCount,
                finishedCount,
                cancelledCount,
                settlementSuccessCount,
                errors.size(),
                errors,
                startedAt,
                finishedAt
        );

        log.info(
                "KBO 경기 동기화 완료: source={}, targetDate={}, sourceRows={}, collected={}, inserted={}, updated={}, statusChanged={}, finished={}, cancelled={}, settlementSuccess={}, failed={}, elapsedMs={}",
                SOURCE,
                date,
                response.sourceRowCount(),
                response.collectedGameCount(),
                response.insertedCount(),
                response.updatedCount(),
                response.statusChangedCount(),
                response.finishedCount(),
                response.cancelledCount(),
                response.settlementSuccessCount(),
                response.failedCount(),
                Duration.between(startedAt, finishedAt).toMillis()
        );
        if (!response.errors().isEmpty()) {
            log.warn(
                    "KBO 경기 동기화 부분 실패: source={}, targetDate={}, errors={}",
                    SOURCE,
                    date,
                    response.errors()
            );
        }
        return response;
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
