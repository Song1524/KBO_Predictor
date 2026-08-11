package com.playball.kbopredictor.prediction.scheduler;

import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.history.SystemPredictionFinalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "app.prediction.history-finalization-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SystemPredictionFinalizationScheduler {

    private final GameRepository gameRepository;
    private final SystemPredictionFinalizationService finalizationService;
    private final Clock clock;

    @Value("${app.prediction.history-finalization-scheduler.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.prediction.history-finalization-scheduler.fixed-delay-ms:5000}",
            initialDelayString = "${app.prediction.history-finalization-scheduler.initial-delay-ms:1500}"
    )
    public void finalizeClosedPredictions() {
        LocalDateTime checkedAt = LocalDateTime.now(clock);
        var gameIds = gameRepository.findIdsPendingSystemPredictionFinalization(
                checkedAt,
                PageRequest.of(0, batchSize)
        );
        int finalized = 0;
        for (Long gameId : gameIds) {
            try {
                if (finalizationService.finalizeClosedGame(gameId)) {
                    finalized++;
                }
            } catch (RuntimeException exception) {
                log.error(
                        "시스템 예측 FINAL 보존 실패: gameId={}, checkedAt={}",
                        gameId,
                        checkedAt,
                        exception
                );
            }
        }
        if (!gameIds.isEmpty()) {
            log.info(
                    "시스템 예측 FINAL 보존 완료: checkedAt={}, candidates={}, finalized={}",
                    checkedAt,
                    gameIds.size(),
                    finalized
            );
        }
    }
}
