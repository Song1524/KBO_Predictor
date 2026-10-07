package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AutomaticSettlementRecoveryService {
    private final GameRepository games;
    private final GameDataCollector collector;
    private final AutomaticSettlementRecoveryProcessor processor;
    private final Clock clock;
    private final int lookbackDays;
    private final int maxGames;

    public AutomaticSettlementRecoveryService(GameRepository games, GameDataCollector collector,
            AutomaticSettlementRecoveryProcessor processor, Clock clock,
            @Value("${app.kbo-data.settlement-recovery.lookback-days:14}") int lookbackDays,
            @Value("${app.kbo-data.settlement-recovery.max-games:100}") int maxGames) {
        if (lookbackDays < 1 || lookbackDays > 366 || maxGames < 1 || maxGames > 1000) {
            throw new IllegalArgumentException("Settlement recovery requires lookback 1..366 and max-games 1..1000");
        }
        this.games = games;
        this.collector = collector;
        this.processor = processor;
        this.clock = clock;
        this.lookbackDays = lookbackDays;
        this.maxGames = maxGames;
    }

    // Intentionally no transaction: KBO HTTP requests precede per-game DB transactions.
    public void recoverPendingGames() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Game> candidates = games.findAutomaticSettlementRecoveryCandidates(
                now.toLocalDate().minusDays(lookbackDays), now.toLocalDate(), now.toLocalTime(),
                PageRequest.of(0, maxGames));
        Map<LocalDate, List<Game>> byDate = new LinkedHashMap<>();
        for (Game game : candidates) {
            byDate.computeIfAbsent(game.getGameDate(), ignored -> new java.util.ArrayList<>()).add(game);
        }
        for (var entry : byDate.entrySet()) {
            GameCollectionBatch batch;
            try {
                batch = collector.collect(entry.getKey());
                if (!batch.errors().isEmpty()) {
                    log.warn("Settlement recovery collection warnings: date={}, errors={}", entry.getKey(), batch.errors());
                }
            } catch (Exception exception) {
                log.warn("Settlement recovery collection failed; retry next run: date={}", entry.getKey(), exception);
                continue;
            }
            for (Game game : entry.getValue()) {
                try {
                    List<CollectedGame> matches = batch.games().stream()
                            .filter(collected -> AutomaticSettlementRecoveryProcessor.matches(game, collected)).toList();
                    if (matches.size() != 1) {
                        log.warn("Settlement recovery result missing or ambiguous: gameId={}", game.getId());
                        continue;
                    }
                    processor.recover(game.getId(), matches.getFirst());
                } catch (Exception exception) {
                    log.warn("Settlement recovery failed; retry next run: gameId={}", game.getId(), exception);
                }
            }
        }
    }
}
