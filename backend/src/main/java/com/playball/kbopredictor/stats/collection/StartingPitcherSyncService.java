package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.generation.PredictionRefreshReason;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartingPitcherSyncService {

    private final StartingPitcherCollector collector;
    private final StartingPitcherWriter writer;
    private final GameRepository gameRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final SystemPredictionGenerationService predictionGenerationService;
    private final Clock clock;
    private final Map<Long, PredictionRefreshReason> pendingPredictionRefresh =
            new ConcurrentHashMap<>();

    public StartingPitcherSyncResponse syncToday() {
        return sync(LocalDate.now(clock));
    }

    public StartingPitcherSyncResponse sync(LocalDate gameDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        CollectionState state = loadState(gameDate);
        Set<Long> incompleteBeforeSync = incompleteGameIds(state);

        // 게임 목록과 선수 상세 조회를 모두 끝낸 후 짧은 단건 DB 트랜잭션으로 저장한다.
        StartingPitcherCollectionBatch batch = collector.collect(gameDate);
        WriteBatchResult writeResult = writeBatch(
                gameDate, batch, null, startedAt
        );
        Set<GameSideKey> after = new HashSet<>(state.existingSides());
        after.addAll(writeResult.writtenSides());
        List<Long> newlyCompletedGameIds = state.games().stream()
                .map(Game::getId)
                .filter(incompleteBeforeSync::contains)
                .filter(gameId -> hasBothSides(after, gameId))
                .toList();
        refreshPredictions(
                gameDate,
                newlyCompletedGameIds,
                writeResult.changedGameIds()
        );
        return writeResult.response();
    }

    public StartingPitcherPollResult pollMissingBeforeClose(LocalDate gameDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        CollectionState state = loadState(gameDate);
        List<Game> monitoredGames = state.games();
        if (monitoredGames.isEmpty()) {
            refreshPredictions(gameDate, List.of(), Set.of());
            return emptyPollResult(gameDate, startedAt);
        }

        Set<GameSideKey> existing = new HashSet<>(state.existingSides());

        List<Game> eligibleGames = monitoredGames.stream()
                .filter(game -> game.getStatus() == GameStatus.SCHEDULED)
                .filter(game -> game.getPredictionCloseAt() != null)
                .filter(game -> now.isBefore(game.getPredictionCloseAt()))
                .toList();
        Map<String, Long> gameIdsByExternalId = new HashMap<>();
        Set<String> incompleteExternalGameIds = new HashSet<>();
        Set<Long> incompleteBeforePoll = new HashSet<>();
        for (Game game : eligibleGames) {
            if (!hasBothSides(existing, game.getId())) {
                incompleteBeforePoll.add(game.getId());
                if (game.getExternalGameId() != null
                        && !game.getExternalGameId().isBlank()) {
                    gameIdsByExternalId.put(
                            game.getExternalGameId(), game.getId()
                    );
                    incompleteExternalGameIds.add(game.getExternalGameId());
                }
            }
        }

        WriteBatchResult writeResult;
        if (incompleteExternalGameIds.isEmpty()) {
            writeResult = new WriteBatchResult(
                    emptyResponse(gameDate, startedAt), Set.of(), Set.of()
            );
        } else {
            StartingPitcherCollectionBatch batch = collector.collect(
                    gameDate,
                    Set.copyOf(incompleteExternalGameIds)
            );
            Set<GameSideKey> missing = new HashSet<>();
            for (String externalGameId : incompleteExternalGameIds) {
                Long gameId = gameIdsByExternalId.get(externalGameId);
                for (StartingPitcherSide side : StartingPitcherSide.values()) {
                    GameSideKey key = new GameSideKey(gameId, side);
                    if (!existing.contains(key)) {
                        missing.add(key);
                    }
                }
            }
            writeResult = writeBatch(
                    gameDate,
                    batch,
                    new RetryFilter(gameIdsByExternalId, missing),
                    startedAt
            );
            existing.addAll(writeResult.writtenSides());
        }

        List<Long> newlyCompletedGameIds = eligibleGames.stream()
                .map(Game::getId)
                .filter(incompleteBeforePoll::contains)
                .filter(gameId -> hasBothSides(existing, gameId))
                .toList();
        refreshPredictions(
                gameDate,
                newlyCompletedGameIds,
                writeResult.changedGameIds()
        );
        List<MissingStartingPitcherGame> missingAtClose = monitoredGames.stream()
                .filter(game -> game.getPredictionCloseAt() != null)
                .filter(game -> !now.isBefore(game.getPredictionCloseAt()))
                .filter(game -> !hasBothSides(existing, game.getId()))
                .map(game -> new MissingStartingPitcherGame(
                        game.getId(),
                        game.getExternalGameId(),
                        game.getPredictionCloseAt(),
                        missingSides(existing, game.getId())
                ))
                .toList();
        return new StartingPitcherPollResult(
                writeResult.response(),
                newlyCompletedGameIds,
                missingAtClose
        );
    }

    public StartingPitcherSyncResponse verifyCompleteBeforeClose(
            LocalDate gameDate
    ) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        CollectionState state = loadState(gameDate);
        Map<String, Long> gameIdsByExternalId = new HashMap<>();
        for (Game game : state.games()) {
            if (game.getStatus() == GameStatus.SCHEDULED
                    && game.getPredictionCloseAt() != null
                    && now.isBefore(game.getPredictionCloseAt())
                    && hasBothSides(state.existingSides(), game.getId())
                    && game.getExternalGameId() != null
                    && !game.getExternalGameId().isBlank()) {
                gameIdsByExternalId.put(game.getExternalGameId(), game.getId());
            }
        }
        if (gameIdsByExternalId.isEmpty()) {
            refreshPredictions(gameDate, List.of(), Set.of());
            return emptyResponse(gameDate, startedAt);
        }

        StartingPitcherCollectionBatch batch = collector.collect(
                gameDate,
                Set.copyOf(gameIdsByExternalId.keySet())
        );
        Set<GameSideKey> changedSides = new HashSet<>();
        for (CollectedStartingPitcher pitcher : batch.pitchers()) {
            GameSideKey key = new GameSideKey(
                    gameIdsByExternalId.get(pitcher.externalGameId()),
                    pitcher.side()
            );
            if (key.gameId() != null && !Objects.equals(
                    state.playerIds().get(key),
                    pitcher.kboPlayerId()
            )) {
                changedSides.add(key);
            }
        }
        WriteBatchResult writeResult = writeBatch(
                gameDate,
                batch,
                new RetryFilter(gameIdsByExternalId, changedSides),
                startedAt
        );
        refreshPredictions(
                gameDate,
                List.of(),
                writeResult.changedGameIds()
        );
        return writeResult.response();
    }

    private WriteBatchResult writeBatch(
            LocalDate gameDate,
            StartingPitcherCollectionBatch batch,
            RetryFilter retryFilter,
            LocalDateTime startedAt
    ) {
        int inserted = 0;
        int updated = 0;
        int statsSaved = 0;
        int collectedPitchers = 0;
        List<String> errors = new ArrayList<>(batch.errors());
        Set<GameSideKey> writtenSides = new HashSet<>();
        Set<Long> changedGameIds = new HashSet<>();
        LocalDate statDate = LocalDate.now(clock);
        for (CollectedStartingPitcher pitcher : batch.pitchers()) {
            if (retryFilter != null && !retryFilter.shouldWrite(pitcher)) {
                continue;
            }
            collectedPitchers++;
            try {
                Optional<StartingPitcherWriteResult> written = writePitcher(
                        pitcher, statDate, retryFilter
                );
                if (written.isEmpty()) {
                    continue;
                }
                StartingPitcherWriteResult result = written.orElseThrow();
                if (result.inserted()) {
                    inserted++;
                } else {
                    updated++;
                }
                if (result.pitcherStatSaved()) {
                    statsSaved++;
                }
                GameSideKey writtenKey = result.gameId() == null
                        ? retryFilter == null ? null : retryFilter.key(pitcher)
                        : new GameSideKey(result.gameId(), result.side());
                if (writtenKey != null) {
                    writtenSides.add(writtenKey);
                }
                if (result.playerChanged() && result.gameId() != null) {
                    changedGameIds.add(result.gameId());
                }
            } catch (RuntimeException exception) {
                errors.add(
                        pitcher.externalGameId()
                                + "/" + pitcher.side()
                                + ": " + safeMessage(exception)
                );
                log.warn(
                        "KBO 선발투수 단건 저장 실패: gameDate={}, externalGameId={}, side={}, error={}",
                        gameDate,
                        pitcher.externalGameId(),
                        pitcher.side(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        StartingPitcherSyncResponse response = new StartingPitcherSyncResponse(
                gameDate,
                batch.sourceGameCount(),
                collectedPitchers,
                inserted,
                updated,
                statsSaved,
                errors.size(),
                List.copyOf(errors),
                startedAt,
                finishedAt
        );
        if (collectedPitchers > 0 || !errors.isEmpty()) {
            log.info(
                    "KBO 선발투수 동기화 완료: gameDate={}, sourceGames={}, pitchers={}, inserted={}, updated={}, pitcherStatsSaved={}, failed={}, elapsedMs={}",
                    gameDate,
                    response.sourceGameCount(),
                    response.collectedPitcherCount(),
                    inserted,
                    updated,
                    statsSaved,
                    errors.size(),
                    Duration.between(startedAt, finishedAt).toMillis()
            );
        } else {
            log.debug(
                    "KBO 선발투수 polling 결과 없음: gameDate={}, sourceGames={}, elapsedMs={}",
                    gameDate,
                    response.sourceGameCount(),
                    Duration.between(startedAt, finishedAt).toMillis()
            );
        }
        return new WriteBatchResult(
                response,
                Set.copyOf(writtenSides),
                Set.copyOf(changedGameIds)
        );
    }

    private Optional<StartingPitcherWriteResult> writePitcher(
            CollectedStartingPitcher pitcher,
            LocalDate statDate,
            RetryFilter retryFilter
    ) {
        if (retryFilter == null) {
            return Optional.of(writer.upsert(
                    pitcher,
                    statDate,
                    LocalDateTime.now(clock)
            ));
        }
        return writer.upsertBeforeClose(pitcher, statDate);
    }

    private CollectionState loadState(LocalDate gameDate) {
        List<Game> games = gameRepository
                .findByGameDateOrderByGameTimeAsc(gameDate)
                .stream()
                .filter(game -> game.getStatus() != GameStatus.CANCELLED)
                .toList();
        if (games.isEmpty()) {
            return new CollectionState(List.of(), Set.of(), Map.of());
        }
        List<Long> gameIds = games.stream().map(Game::getId).toList();
        Set<GameSideKey> existingSides = new HashSet<>();
        Map<GameSideKey, String> playerIds = new HashMap<>();
        for (StartingPitcher pitcher
                : startingPitcherRepository.findByGameIdInWithPlayer(gameIds)) {
            GameSideKey key = new GameSideKey(
                    pitcher.getGame().getId(), pitcher.getSide()
            );
            existingSides.add(key);
            playerIds.put(key, pitcher.getPlayer().getKboPlayerId());
        }
        return new CollectionState(
                games,
                Set.copyOf(existingSides),
                Map.copyOf(playerIds)
        );
    }

    private Set<Long> incompleteGameIds(CollectionState state) {
        Set<Long> result = new HashSet<>();
        for (Game game : state.games()) {
            if (!hasBothSides(state.existingSides(), game.getId())) {
                result.add(game.getId());
            }
        }
        return result;
    }

    private void refreshPredictions(
            LocalDate gameDate,
            java.util.Collection<Long> newlyCompletedGameIds,
            java.util.Collection<Long> changedGameIds
    ) {
        for (Long gameId : newlyCompletedGameIds) {
            pendingPredictionRefresh.merge(
                    gameId,
                    PredictionRefreshReason.STARTER_ACQUIRED,
                    (existing, added) -> existing == PredictionRefreshReason.STARTER_CHANGED
                            ? existing : added
            );
        }
        for (Long gameId : changedGameIds) {
            pendingPredictionRefresh.put(
                    gameId, PredictionRefreshReason.STARTER_CHANGED
            );
        }
        for (Map.Entry<Long, PredictionRefreshReason> entry
                : Map.copyOf(pendingPredictionRefresh).entrySet()) {
            try {
                predictionGenerationService.refreshStale(
                        entry.getKey(), entry.getValue()
                );
                pendingPredictionRefresh.remove(
                        entry.getKey(), entry.getValue()
                );
            } catch (RuntimeException exception) {
                log.error(
                        "선발투수 반영을 위한 시스템 예측 재생성 실패 - 다음 동기화에서 재시도: gameDate={}, gameId={}, reason={}",
                        gameDate,
                        entry.getKey(),
                        entry.getValue(),
                        exception
                );
            }
        }
    }

    private StartingPitcherPollResult emptyPollResult(
            LocalDate gameDate,
            LocalDateTime startedAt
    ) {
        return new StartingPitcherPollResult(
                emptyResponse(gameDate, startedAt), List.of(), List.of()
        );
    }

    private StartingPitcherSyncResponse emptyResponse(
            LocalDate gameDate,
            LocalDateTime startedAt
    ) {
        LocalDateTime finishedAt = LocalDateTime.now(clock);
        return new StartingPitcherSyncResponse(
                gameDate, 0, 0, 0, 0, 0, 0, List.of(), startedAt, finishedAt
        );
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private boolean hasBothSides(Set<GameSideKey> existing, Long gameId) {
        return existing.contains(new GameSideKey(gameId, StartingPitcherSide.HOME))
                && existing.contains(new GameSideKey(gameId, StartingPitcherSide.AWAY));
    }

    private List<StartingPitcherSide> missingSides(
            Set<GameSideKey> existing,
            Long gameId
    ) {
        EnumSet<StartingPitcherSide> missing = EnumSet.allOf(
                StartingPitcherSide.class
        );
        missing.removeIf(side -> existing.contains(new GameSideKey(gameId, side)));
        return List.copyOf(missing);
    }

    private record GameSideKey(Long gameId, StartingPitcherSide side) {
    }

    private record CollectionState(
            List<Game> games,
            Set<GameSideKey> existingSides,
            Map<GameSideKey, String> playerIds
    ) {
    }

    private record RetryFilter(
            Map<String, Long> gameIdsByExternalId,
            Set<GameSideKey> writableSides
    ) {
        private boolean shouldWrite(CollectedStartingPitcher pitcher) {
            GameSideKey key = key(pitcher);
            return key.gameId() != null && writableSides.contains(key);
        }

        private GameSideKey key(CollectedStartingPitcher pitcher) {
            return new GameSideKey(
                    gameIdsByExternalId.get(pitcher.externalGameId()),
                    pitcher.side()
            );
        }
    }

    private record WriteBatchResult(
            StartingPitcherSyncResponse response,
            Set<GameSideKey> writtenSides,
            Set<Long> changedGameIds
    ) {
    }
}
