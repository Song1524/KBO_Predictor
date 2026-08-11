package com.playball.kbopredictor.prediction.scheduler;

import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "app.odds.closing-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class GameOddsClosingScheduler {

    private final GameRepository gameRepository;
    private final GameOddsService gameOddsService;
    private final Clock clock;

    @Value("${app.odds.closing-scheduler.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString =
                    "${app.odds.closing-scheduler.fixed-delay-ms:60000}",
            initialDelayString =
                    "${app.odds.closing-scheduler.initial-delay-ms:5000}"
    )
    public void finalizeExpiredOdds() {
        LocalDateTime checkedAt = LocalDateTime.now(clock);
        List<Long> gameIds = gameRepository.findIdsPendingOddsFinalization(
                checkedAt,
                PageRequest.of(0, batchSize)
        );

        int finalizedCount = 0;
        for (Long gameId : gameIds) {
            try {
                if (gameOddsService.finalizeExpiredGame(gameId)) {
                    finalizedCount++;
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to finalize game odds: gameId={}, checkedAt={}",
                        gameId,
                        checkedAt,
                        exception
                );
            }
        }

        if (finalizedCount > 0) {
            log.info(
                    "Completed scheduled odds finalization: checkedAt={}, " +
                            "candidates={}, finalized={}",
                    checkedAt,
                    gameIds.size(),
                    finalizedCount
            );
        }
    }
}
