package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameUpsertServiceTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);
    private static final LocalTime GAME_TIME = LocalTime.of(18, 30);
    private static final LocalDateTime NOW = LocalDateTime.of(
            2026,
            8,
            10,
            12,
            0
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private TeamRepository teamRepository;

    private Team homeTeam;
    private Team awayTeam;
    private GameUpsertService service;

    @BeforeEach
    void setUp() {
        homeTeam = team(1L, "OB", "두산 베어스");
        awayTeam = team(2L, "LG", "LG 트윈스");
        when(teamRepository.findByKboTeamCode("OB"))
                .thenReturn(Optional.of(homeTeam));
        when(teamRepository.findByKboTeamCode("LG"))
                .thenReturn(Optional.of(awayTeam));

        Clock clock = Clock.fixed(
                NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );
        service = new GameUpsertService(
                gameRepository,
                teamRepository,
                clock
        );
    }

    @Test
    void firstCollectionInsertsAndSecondCollectionUpdatesSameGame() {
        CollectedGame scheduled = collected(
                GameStatus.SCHEDULED,
                null,
                null,
                null
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.empty());
        when(gameRepository
                .findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
                        GAME_DATE,
                        GAME_TIME,
                        1L,
                        2L
                ))
                .thenReturn(Optional.empty());

        GameUpsertResult insertedResult = service.upsert(scheduled);
        assertThat(insertedResult.action())
                .isEqualTo(GameUpsertAction.INSERTED);
        assertThat(insertedResult.currentStatus())
                .isEqualTo(GameStatus.SCHEDULED);

        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).saveAndFlush(captor.capture());
        Game inserted = captor.getValue();
        assertThat(inserted.getExternalGameId()).isEqualTo("20260812LGOB0");
        assertThat(inserted.getPredictionCloseAt())
                .isEqualTo(LocalDateTime.of(GAME_DATE, GAME_TIME).minusMinutes(30));

        LocalDateTime preservedCloseAt = GAME_DATE.atTime(17, 45);
        ReflectionTestUtils.setField(
                inserted,
                "predictionCloseAt",
                preservedCloseAt
        );

        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(inserted));

        GameUpsertResult updatedResult = service.upsert(scheduled);
        assertThat(updatedResult.action())
                .isEqualTo(GameUpsertAction.UPDATED);
        assertThat(updatedResult.statusChanged()).isFalse();
        assertThat(inserted.getPredictionCloseAt()).isEqualTo(preservedCloseAt);
        verify(gameRepository, times(2)).saveAndFlush(inserted);
    }

    @Test
    void changedTimeUpdatesLegacyGameWithoutExternalId() {
        Game existing = Game.createCollected(
                null,
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        LocalTime changedTime = LocalTime.of(19, 0);
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.empty());
        when(gameRepository
                .findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
                        GAME_DATE,
                        changedTime,
                        1L,
                        2L
                ))
                .thenReturn(Optional.empty());
        when(gameRepository
                .findByGameDateAndHomeTeamIdAndAwayTeamIdOrderByGameTimeAsc(
                        GAME_DATE,
                        1L,
                        2L
                ))
                .thenReturn(List.of(existing));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                changedTime,
                "LG",
                "OB",
                "대전",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                false,
                null
        ));

        assertThat(result.action()).isEqualTo(GameUpsertAction.UPDATED);
        assertThat(existing.getExternalGameId()).isEqualTo("20260812LGOB0");
        assertThat(existing.getGameTime()).isEqualTo(changedTime);
        assertThat(existing.getStadium()).isEqualTo("대전");
        assertThat(existing.getPredictionCloseAt())
                .isEqualTo(GAME_DATE.atTime(changedTime).minusMinutes(30));
        verify(gameRepository).saveAndFlush(existing);
    }

    @Test
    void secondDoubleheaderGameIsInsertedInsteadOfOverwritingFirstGame() {
        Game firstGame = Game.createCollected(
                "20260812LGOB1",
                2026,
                GAME_DATE,
                LocalTime.of(14, 0),
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        LocalTime secondGameTime = LocalTime.of(17, 0);
        when(gameRepository.findByExternalGameId("20260812LGOB2"))
                .thenReturn(Optional.empty());
        when(gameRepository
                .findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
                        GAME_DATE,
                        secondGameTime,
                        1L,
                        2L
                ))
                .thenReturn(Optional.empty());
        when(gameRepository
                .findByGameDateAndHomeTeamIdAndAwayTeamIdOrderByGameTimeAsc(
                        GAME_DATE,
                        1L,
                        2L
                ))
                .thenReturn(List.of(firstGame));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB2",
                2026,
                GAME_DATE,
                secondGameTime,
                "LG",
                "OB",
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                false,
                null
        ));

        assertThat(result.action()).isEqualTo(GameUpsertAction.INSERTED);
        assertThat(firstGame.getExternalGameId()).isEqualTo("20260812LGOB1");
        assertThat(firstGame.getGameTime()).isEqualTo(LocalTime.of(14, 0));
        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isNotSameAs(firstGame);
        assertThat(captor.getValue().getExternalGameId())
                .isEqualTo("20260812LGOB2");
    }

    @Test
    void missingTimeAndStadiumDoNotEraseKnownScheduleFields() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                null,
                "LG",
                "OB",
                null,
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                false,
                null
        ));

        assertThat(existing.getGameTime()).isEqualTo(GAME_TIME);
        assertThat(existing.getStadium()).isEqualTo("잠실");
        assertThat(existing.getPredictionCloseAt())
                .isEqualTo(GAME_DATE.atTime(GAME_TIME).minusMinutes(30));
    }

    @Test
    void incompleteScheduledResponseDoesNotRegressFinishedGame() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.FINISHED,
                5,
                2,
                homeTeam,
                GameResult.HOME_WIN,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsert(collected(
                GameStatus.SCHEDULED,
                null,
                null,
                null
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINISHED -> SCHEDULED");

        assertThat(existing.getStatus()).isEqualTo(GameStatus.FINISHED);
        assertThat(existing.getHomeScore()).isEqualTo(5);
        assertThat(existing.getAwayScore()).isEqualTo(2);
        assertThat(existing.getResult()).isEqualTo(GameResult.HOME_WIN);
    }

    @Test
    void falseFinishedWithoutResultCanReturnToScheduled() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.FINISHED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(collected(
                GameStatus.SCHEDULED,
                null,
                null,
                null
        ));

        assertThat(result.statusChanged()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(existing.getHomeScore()).isNull();
        assertThat(existing.getAwayScore()).isNull();
        assertThat(existing.getResult()).isNull();
    }

    @Test
    void falseFinishedWithoutResultCanAdvanceToInProgress() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.FINISHED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.IN_PROGRESS,
                0,
                1,
                null,
                false,
                null
        ));

        assertThat(result.statusChanged()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(existing.getAwayScore()).isZero();
        assertThat(existing.getHomeScore()).isEqualTo(1);
        assertThat(existing.getResult()).isNull();
    }

    @Test
    void scheduledGameChangesToInProgress() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(collected(
                GameStatus.IN_PROGRESS,
                1,
                3,
                null
        ));

        assertThat(result.statusChanged()).isTrue();
        assertThat(result.reachedTerminalStatus()).isFalse();
        assertThat(existing.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(existing.getAwayScore()).isEqualTo(1);
        assertThat(existing.getHomeScore()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("finishedResults")
    void inProgressGameChangesToFinishedWithScoresAndWinner(
            int awayScore,
            int homeScore,
            GameResult result,
            String expectedWinnerCode
    ) {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.IN_PROGRESS,
                0,
                1,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult upsertResult = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.FINISHED,
                awayScore,
                homeScore,
                result,
                true,
                null
        ));

        assertThat(upsertResult.action()).isEqualTo(GameUpsertAction.UPDATED);
        assertThat(upsertResult.statusChanged()).isTrue();
        assertThat(upsertResult.reachedFinished()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(GameStatus.FINISHED);
        assertThat(existing.getAwayScore()).isEqualTo(awayScore);
        assertThat(existing.getHomeScore()).isEqualTo(homeScore);
        assertThat(existing.getResult()).isEqualTo(result);
        if (expectedWinnerCode == null) {
            assertThat(existing.getWinnerTeam()).isNull();
        } else {
            assertThat(existing.getWinnerTeam().getKboTeamCode())
                    .isEqualTo(expectedWinnerCode);
        }
    }

    @Test
    void unconfirmedFinishedResultIsPendingUntilConfirmedScoreArrives() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.IN_PROGRESS,
                0,
                0,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult pending = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.FINISHED,
                null,
                null,
                null,
                false,
                null
        ));

        assertThat(pending.reachedFinished()).isTrue();
        assertThat(pending.finalScoreConfirmed()).isFalse();
        assertThat(existing.getHomeScore()).isNull();
        assertThat(existing.getAwayScore()).isNull();
        assertThat(existing.getResult()).isNull();

        GameUpsertResult confirmed = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.FINISHED,
                2,
                7,
                GameResult.HOME_WIN,
                true,
                null
        ));

        assertThat(confirmed.finalScoreConfirmed()).isTrue();
        assertThat(existing.getAwayScore()).isEqualTo(2);
        assertThat(existing.getHomeScore()).isEqualTo(7);
        assertThat(existing.getResult()).isEqualTo(GameResult.HOME_WIN);
        assertThat(existing.getWinnerTeam()).isSameAs(homeTeam);
    }

    @Test
    void temporaryUnconfirmedResponseDoesNotEraseKnownFinalResult() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.FINISHED,
                5,
                2,
                homeTeam,
                GameResult.HOME_WIN,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.FINISHED,
                null,
                null,
                null,
                false,
                null
        ));

        assertThat(result.terminalDataChanged()).isFalse();
        assertThat(existing.getHomeScore()).isEqualTo(5);
        assertThat(existing.getAwayScore()).isEqualTo(2);
        assertThat(existing.getResult()).isEqualTo(GameResult.HOME_WIN);
    }

    @Test
    void cancelledGameIsSeparateFromDrawAndClearsResult() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.CANCELLED,
                null,
                null,
                null,
                false,
                "우천취소"
        ));

        assertThat(existing.getStatus()).isEqualTo(GameStatus.CANCELLED);
        assertThat(existing.getResult()).isNull();
        assertThat(existing.getWinnerTeam()).isNull();
        assertThat(existing.getCancelReason()).isEqualTo("우천취소");
        assertThat(result.statusChanged()).isTrue();
        assertThat(result.reachedCancelled()).isTrue();
    }

    @Test
    void changedResultOfPreviouslyFinishedGameIsMarkedAsCorrection() {
        Game existing = Game.createCollected(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                homeTeam,
                awayTeam,
                "잠실",
                GameStatus.FINISHED,
                5,
                2,
                homeTeam,
                GameResult.HOME_WIN,
                null,
                NOW.minusDays(1)
        );
        when(gameRepository.findByExternalGameId("20260812LGOB0"))
                .thenReturn(Optional.of(existing));

        GameUpsertResult result = service.upsert(new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                GameStatus.FINISHED,
                7,
                3,
                GameResult.AWAY_WIN,
                true,
                null
        ));

        assertThat(result.terminalDataChanged()).isTrue();
        assertThat(result.previousResult()).isEqualTo(GameResult.HOME_WIN);
        assertThat(result.currentResult()).isEqualTo(GameResult.AWAY_WIN);
        assertThat(result.statusChanged()).isFalse();
    }

    private static Stream<Arguments> finishedResults() {
        return Stream.of(
                Arguments.of(2, 5, GameResult.HOME_WIN, "OB"),
                Arguments.of(7, 3, GameResult.AWAY_WIN, "LG"),
                Arguments.of(2, 2, GameResult.DRAW, null)
        );
    }

    private CollectedGame collected(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            GameResult result
    ) {
        return new CollectedGame(
                "20260812LGOB0",
                2026,
                GAME_DATE,
                GAME_TIME,
                "LG",
                "OB",
                "잠실",
                status,
                awayScore,
                homeScore,
                result,
                status == GameStatus.FINISHED,
                null
        );
    }

    private Team team(Long id, String code, String name) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "kboTeamCode", code);
        ReflectionTestUtils.setField(team, "name", name);
        return team;
    }

    private <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
