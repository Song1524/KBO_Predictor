package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamRecentFormCalculatorTest {

    @Mock
    private GameRepository gameRepository;

    @Test
    void calculatesRecentFiveAndTenWithHomeAwayDrawAndNoFutureLeakage() {
        Team lg = team(1L, "LG");
        Team ob = team(2L, "OB");
        LocalDate cutoff = LocalDate.of(2026, 8, 12);
        List<Game> repositoryRows = List.of(
                game(cutoff.plusDays(1), lg, ob, 99, 0,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(cutoff.minusDays(1), lg, ob, 10, 0,
                        GameStatus.IN_PROGRESS, null),
                game(cutoff.minusDays(1), lg, ob, 99, 0,
                        GameStatus.FINISHED, null),
                game(cutoff.minusDays(1), lg, ob, 5, 2,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(cutoff.minusDays(2), ob, lg, 4, 1,
                        GameStatus.FINISHED, GameResult.HOME_WIN),
                game(cutoff.minusDays(3), lg, ob, 3, 3,
                        GameStatus.FINISHED, GameResult.DRAW),
                game(cutoff.minusDays(4), ob, lg, 2, 6,
                        GameStatus.FINISHED, GameResult.AWAY_WIN),
                game(cutoff.minusDays(5), lg, ob, 7, 0,
                        GameStatus.FINISHED, GameResult.HOME_WIN)
        );
        when(gameRepository.findTeamGamesBefore(
                eq(1L),
                eq(GameStatus.FINISHED),
                eq(cutoff),
                any(Pageable.class)
        )).thenReturn(repositoryRows);

        TeamRecentForm form = new TeamRecentFormCalculator(gameRepository)
                .calculate(1L, cutoff);

        assertThat(form.recent10Wins()).isEqualTo(3);
        assertThat(form.recent10Losses()).isEqualTo(1);
        assertThat(form.recent10Draws()).isEqualTo(1);
        assertThat(form.values().recent5WinRate()).isEqualByComparingTo("0.750");
        assertThat(form.values().recent10WinRate()).isEqualByComparingTo("0.750");
        assertThat(form.values().recent5AvgRuns()).isEqualByComparingTo("4.40");
        assertThat(form.values().recent5AvgRunsAllowed()).isEqualByComparingTo("2.20");
        assertThat(form.values().recent10AvgRuns()).isEqualByComparingTo("4.40");
        assertThat(form.values().recent10AvgRunsAllowed()).isEqualByComparingTo("2.20");
    }

    @Test
    void allDrawsHaveNoWinRateButKeepRunAverages() {
        Team lg = team(1L, "LG");
        Team ob = team(2L, "OB");
        LocalDate cutoff = LocalDate.of(2026, 8, 12);
        when(gameRepository.findTeamGamesBefore(
                eq(1L), eq(GameStatus.FINISHED), eq(cutoff), any(Pageable.class)
        )).thenReturn(List.of(game(
                cutoff.minusDays(1),
                lg,
                ob,
                2,
                2,
                GameStatus.FINISHED,
                GameResult.DRAW
        )));

        TeamRecentForm form = new TeamRecentFormCalculator(gameRepository)
                .calculate(1L, cutoff);

        assertThat(form.recent10Draws()).isOne();
        assertThat(form.values().recent5WinRate()).isNull();
        assertThat(form.values().recent5AvgRuns()).isEqualByComparingTo("2.00");
    }

    private Game game(
            LocalDate date,
            Team home,
            Team away,
            int homeScore,
            int awayScore,
            GameStatus status,
            GameResult result
    ) {
        Team winner = result == GameResult.HOME_WIN
                ? home
                : result == GameResult.AWAY_WIN ? away : null;
        return Game.createCollected(
                date.toString() + home.getKboTeamCode() + away.getKboTeamCode(),
                date.getYear(),
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
                LocalDateTime.of(date.minusDays(1), LocalTime.NOON)
        );
    }

    private Team team(Long id, String code) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "kboTeamCode", code);
        ReflectionTestUtils.setField(team, "name", code);
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
