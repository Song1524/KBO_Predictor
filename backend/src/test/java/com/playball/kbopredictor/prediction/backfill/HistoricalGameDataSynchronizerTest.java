package com.playball.kbopredictor.prediction.backfill;

import com.playball.kbopredictor.game.collection.GameSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalGameDataSynchronizerTest {

    @Mock
    private GameSyncService gameSyncService;

    @Test
    void oneFailedMonthDoesNotPreventTheNextMonthlyRequest() {
        when(gameSyncService.syncDates(anyList()))
                .thenThrow(new IllegalStateException("KBO unavailable"))
                .thenReturn(List.of());
        HistoricalGameDataSynchronizer synchronizer =
                new HistoricalGameDataSynchronizer(gameSyncService);

        HistoricalGameSyncSummary result = synchronizer.syncRange(
                LocalDate.of(2025, 10, 1),
                LocalDate.of(2026, 3, 10)
        );

        assertThat(result.requestedMonthCount()).isEqualTo(2);
        assertThat(result.successfulMonthCount()).isOne();
        assertThat(result.failedMonthCount()).isOne();
        assertThat(result.errors()).anyMatch(
                error -> error.contains("KBO unavailable")
        );
        verify(gameSyncService, times(2)).syncDates(anyList());
    }

    @Test
    void multiSeasonRangeSkipsOffSeasonMonths() {
        when(gameSyncService.syncDates(anyList())).thenReturn(List.of());
        HistoricalGameDataSynchronizer synchronizer =
                new HistoricalGameDataSynchronizer(gameSyncService);

        HistoricalGameSyncSummary result = synchronizer.syncRange(
                LocalDate.of(2024, 9, 1),
                LocalDate.of(2025, 4, 30)
        );

        assertThat(result.requestedMonthCount()).isEqualTo(4);
        assertThat(result.successfulMonthCount()).isEqualTo(4);
        verify(gameSyncService, times(4)).syncDates(anyList());
    }

    @Test
    void preSeasonRangeDoesNotCallTheOfficialSchedule() {
        HistoricalGameDataSynchronizer synchronizer =
                new HistoricalGameDataSynchronizer(gameSyncService);

        HistoricalGameSyncSummary result = synchronizer.syncSeasonThrough(
                LocalDate.of(2026, 2, 10)
        );

        assertThat(result.requestedMonthCount()).isZero();
        verifyNoInteractions(gameSyncService);
    }
}
