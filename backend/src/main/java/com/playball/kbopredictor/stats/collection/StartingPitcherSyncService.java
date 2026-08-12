package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartingPitcherSyncService {

    private final StartingPitcherCollector collector;
    private final StartingPitcherWriter writer;
    private final GameRepository gameRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final Clock clock;

    public StartingPitcherSyncResponse syncToday() {
        return sync(LocalDate.now(clock));
    }

    public StartingPitcherSyncResponse sync(LocalDate gameDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);

        // 게임 목록과 선수 상세 조회를 모두 끝낸 후 짧은 단건 DB 트랜잭션으로 저장한다.
        StartingPitcherCollectionBatch batch = collector.collect(gameDate);

        return writeBatch(gameDate, batch, null, startedAt);
    }

    public StartingPitcherSyncResponse retryMissingBeforeStart(LocalDate gameDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Game> games = gameRepository.findByGameDateOrderByGameTimeAsc(gameDate)
                .stream()
                .filter(game -> game.getStatus() == GameStatus.SCHEDULED)
                .filter(game -> game.getGameTime() != null)
                .filter(game -> now.isBefore(LocalDateTime.of(
                        game.getGameDate(), game.getGameTime()
                )))
                .toList();
        if (games.isEmpty()) {
            return emptyResponse(gameDate, startedAt);
        }

        List<Long> gameIds = games.stream().map(Game::getId).toList();
        Set<GameSideKey> existing = new HashSet<>();
        for (StartingPitcher pitcher
                : startingPitcherRepository.findByGameIdInWithPlayer(gameIds)) {
            existing.add(new GameSideKey(
                    pitcher.getGame().getId(), pitcher.getSide()
            ));
        }

        Map<String, Long> gameIdsByExternalId = new HashMap<>();
        Set<String> incompleteExternalGameIds = new HashSet<>();
        for (Game game : games) {
            gameIdsByExternalId.put(game.getExternalGameId(), game.getId());
            if (!existing.contains(new GameSideKey(game.getId(), StartingPitcherSide.HOME))
                    || !existing.contains(new GameSideKey(game.getId(), StartingPitcherSide.AWAY))) {
                incompleteExternalGameIds.add(game.getExternalGameId());
            }
        }
        if (incompleteExternalGameIds.isEmpty()) {
            return emptyResponse(gameDate, startedAt);
        }

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
        return writeBatch(gameDate, batch, new RetryFilter(gameIdsByExternalId, missing), startedAt);
    }

    private StartingPitcherSyncResponse writeBatch(
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
        LocalDate statDate = LocalDate.now(clock);
        for (CollectedStartingPitcher pitcher : batch.pitchers()) {
            if (retryFilter != null && !retryFilter.shouldWrite(pitcher)) {
                continue;
            }
            collectedPitchers++;
            try {
                StartingPitcherWriteResult result = writer.upsert(
                        pitcher,
                        statDate,
                        LocalDateTime.now(clock)
                );
                if (result.inserted()) {
                    inserted++;
                } else {
                    updated++;
                }
                if (result.pitcherStatSaved()) {
                    statsSaved++;
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
        return response;
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

    private record GameSideKey(Long gameId, StartingPitcherSide side) {
    }

    private record RetryFilter(
            Map<String, Long> gameIdsByExternalId,
            Set<GameSideKey> missing
    ) {
        private boolean shouldWrite(CollectedStartingPitcher pitcher) {
            Long gameId = gameIdsByExternalId.get(pitcher.externalGameId());
            return gameId != null && missing.contains(new GameSideKey(gameId, pitcher.side()));
        }
    }
}
