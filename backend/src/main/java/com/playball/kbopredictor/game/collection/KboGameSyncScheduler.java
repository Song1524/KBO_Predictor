package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "app.kbo-data.sync-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class KboGameSyncScheduler {

    private final GameSyncService gameSyncService;
    private final GameRepository gameRepository;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<EmptyScheduleCheck> emptyScheduleCheck =
            new AtomicReference<>();
    private final AtomicReference<LocalDate> verifiedScheduleDate =
            new AtomicReference<>();

    @Value("${app.kbo-data.sync-scheduler.look-ahead-days:7}")
    private int lookAheadDays;

    @Value("${app.kbo-data.sync-scheduler.status-refresh-lead-minutes:60}")
    private int statusRefreshLeadMinutes;

    @Value("${app.kbo-data.sync-scheduler.missing-schedule-retry-minutes:60}")
    private int missingScheduleRetryMinutes;

    @Value("${app.kbo-data.sync-scheduler.final-result-retry-hours:6}")
    private int finalResultRetryHours;

    @Scheduled(
            cron = "${app.kbo-data.sync-scheduler.schedule-cron:0 0 6 * * *}",
            zone = "${app.kbo-data.sync-scheduler.zone:Asia/Seoul}"
    )
    public void syncUpcomingSchedule() {
        LocalDate today = LocalDate.now(clock);
        LocalDate through = today.plusDays(Math.max(0, lookAheadDays));
        String target = today + "~" + through;
        long startedNanos = System.nanoTime();

        if (!running.compareAndSet(false, true)) {
            logSkipped("일정 수집", target, "다른 KBO 동기화 실행 중", startedNanos);
            return;
        }

        List<GameSyncResponse> responses = new ArrayList<>();
        int requestFailures = 0;
        try {
            Map<YearMonth, List<LocalDate>> datesByMonth = today
                    .datesUntil(through.plusDays(1))
                    .collect(Collectors.groupingBy(
                            YearMonth::from,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            for (Map.Entry<YearMonth, List<LocalDate>> entry
                    : datesByMonth.entrySet()) {
                try {
                    responses.addAll(
                            gameSyncService.syncDates(entry.getValue())
                    );
                } catch (RuntimeException exception) {
                    requestFailures++;
                    log.error(
                            "KBO 일정 월별 수집 실패 - 다음 실행에서 재시도: yearMonth={}, targetDates={}",
                            entry.getKey(),
                            entry.getValue(),
                            exception
                    );
                }
            }
            responses.stream()
                    .filter(response -> response.targetDate().equals(today))
                    .findFirst()
                    .ifPresent(response -> rememberSyncResult(today, response));
            logSummary(
                    "일정 수집",
                    target,
                    responses,
                    requestFailures,
                    startedNanos
            );
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString =
                    "${app.kbo-data.sync-scheduler.status-fixed-delay-ms:300000}",
            initialDelayString =
                    "${app.kbo-data.sync-scheduler.status-initial-delay-ms:30000}"
    )
    public void refreshTodayGameStatuses() {
        LocalDate today = LocalDate.now(clock);
        long startedNanos = System.nanoTime();

        if (!running.compareAndSet(false, true)) {
            logSkipped(
                    "당일 상태 갱신",
                    today.toString(),
                    "다른 KBO 동기화 실행 중",
                    startedNanos
            );
            return;
        }

        try {
            RefreshDecision decision = decideStatusRefresh(today);
            if (!decision.shouldRefresh()) {
                logSkipped(
                        "당일 상태 갱신",
                        today.toString(),
                        decision.reason(),
                        startedNanos
                );
                return;
            }

            try {
                GameSyncResponse response = gameSyncService.sync(today);
                rememberSyncResult(today, response);
                logSummary(
                        "당일 상태 갱신",
                        today.toString(),
                        List.of(response),
                        0,
                        startedNanos
                );
            } catch (RuntimeException exception) {
                log.error(
                        "KBO 당일 상태 갱신 실패 - 다음 실행에서 재시도: targetDate={}, sourceRows=0, inserted=0, updated=0, statusChanged=0, finished=0, cancelled=0, settlementSuccess=0, failed=1, elapsedMs={}",
                        today,
                        elapsedMillis(startedNanos),
                        exception
                );
            }
        } finally {
            running.set(false);
        }
    }

    private RefreshDecision decideStatusRefresh(LocalDate today) {
        List<Game> todayGames = gameRepository
                .findByGameDateOrderByGameTimeAsc(today);
        if (todayGames.isEmpty()) {
            EmptyScheduleCheck previousCheck = emptyScheduleCheck.get();
            LocalDateTime retryFrom = previousCheck == null
                    ? null
                    : previousCheck.checkedAt().plusMinutes(
                            Math.max(1, missingScheduleRetryMinutes)
                    );
            if (previousCheck != null
                    && previousCheck.targetDate().equals(today)
                    && LocalDateTime.now(clock).isBefore(retryFrom)) {
                return new RefreshDecision(
                        false,
                        "빈 일정 재확인 시각 전(" + retryFrom + ")"
                );
            }
            return new RefreshDecision(true, null);
        }

        List<Game> incompleteFinishedGames = todayGames.stream()
                .filter(this::hasIncompleteFinalResult)
                .toList();
        if (!incompleteFinishedGames.isEmpty()) {
            boolean withinRetryWindow = incompleteFinishedGames.stream()
                    .anyMatch(this::isWithinFinalResultRetryWindow);
            if (withinRetryWindow) {
                return new RefreshDecision(true, null);
            }
            return new RefreshDecision(
                    false,
                    "KBO final score confirmation retry window has ended"
            );
        }

        if (!today.equals(verifiedScheduleDate.get())) {
            return new RefreshDecision(true, null);
        }

        List<Game> activeGames = todayGames.stream()
                .filter(game -> game.getStatus() == GameStatus.SCHEDULED
                        || game.getStatus() == GameStatus.IN_PROGRESS)
                .toList();

        if (activeGames.isEmpty()) {
            return new RefreshDecision(
                    false,
                    "오늘 진행 대기 중인 KBO 경기가 없음"
            );
        }
        if (activeGames.stream()
                .anyMatch(game -> game.getStatus() == GameStatus.IN_PROGRESS)) {
            return new RefreshDecision(true, null);
        }

        LocalTime earliestGameTime = activeGames.stream()
                .map(Game::getGameTime)
                .filter(time -> time != null)
                .min(LocalTime::compareTo)
                .orElse(null);
        if (earliestGameTime == null) {
            return new RefreshDecision(true, null);
        }

        LocalDateTime refreshFrom = LocalDateTime.of(today, earliestGameTime)
                .minusMinutes(Math.max(0, statusRefreshLeadMinutes));
        if (LocalDateTime.now(clock).isBefore(refreshFrom)) {
            return new RefreshDecision(
                    false,
                    "첫 경기 상태 갱신 시작 시각 전(" + refreshFrom + ")"
            );
        }
        return new RefreshDecision(true, null);
    }

    private boolean hasIncompleteFinalResult(Game game) {
        if (game.getStatus() != GameStatus.FINISHED) {
            return false;
        }
        Integer homeScore = game.getHomeScore();
        Integer awayScore = game.getAwayScore();
        if (homeScore == null || awayScore == null
                || homeScore < 0 || awayScore < 0
                || game.getResult() == null) {
            return true;
        }
        return resultOf(homeScore, awayScore) != game.getResult();
    }

    private boolean isWithinFinalResultRetryWindow(Game game) {
        if (game.getGameTime() == null) {
            return LocalDateTime.now(clock).isBefore(
                    game.getGameDate().plusDays(1).atStartOfDay()
            );
        }
        LocalDateTime retryUntil = LocalDateTime.of(
                game.getGameDate(),
                game.getGameTime()
        ).plusHours(Math.max(1, finalResultRetryHours));
        return !LocalDateTime.now(clock).isAfter(retryUntil);
    }

    private GameResult resultOf(int homeScore, int awayScore) {
        if (homeScore > awayScore) {
            return GameResult.HOME_WIN;
        }
        if (homeScore < awayScore) {
            return GameResult.AWAY_WIN;
        }
        return GameResult.DRAW;
    }

    private void rememberSyncResult(
            LocalDate targetDate,
            GameSyncResponse response
    ) {
        if (response.failedCount() == 0) {
            verifiedScheduleDate.set(targetDate);
        } else {
            verifiedScheduleDate.set(null);
        }

        if (response.collectedGameCount() == 0
                && response.failedCount() == 0) {
            emptyScheduleCheck.set(new EmptyScheduleCheck(
                    targetDate,
                    LocalDateTime.now(clock)
            ));
            return;
        }
        emptyScheduleCheck.set(null);
    }

    private void logSummary(
            String job,
            String target,
            List<GameSyncResponse> responses,
            int requestFailures,
            long startedNanos
    ) {
        int sourceRows = sum(responses, Metric.SOURCE_ROWS);
        int inserted = sum(responses, Metric.INSERTED);
        int updated = sum(responses, Metric.UPDATED);
        int statusChanged = sum(responses, Metric.STATUS_CHANGED);
        int finished = sum(responses, Metric.FINISHED);
        int cancelled = sum(responses, Metric.CANCELLED);
        int settlementSuccess = sum(responses, Metric.SETTLEMENT_SUCCESS);
        int failed = sum(responses, Metric.FAILED) + requestFailures;

        log.info(
                "KBO 자동 {} 완료: target={}, sourceRows={}, inserted={}, updated={}, statusChanged={}, finished={}, cancelled={}, settlementSuccess={}, failed={}, elapsedMs={}",
                job,
                target,
                sourceRows,
                inserted,
                updated,
                statusChanged,
                finished,
                cancelled,
                settlementSuccess,
                failed,
                elapsedMillis(startedNanos)
        );
    }

    private int sum(List<GameSyncResponse> responses, Metric metric) {
        return responses.stream().mapToInt(metric::value).sum();
    }

    private void logSkipped(
            String job,
            String target,
            String reason,
            long startedNanos
    ) {
        log.info(
                "KBO 자동 {} 건너뜀: target={}, reason={}, sourceRows=0, inserted=0, updated=0, statusChanged=0, finished=0, cancelled=0, settlementSuccess=0, failed=0, elapsedMs={}",
                job,
                target,
                reason,
                elapsedMillis(startedNanos)
        );
    }

    private long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private enum Metric {
        SOURCE_ROWS {
            @Override
            int value(GameSyncResponse response) {
                return response.sourceRowCount();
            }
        },
        INSERTED {
            @Override
            int value(GameSyncResponse response) {
                return response.insertedCount();
            }
        },
        UPDATED {
            @Override
            int value(GameSyncResponse response) {
                return response.updatedCount();
            }
        },
        STATUS_CHANGED {
            @Override
            int value(GameSyncResponse response) {
                return response.statusChangedCount();
            }
        },
        FINISHED {
            @Override
            int value(GameSyncResponse response) {
                return response.finishedCount();
            }
        },
        CANCELLED {
            @Override
            int value(GameSyncResponse response) {
                return response.cancelledCount();
            }
        },
        SETTLEMENT_SUCCESS {
            @Override
            int value(GameSyncResponse response) {
                return response.settlementSuccessCount();
            }
        },
        FAILED {
            @Override
            int value(GameSyncResponse response) {
                return response.failedCount();
            }
        };

        abstract int value(GameSyncResponse response);
    }

    private record RefreshDecision(boolean shouldRefresh, String reason) {
    }

    private record EmptyScheduleCheck(
            LocalDate targetDate,
            LocalDateTime checkedAt
    ) {
    }
}
