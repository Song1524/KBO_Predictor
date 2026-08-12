package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
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

    @Value("${app.kbo-data.pregame-scheduler.starting-pitcher-look-ahead-days:1}")
    private int startingPitcherLookAheadDays;

    @Value("${app.kbo-data.pregame-scheduler.prediction-look-ahead-days:7}")
    private int predictionLookAheadDays;

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
            cron = "${app.kbo-data.pregame-scheduler.starting-pitchers-cron:0 0 15 * * *}",
            zone = "${app.kbo-data.pregame-scheduler.zone:Asia/Seoul}"
    )
    public void syncDailyStartingPitchers() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 선발투수를 건너뜁니다.");
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            int lookAhead = Math.max(0, startingPitcherLookAheadDays);
            for (int offset = 0; offset <= lookAhead; offset++) {
                LocalDate target = today.plusDays(offset);
                try {
                    startingPitcherSyncService.sync(target);
                    generatePredictions(target);
                } catch (RuntimeException exception) {
                    log.error(
                            "KBO 선발투수 자동 동기화 실패 - 다음 실행에서 재시도: gameDate={}",
                            target,
                            exception
                    );
                }
            }
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            cron = "${app.kbo-data.pregame-scheduler.starting-pitchers-retry-cron:0 30 15-17 * * *}",
            zone = "${app.kbo-data.pregame-scheduler.zone:Asia/Seoul}"
    )
    public void retryMissingStartingPitchers() {
        if (!running.compareAndSet(false, true)) {
            log.info("KBO 경기 전 데이터 수집이 이미 실행 중이어서 선발투수 재시도를 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            StartingPitcherSyncResponse response =
                    startingPitcherSyncService.retryMissingBeforeStart(today);
            if (response.insertedCount() > 0 || response.updatedCount() > 0) {
                generatePredictions(today);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "KBO 미발표 선발투수 재시도 실패 - 다음 실행에서 재시도: gameDate={}",
                    today,
                    exception
            );
        } finally {
            running.set(false);
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
}
