package com.playball.kbopredictor.game.collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KboGameDataCollectorTest {

    @Mock
    private KboScheduleClient scheduleClient;

    @Mock
    private KboScheduleParser scheduleParser;

    @Test
    void datesInSameMonthReuseSingleOfficialScheduleRequest() {
        LocalDate firstDate = LocalDate.of(2026, 8, 10);
        LocalDate secondDate = firstDate.plusDays(1);
        GameCollectionBatch empty = new GameCollectionBatch(
                0,
                List.of(),
                List.of()
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("official-json");
        when(scheduleParser.parse("official-json", firstDate)).thenReturn(empty);
        when(scheduleParser.parse("official-json", secondDate)).thenReturn(empty);
        KboGameDataCollector collector = new KboGameDataCollector(
                scheduleClient,
                scheduleParser
        );

        Map<LocalDate, GameCollectionBatch> result = collector.collectDates(
                List.of(firstDate, secondDate)
        );

        assertThat(result).containsOnlyKeys(firstDate, secondDate);
        verify(scheduleClient).fetchSchedule(YearMonth.of(2026, 8));
        verify(scheduleParser).parse("official-json", firstDate);
        verify(scheduleParser).parse("official-json", secondDate);
    }

    @Test
    void completedMonthIsCachedAcrossBackfillCalls() {
        LocalDate date = LocalDate.of(2025, 6, 1);
        GameCollectionBatch empty = new GameCollectionBatch(
                0, List.of(), List.of()
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2025, 6)))
                .thenReturn("historical-json");
        when(scheduleParser.parse("historical-json", date)).thenReturn(empty);
        KboGameDataCollector collector = new KboGameDataCollector(
                scheduleClient,
                scheduleParser
        );

        collector.collect(date);
        collector.collect(date);

        verify(scheduleClient, times(1))
                .fetchSchedule(YearMonth.of(2025, 6));
    }
}
