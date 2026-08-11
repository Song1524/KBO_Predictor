package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamStatsSyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    @Mock
    private TeamStatsCollector collector;

    @Mock
    private TeamStatSnapshotWriter writer;

    private TeamStatsSyncService service;
    private CollectedTeamStat lg;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-10T03:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        service = new TeamStatsSyncService(collector, writer, clock);
        lg = new CollectedTeamStat(
                new OfficialTeamStanding(
                        "LG", 55, 45, 1, new BigDecimal("0.550"),
                        3, 6, 1, 32, 20, 0, 23, 25, 1
                ),
                new BigDecimal("0.270"),
                new BigDecimal("4.90")
        );
    }

    @Test
    void sameDateIsUpdatedAndNextDateUsesANewSnapshotKey() {
        when(collector.collect()).thenReturn(List.of(lg));
        when(writer.upsert(eq(lg), eq(2026), eq(TODAY), any()))
                .thenReturn(new TeamStatWriteResult(true))
                .thenReturn(new TeamStatWriteResult(false));
        when(writer.upsert(eq(lg), eq(2026), eq(TODAY.plusDays(1)), any()))
                .thenReturn(new TeamStatWriteResult(true));

        TeamStatsSyncResponse first = service.sync(TODAY);
        TeamStatsSyncResponse second = service.sync(TODAY);
        TeamStatsSyncResponse nextDay = service.sync(TODAY.plusDays(1));

        assertThat(first.insertedCount()).isOne();
        assertThat(second.updatedCount()).isOne();
        assertThat(nextDay.insertedCount()).isOne();
    }

    @Test
    void collectorFailureDoesNotStartDatabaseWrites() {
        when(collector.collect()).thenThrow(
                new PregameDataCollectionException("KBO unavailable")
        );

        assertThatThrownBy(() -> service.sync(TODAY))
                .isInstanceOf(PregameDataCollectionException.class);
        verify(writer, never()).upsert(any(), any(), any(), any());
    }
}
