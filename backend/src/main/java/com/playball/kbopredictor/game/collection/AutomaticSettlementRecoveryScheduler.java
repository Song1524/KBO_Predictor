package com.playball.kbopredictor.game.collection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.kbo-data.settlement-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class AutomaticSettlementRecoveryScheduler {
    private final AutomaticSettlementRecoveryService recovery;

    @Scheduled(fixedDelayString = "${app.kbo-data.settlement-recovery.fixed-delay-ms:300000}",
            initialDelayString = "${app.kbo-data.settlement-recovery.initial-delay-ms:60000}")
    public void recoverPendingGames() {
        try {
            recovery.recoverPendingGames();
        } catch (Exception exception) {
            log.error("Settlement recovery batch failed; retry next run", exception);
        }
    }
}
