package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "app.kbo-data.pregame-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PregameDataSyncScheduler {

    private final TeamStatsSyncService teamStatsSyncService;
    private final StartingPitcherSyncService startingPitcherSyncService;
    private final SystemPredictionGenerationService predictionGenerationService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Long> warnedMissingAtClose = ConcurrentHashMap.newKeySet();

    @Value("${app.kbo-data.pregame-scheduler.starting-pitcher-look-ahead-days:1}")
    private int startingPitcherLookAheadDays;

    @Value("${app.kbo-data.pregame-scheduler.prediction-look-ahead-days:7}")
    private int predictionLookAheadDays;

    @EventListener(ApplicationReadyEvent.class)
    public void refreshStalePredictionsAfterStartup() {
        if (!running.compareAndSet(false, true)) {
            log.info("Skipping startup stale prediction refresh because another pregame sync is running.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            refreshStalePredictions(today);
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            cron = "${app.kbo-data.pregame-scheduler.team-stats-cron:0 20 6 * * *}",
            zone = "${app.kbo-data.pregame-scheduler.zone:Asia/Seoul}"
    )
    public void syncDailyTeamStats() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 팀 통계를 건너뜁니다.");
            return;
        }
        try {
            teamStatsSyncService.syncToday();
            LocalDate today = LocalDate.now(clock);
            int lookAhead = Math.max(0, predictionLookAheadDays);
            for (int offset = 0; offset <= lookAhead; offset++) {
                generatePredictions(today.plusDays(offset));
            }
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 팀 통계 자동 동기화 실패 - 다음 실행에서 재시도합니다.",
                    exception
            );
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-poll-fixed-delay-ms:60000}",
            initialDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-poll-initial-delay-ms:15000}"
    )
    public void pollTodaysMissingStartingPitchers() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 오늘 선발투수 polling을 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            pollAndRefresh(today, true);
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 오늘 선발투수 polling 실패 - 다음 실행에서 재시도: gameDate={}",
                    today,
                    exception
            );
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-verification-fixed-delay-ms:600000}",
            initialDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-verification-initial-delay-ms:45000}"
    )
    public void verifyTodaysCompleteStartingPitchers() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 오늘 예고 선발 재검증을 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            startingPitcherSyncService.verifyCompleteBeforeClose(today);
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 오늘 예고 선발 재검증 실패 - 다음 실행에서 재시도: gameDate={}",
                    today,
                    exception
            );
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-look-ahead-fixed-delay-ms:3600000}",
            initialDelayString = "${app.kbo-data.pregame-scheduler.starting-pitcher-look-ahead-initial-delay-ms:30000}"
    )
    public void pollUpcomingMissingStartingPitchers() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 선발투수 look-ahead polling을 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            int lookAhead = Math.max(0, startingPitcherLookAheadDays);
            for (int offset = 1; offset <= lookAhead; offset++) {
                LocalDate target = today.plusDays(offset);
                try {
                    pollAndRefresh(target, false);
                    startingPitcherSyncService.verifyCompleteBeforeClose(target);
                } catch (RuntimeException exception) {
                    log.error(
                            "KBO 선발투수 look-ahead polling 실패 - 다음 실행에서 재시도: gameDate={}",
                            target,
                            exception
                    );
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void pollAndRefresh(LocalDate date, boolean warnAtClose) {
        StartingPitcherPollResult result =
                startingPitcherSyncService.pollMissingBeforeClose(date);
        if (!warnAtClose) {
            return;
        }
        for (MissingStartingPitcherGame missing : result.missingAtClose()) {
            if (warnedMissingAtClose.add(missing.gameId())) {
                log.warn(
                        "예측 마감까지 선발투수를 확보하지 못했습니다: gameDate={}, gameId={}, externalGameId={}, predictionCloseAt={}, missingSides={}",
                        date,
                        missing.gameId(),
                        missing.externalGameId(),
                        missing.predictionCloseAt(),
                        missing.missingSides()
                );
            }
        }
    }

    private void generatePredictions(LocalDate date) {
        try {
            predictionGenerationService.generateForDate(date);
        } catch (RuntimeException exception) {
            log.error(
                    "시스템 예측 자동 생성 실패 - 다음 수집 후 재시도: date={}",
                    date,
                    exception
            );
        }
    }

    private void refreshStalePredictions(LocalDate date) {
        try {
            predictionGenerationService.refreshStaleForDate(date);
        } catch (RuntimeException exception) {
            log.error(
                    "Stale system prediction refresh failed; it will retry on the next scheduled run. date={}",
                    date,
                    exception
            );
        }
    }
}
