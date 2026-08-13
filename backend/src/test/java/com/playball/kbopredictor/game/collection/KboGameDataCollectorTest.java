package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
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

    @Mock
    private OfficialGameResultSource officialGameResultSource;

    @Mock
    private OfficialFinalScoreParser officialFinalScoreParser;

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
                scheduleParser,
                officialGameResultSource,
                officialFinalScoreParser
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
                scheduleParser,
                officialGameResultSource,
                officialFinalScoreParser
        );

        collector.collect(date);
        collector.collect(date);

        verify(scheduleClient, times(1))
                .fetchSchedule(YearMonth.of(2025, 6));
    }

    @Test
    void finishedScheduleScoreIsReplacedByConfirmedGameCenterScore() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        CollectedGame scheduleGame = game(
                GameStatus.FINISHED,
                0,
                0,
                null,
                false
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(1, List.of(scheduleGame), List.of())
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(
                        Map.of("20260812LGKT0", new OfficialFinalScore(
                                "20260812LGKT0",
                                "LG",
                                "KT",
                                2,
                                7,
                                GameResult.HOME_WIN
                        )),
                        List.of()
                ));
        KboGameDataCollector collector = collector();

        CollectedGame collected = collector.collect(date).games().getFirst();

        assertThat(collected.awayScore()).isEqualTo(2);
        assertThat(collected.homeScore()).isEqualTo(7);
        assertThat(collected.result()).isEqualTo(GameResult.HOME_WIN);
        assertThat(collected.finalScoreConfirmed()).isTrue();
    }

    @Test
    void unconfirmedFinishedScoreBecomesUnknownInsteadOfZeroZero() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(
                        1,
                        List.of(game(
                                GameStatus.FINISHED,
                                0,
                                0,
                                null,
                                false
                        )),
                        List.of()
                )
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(Map.of(), List.of()));

        GameCollectionBatch batch = collector().collect(date);

        assertThat(batch.games()).singleElement().satisfies(game -> {
            assertThat(game.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(game.awayScore()).isNull();
            assertThat(game.homeScore()).isNull();
            assertThat(game.result()).isNull();
            assertThat(game.finalScoreConfirmed()).isFalse();
        });
        assertThat(batch.errors()).singleElement().asString()
                .contains("final score is not confirmed");
    }

    @Test
    void officialScheduledStateCorrectsFalseFinishedScheduleMarker() {
        LocalDate date = LocalDate.of(2026, 8, 13);
        CollectedGame falseFinished = new CollectedGame(
                "20260813SSHT0",
                2026,
                date,
                LocalTime.of(19, 0),
                "SS",
                "HT",
                "광주",
                GameStatus.FINISHED,
                0,
                0,
                null,
                false,
                null
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(1, List.of(falseFinished), List.of())
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(
                        Map.of(),
                        Map.of("20260813SSHT0", new OfficialGameState(
                                "20260813SSHT0",
                                "SS",
                                "HT",
                                GameStatus.SCHEDULED,
                                null,
                                null
                        )),
                        List.of()
                ));

        GameCollectionBatch batch = collector().collect(date);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.games()).singleElement().satisfies(game -> {
            assertThat(game.status()).isEqualTo(GameStatus.SCHEDULED);
            assertThat(game.awayScore()).isNull();
            assertThat(game.homeScore()).isNull();
            assertThat(game.result()).isNull();
            assertThat(game.finalScoreConfirmed()).isFalse();
        });
    }

    @Test
    void scheduledScheduleStillUsesOfficialGameCenterState() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(
                        1,
                        List.of(game(
                                GameStatus.SCHEDULED,
                                null,
                                null,
                                null,
                                false
                        )),
                        List.of()
                )
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(
                        Map.of(),
                        Map.of("20260812LGKT0", new OfficialGameState(
                                "20260812LGKT0",
                                "LG",
                                "KT",
                                GameStatus.SCHEDULED,
                                null,
                                null
                        )),
                        List.of()
                ));

        GameCollectionBatch batch = collector().collect(date);

        assertThat(batch.games()).singleElement().satisfies(game -> {
            assertThat(game.status()).isEqualTo(GameStatus.SCHEDULED);
            assertThat(game.awayScore()).isNull();
            assertThat(game.homeScore()).isNull();
        });
        verify(officialGameResultSource).fetchGameList(date);
        verify(officialFinalScoreParser).parse("game-center-json", date);
    }

    @Test
    void officialLiveStateAndScoreOverrideStaleScheduledSchedule() {
        LocalDate date = LocalDate.of(2026, 8, 13);
        CollectedGame staleSchedule = new CollectedGame(
                "20260813KTNC0",
                2026,
                date,
                LocalTime.of(19, 0),
                "KT",
                "NC",
                "창원",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                false,
                null
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(1, List.of(staleSchedule), List.of())
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(
                        Map.of(),
                        Map.of("20260813KTNC0", new OfficialGameState(
                                "20260813KTNC0",
                                "KT",
                                "NC",
                                GameStatus.IN_PROGRESS,
                                3,
                                0
                        )),
                        List.of()
                ));

        GameCollectionBatch batch = collector().collect(date);

        assertThat(batch.games()).singleElement().satisfies(game -> {
            assertThat(game.status()).isEqualTo(GameStatus.IN_PROGRESS);
            assertThat(game.awayScore()).isEqualTo(3);
            assertThat(game.homeScore()).isZero();
            assertThat(game.result()).isNull();
            assertThat(game.finalScoreConfirmed()).isFalse();
        });
    }

    @Test
    void officialLiveZeroZeroRemainsInProgressWithRealZeroScores() {
        LocalDate date = LocalDate.of(2026, 8, 13);
        CollectedGame staleSchedule = new CollectedGame(
                "20260813SSHT0",
                2026,
                date,
                LocalTime.of(19, 0),
                "SS",
                "HT",
                "광주",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                false,
                null
        );
        when(scheduleClient.fetchSchedule(YearMonth.of(2026, 8)))
                .thenReturn("schedule-json");
        when(scheduleParser.parse("schedule-json", date)).thenReturn(
                new GameCollectionBatch(1, List.of(staleSchedule), List.of())
        );
        when(officialGameResultSource.fetchGameList(date))
                .thenReturn("game-center-json");
        when(officialFinalScoreParser.parse("game-center-json", date))
                .thenReturn(new OfficialFinalScoreBatch(
                        Map.of(),
                        Map.of("20260813SSHT0", new OfficialGameState(
                                "20260813SSHT0",
                                "SS",
                                "HT",
                                GameStatus.IN_PROGRESS,
                                0,
                                0
                        )),
                        List.of()
                ));

        GameCollectionBatch batch = collector().collect(date);

        assertThat(batch.games()).singleElement().satisfies(game -> {
            assertThat(game.status()).isEqualTo(GameStatus.IN_PROGRESS);
            assertThat(game.awayScore()).isZero();
            assertThat(game.homeScore()).isZero();
            assertThat(game.result()).isNull();
        });
    }

    private KboGameDataCollector collector() {
        return new KboGameDataCollector(
                scheduleClient,
                scheduleParser,
                officialGameResultSource,
                officialFinalScoreParser
        );
    }

    private CollectedGame game(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            GameResult result,
            boolean finalScoreConfirmed
    ) {
        return new CollectedGame(
                "20260812LGKT0",
                2026,
                LocalDate.of(2026, 8, 12),
                LocalTime.of(19, 0),
                "LG",
                "KT",
                "수원",
                status,
                awayScore,
                homeScore,
                result,
                finalScoreConfirmed,
                null
        );
    }
}
