package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PregameDataSyncSchedulerTest {

    @Mock
    private TeamStatsSyncService teamStatsSyncService;

    @Mock
    private StartingPitcherSyncService startingPitcherSyncService;

    @Mock
    private SystemPredictionGenerationService predictionGenerationService;

    private PregameDataSyncScheduler scheduler;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-10T03:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        today = LocalDate.of(2026, 8, 10);
        scheduler = new PregameDataSyncScheduler(
                teamStatsSyncService,
                startingPitcherSyncService,
                predictionGenerationService,
                clock
        );
        ReflectionTestUtils.setField(
                scheduler,
                "startingPitcherLookAheadDays",
                1
        );
        ReflectionTestUtils.setField(
                scheduler,
                "predictionLookAheadDays",
                1
        );
    }

    @Test
    void teamStatsStillGeneratePredictionsForConfiguredDates() {
        scheduler.syncDailyTeamStats();

        verify(teamStatsSyncService).syncToday();
        verify(predictionGenerationService).generateForDate(today);
        verify(predictionGenerationService).generateForDate(today.plusDays(1));
    }

    @Test
    void todayPollingDelegatesMissingCollectionWithoutDuplicatingPredictionRefresh() {
        when(startingPitcherSyncService.pollMissingBeforeClose(today))
                .thenReturn(pollResult(List.of(11L, 12L), List.of()));

        scheduler.pollTodaysMissingStartingPitchers();

        verify(startingPitcherSyncService).pollMissingBeforeClose(today);
        verify(predictionGenerationService, never()).refreshStale(11L);
        verify(predictionGenerationService, never()).refreshStale(12L);
        verify(predictionGenerationService, never()).refreshStaleForDate(today);
        verify(predictionGenerationService, never()).generateForDate(today);
    }

    @Test
    void completeStarterVerificationUsesSeparateLowFrequencyPath() {
        scheduler.verifyTodaysCompleteStartingPitchers();

        verify(startingPitcherSyncService).verifyCompleteBeforeClose(today);
        verify(startingPitcherSyncService, never()).pollMissingBeforeClose(today);
    }

    @Test
    void lookAheadPollingChecksTomorrowWithoutDependingOnDailyCron() {
        LocalDate tomorrow = today.plusDays(1);
        when(startingPitcherSyncService.pollMissingBeforeClose(tomorrow))
                .thenReturn(pollResult(List.of(), List.of()));

        scheduler.pollUpcomingMissingStartingPitchers();

        verify(startingPitcherSyncService).pollMissingBeforeClose(tomorrow);
        verify(startingPitcherSyncService).verifyCompleteBeforeClose(tomorrow);
        verify(startingPitcherSyncService, never()).pollMissingBeforeClose(today);
    }

    @Test
    void missingStarterWarningIsLoggedOncePerGame(CapturedOutput output) {
        MissingStartingPitcherGame missing = new MissingStartingPitcherGame(
                11L,
                "20260810HHLG0",
                LocalDateTime.of(2026, 8, 10, 11, 50),
                List.of(StartingPitcherSide.AWAY)
        );
        when(startingPitcherSyncService.pollMissingBeforeClose(today))
                .thenReturn(pollResult(List.of(), List.of(missing)));

        scheduler.pollTodaysMissingStartingPitchers();
        scheduler.pollTodaysMissingStartingPitchers();

        assertThat(count(output.getOut(), "예측 마감까지 선발투수를 확보하지 못했습니다"))
                .isEqualTo(1);
        assertThat(output.getOut()).contains("missingSides=[AWAY]");
    }

    @Test
    void startupChecksTodaysStalePredictionsOnce() {
        scheduler.refreshStalePredictionsAfterStartup();

        verify(predictionGenerationService).refreshStaleForDate(today);
        verify(startingPitcherSyncService, never())
                .pollMissingBeforeClose(today);
    }

    private StartingPitcherPollResult pollResult(
            List<Long> completedGameIds,
            List<MissingStartingPitcherGame> missingAtClose
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        return new StartingPitcherPollResult(
                new StartingPitcherSyncResponse(
                        today, 5, 0, 0, 0, 0, 0, List.of(), now, now
                ),
                completedGameIds,
                missingAtClose
        );
    }

    private int count(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
