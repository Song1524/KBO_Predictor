package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;

@ExtendWith(MockitoExtension.class)
class PregameDataSyncSchedulerTest {

    @Mock
    private TeamStatsSyncService teamStatsSyncService;

    @Mock
    private StartingPitcherSyncService startingPitcherSyncService;

    @Mock
    private SystemPredictionGenerationService predictionGenerationService;

    private PregameDataSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-10T03:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
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
    void teamStatsRunOnceAndStartersCoverTodayAndTomorrow() {
        scheduler.syncDailyTeamStats();
        scheduler.syncDailyStartingPitchers();

        LocalDate today = LocalDate.of(2026, 8, 10);
        verify(teamStatsSyncService).syncToday();
        verify(startingPitcherSyncService).sync(today);
        verify(startingPitcherSyncService).sync(today.plusDays(1));
        verify(predictionGenerationService, org.mockito.Mockito.times(2))
                .generateForDate(today);
        verify(predictionGenerationService, org.mockito.Mockito.times(2))
                .generateForDate(today.plusDays(1));
    }

    @Test
    void retryRegeneratesPredictionsOnlyWhenMissingStarterWasSaved() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        when(startingPitcherSyncService.retryMissingBeforeStart(today))
                .thenReturn(new StartingPitcherSyncResponse(
                        today, 5, 1, 1, 0, 1, 0, List.of(), now, now
                ));

        scheduler.retryMissingStartingPitchers();

        verify(startingPitcherSyncService).retryMissingBeforeStart(today);
        verify(predictionGenerationService).generateForDate(today);
    }

    @Test
    void retryDoesNotRegenerateWhenAllStartersWereAlreadyPresent() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        when(startingPitcherSyncService.retryMissingBeforeStart(today))
                .thenReturn(new StartingPitcherSyncResponse(
                        today, 0, 0, 0, 0, 0, 0, List.of(), now, now
                ));

        scheduler.retryMissingStartingPitchers();

        verify(predictionGenerationService, never()).generateForDate(today);
    }
}
