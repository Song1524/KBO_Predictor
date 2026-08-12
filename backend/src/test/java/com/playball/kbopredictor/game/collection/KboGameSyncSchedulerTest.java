package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KboGameSyncSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final LocalDateTime NOW = TODAY.atTime(12, 0);

    @Mock
    private GameSyncService gameSyncService;

    @Mock
    private GameRepository gameRepository;

    private KboGameSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        scheduler = new KboGameSyncScheduler(
                gameSyncService,
                gameRepository,
                clock
        );
        ReflectionTestUtils.setField(scheduler, "lookAheadDays", 7);
        ReflectionTestUtils.setField(
                scheduler,
                "statusRefreshLeadMinutes",
                60
        );
        ReflectionTestUtils.setField(
                scheduler,
                "missingScheduleRetryMinutes",
                60
        );
    }

    @Test
    void dailyScheduleSyncRequestsTodayThroughSevenDaysAhead() {
        List<LocalDate> expectedDates = TODAY
                .datesUntil(TODAY.plusDays(8))
                .toList();
        when(gameSyncService.syncDates(expectedDates)).thenReturn(List.of());

        scheduler.syncUpcomingSchedule();

        verify(gameSyncService).syncDates(expectedDates);
    }

    @Test
    void missingTodayScheduleTriggersRecoverySync() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of());
        when(gameSyncService.sync(TODAY)).thenReturn(emptyResponse());

        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService).sync(TODAY);
    }

    @Test
    void successfulEmptyScheduleIsThrottledUntilRetryInterval() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of());
        when(gameSyncService.sync(TODAY)).thenReturn(emptyResponse());

        scheduler.refreshTodayGameStatuses();
        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService).sync(TODAY);
    }

    @Test
    void failedMissingScheduleRequestRetriesOnNextExecution() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of());
        when(gameSyncService.sync(TODAY))
                .thenThrow(new GameDataCollectionException("KBO 요청 실패"))
                .thenReturn(emptyResponse());

        scheduler.refreshTodayGameStatuses();
        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService, times(2)).sync(TODAY);
    }

    @Test
    void terminalGamesAreVerifiedOnceAfterStartupThenStopRefreshing() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of(game(GameStatus.FINISHED, LocalTime.of(18, 30))));
        when(gameSyncService.sync(TODAY)).thenReturn(response());

        scheduler.refreshTodayGameStatuses();
        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService).sync(TODAY);
    }

    @Test
    void scheduledGameIsVerifiedOnceAfterStartupThenWaitsForRefreshWindow() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of(game(GameStatus.SCHEDULED, LocalTime.of(18, 30))));
        when(gameSyncService.sync(TODAY)).thenReturn(response());

        scheduler.refreshTodayGameStatuses();
        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService).sync(TODAY);
    }

    @Test
    void inProgressGameTriggersTodayStatusSync() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of(game(GameStatus.IN_PROGRESS, LocalTime.of(18, 30))));
        when(gameSyncService.sync(TODAY)).thenReturn(response());

        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService).sync(TODAY);
    }

    @Test
    void failedTodayRequestCanRetryOnNextSchedulerExecution() {
        when(gameRepository.findByGameDateOrderByGameTimeAsc(TODAY))
                .thenReturn(List.of(game(GameStatus.IN_PROGRESS, LocalTime.of(18, 30))));
        when(gameSyncService.sync(TODAY))
                .thenThrow(new GameDataCollectionException("KBO 요청 실패"))
                .thenReturn(response());

        scheduler.refreshTodayGameStatuses();
        scheduler.refreshTodayGameStatuses();

        verify(gameSyncService, times(2)).sync(TODAY);
    }

    private Game game(GameStatus status, LocalTime gameTime) {
        return Game.createCollected(
                "20260810LGOB0",
                2026,
                TODAY,
                gameTime,
                null,
                null,
                "잠실",
                status,
                null,
                null,
                null,
                null,
                null,
                NOW
        );
    }

    private GameSyncResponse response() {
        return new GameSyncResponse(
                TODAY,
                5,
                5,
                0,
                5,
                1,
                1,
                0,
                1,
                0,
                List.of(),
                NOW,
                NOW.plusSeconds(1)
        );
    }

    private GameSyncResponse emptyResponse() {
        return new GameSyncResponse(
                TODAY,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                NOW,
                NOW.plusSeconds(1)
        );
    }
}
