package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalPredictionFeatureBuilderTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 6, 15);

    @Mock
    private GameRepository gameRepository;

    private Team lg;
    private Team hanwha;
    private HistoricalPredictionFeatureBuilder builder;

    @BeforeEach
    void setUp() {
        lg = team(1L, "LG", "LG 트윈스");
        hanwha = team(2L, "HH", "한화 이글스");
        builder = new HistoricalPredictionFeatureBuilder(gameRepository);
    }

    @Test
    void usesOnlyFinishedGamesBeforeTheTargetDateForRecentAndVenueForm() {
        Game target = game(
                100L, TARGET_DATE, lg, hanwha, 3, 2,
                GameStatus.FINISHED, GameResult.HOME_WIN
        );
        when(gameRepository.findById(100L)).thenReturn(Optional.of(target));
        List<Game> homeHistory = List.of(
                game(1L, TARGET_DATE.minusDays(1), lg, hanwha, 5, 1,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(2L, TARGET_DATE.minusDays(2), hanwha, lg, 2, 2,
                        GameStatus.FINISHED, GameResult.DRAW),
                game(3L, TARGET_DATE.minusDays(3), hanwha, lg, 3, 1,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(4L, TARGET_DATE.minusDays(4), lg, hanwha, 4, 2,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(5L, TARGET_DATE.minusDays(5), hanwha, lg, 1, 6,
                        GameStatus.FINISHED, GameResult.AWAY_WIN),
                game(6L, TARGET_DATE.minusDays(6), lg, hanwha, 1, 3,
                        GameStatus.FINISHED, GameResult.AWAY_WIN),
                game(7L, TARGET_DATE, lg, hanwha, 100, 0,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(8L, TARGET_DATE.plusDays(1), lg, hanwha, 100, 0,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(9L, TARGET_DATE.minusDays(1), lg, hanwha, 9, 0,
                        GameStatus.IN_PROGRESS, null)
        );
        when(gameRepository.findTeamSeasonGamesBefore(
                1L, 2026, GameStatus.FINISHED, TARGET_DATE
        )).thenReturn(homeHistory);
        when(gameRepository.findTeamSeasonGamesBefore(
                2L, 2026, GameStatus.FINISHED, TARGET_DATE
        )).thenReturn(List.of());

        HistoricalPredictionFeatures historical = builder.build(100L);

        assertThat(historical.homeHistoricalGameCount()).isEqualTo(6);
        assertThat(historical.homeSeasonWins()).isEqualTo(3);
        assertThat(historical.homeSeasonLosses()).isEqualTo(2);
        assertThat(historical.homeSeasonDraws()).isOne();
        assertThat(historical.features().home().seasonWinRate())
                .isEqualByComparingTo("0.600");
        assertThat(historical.features().home().recent5WinRate())
                .isEqualByComparingTo("0.750");
        assertThat(historical.features().home().recent5AvgRuns())
                .isEqualByComparingTo("3.60");
        assertThat(historical.features().home().recent5AvgRunsAllowed())
                .isEqualByComparingTo("1.80");
        assertThat(historical.features().home().venueWinRate())
                .isEqualByComparingTo("0.667");
        assertThat(historical.features().home().battingAverage()).isNull();
        assertThat(historical.features().home().era()).isNull();
        assertThat(historical.features().home().startingPitcher()).isNull();
        assertThat(historical.missingFeatures())
                .contains(
                        "HOME_BATTING_AVERAGE",
                        "HOME_TEAM_ERA",
                        "HOME_STARTING_PITCHER_ERA",
                        "HOME_STARTING_PITCHER_WHIP"
                );
        verify(gameRepository).findTeamSeasonGamesBefore(
                1L, 2026, GameStatus.FINISHED, TARGET_DATE
        );
    }

    @Test
    void noPriorGamesRemainExplicitlyMissingInsteadOfBecomingZero() {
        Game target = game(
                100L, TARGET_DATE, lg, hanwha, 3, 2,
                GameStatus.FINISHED, GameResult.HOME_WIN
        );
        when(gameRepository.findById(100L)).thenReturn(Optional.of(target));
        when(gameRepository.findTeamSeasonGamesBefore(
                1L, 2026, GameStatus.FINISHED, TARGET_DATE
        )).thenReturn(List.of());
        when(gameRepository.findTeamSeasonGamesBefore(
                2L, 2026, GameStatus.FINISHED, TARGET_DATE
        )).thenReturn(List.of());

        HistoricalPredictionFeatures historical = builder.build(100L);

        assertThat(historical.features().home().teamStatsAvailable()).isFalse();
        assertThat(historical.features().home().seasonWinRate()).isNull();
        assertThat(historical.features().home().recent10AvgRuns()).isNull();
        assertThat(historical.features().away().venueWinRate()).isNull();
        assertThat(historical.missingFeatures())
                .contains("HOME_SEASON_WIN_RATE", "AWAY_SEASON_WIN_RATE");
    }

    @Test
    void seasonBoundaryDoesNotMixPreviousSeasonGames() {
        LocalDate openingDay = LocalDate.of(2025, 3, 22);
        Game target = game(
                100L, openingDay, lg, hanwha, 3, 2,
                GameStatus.FINISHED, GameResult.HOME_WIN
        );
        ReflectionTestUtils.setField(target, "season", 2025);
        Game previousSeason = game(
                1L, LocalDate.of(2024, 10, 1), lg, hanwha, 5, 1,
                GameStatus.FINISHED, GameResult.HOME_WIN
        );
        ReflectionTestUtils.setField(previousSeason, "season", 2024);
        when(gameRepository.findById(100L)).thenReturn(Optional.of(target));
        when(gameRepository.findTeamSeasonGamesBefore(
                1L, 2025, GameStatus.FINISHED, openingDay
        )).thenReturn(List.of(previousSeason));
        when(gameRepository.findTeamSeasonGamesBefore(
                2L, 2025, GameStatus.FINISHED, openingDay
        )).thenReturn(List.of(previousSeason));

        HistoricalPredictionFeatures historical = builder.build(100L);

        assertThat(historical.homeHistoricalGameCount()).isZero();
        assertThat(historical.awayHistoricalGameCount()).isZero();
        assertThat(historical.features().home().seasonWinRate()).isNull();
        assertThat(historical.features().away().recent5WinRate()).isNull();
        verify(gameRepository).findTeamSeasonGamesBefore(
                1L, 2025, GameStatus.FINISHED, openingDay
        );
    }

    private Game game(
            Long id,
            LocalDate date,
            Team home,
            Team away,
            Integer homeScore,
            Integer awayScore,
            GameStatus status,
            GameResult result
    ) {
        Team winner = result == GameResult.HOME_WIN
                ? home
                : result == GameResult.AWAY_WIN ? away : null;
        Game game = Game.createCollected(
                "G" + id,
                2026,
                date,
                LocalTime.of(18, 30),
                home,
                away,
                "잠실",
                status,
                homeScore,
                awayScore,
                winner,
                result,
                null,
                LocalDateTime.of(date, LocalTime.of(22, 0))
        );
        ReflectionTestUtils.setField(game, "id", id);
        return game;
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
