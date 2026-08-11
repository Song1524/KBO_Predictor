package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.*;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PredictionEvaluationServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 1);

    @Mock
    private GameRepository gameRepository;

    @Mock
    private SystemPredictionHistoryRepository historyRepository;

    @Test
    void evaluatesBacktestHistoryByModelAndComparesSimpleBaselines() {
        Game homeWin = finishedGame(1L, GameResult.HOME_WIN);
        Game draw = finishedGame(2L, GameResult.DRAW);
        Game awayWin = finishedGame(3L, GameResult.AWAY_WIN);
        Game zeroCoverage = finishedGame(4L, GameResult.HOME_WIN);
        List<Game> games = List.of(homeWin, draw, awayWin, zeroCoverage);
        when(gameRepository.findByStatusAndGameDateBetweenWithTeams(
                GameStatus.FINISHED, FROM, TO
        )).thenReturn(games);

        SystemPredictionHistory homeHistory = history(
                homeWin, PredictionOutcome.HOME_WIN,
                "60.00", "8.00", "32.00", "0.650",
                "0.600", "0.400", true
        );
        SystemPredictionHistory drawHistory = history(
                draw, PredictionOutcome.HOME_WIN,
                "46.00", "12.00", "42.00", "0.650",
                "0.500", "0.500", false
        );
        SystemPredictionHistory awayHistory = history(
                awayWin, PredictionOutcome.AWAY_WIN,
                "30.00", "8.00", "62.00", "0.650",
                "0.300", "0.600", false
        );
        SystemPredictionHistory unavailable = history(
                zeroCoverage, PredictionOutcome.HOME_WIN,
                "47.00", "11.00", "42.00", "0.000",
                null, null, false
        );
        when(historyRepository.findForEvaluation(
                "baseline-v1",
                PredictionSource.BACKTEST,
                PredictionStage.FINAL,
                FROM,
                TO
        )).thenReturn(List.of(
                homeHistory, drawHistory, awayHistory, unavailable
        ));

        PredictionEvaluationResponse response =
                new PredictionEvaluationService(
                        gameRepository,
                        historyRepository
                ).evaluate(FROM, TO, "baseline-v1");

        assertThat(response.modelVersion()).isEqualTo("baseline-v1");
        assertThat(response.finishedGameCount()).isEqualTo(4);
        assertThat(response.featureGeneratedGameCount()).isEqualTo(4);
        assertThat(response.evaluableGameCount()).isEqualTo(3);
        assertThat(response.skippedGameCount()).isOne();
        assertThat(response.dataCoverage()).isEqualByComparingTo("75.00");
        assertThat(response.averageFeatureCoverage())
                .isEqualByComparingTo("65.00");
        assertThat(response.starterDataGameCount()).isOne();
        assertThat(response.teamOnlyGameCount()).isEqualTo(2);
        assertThat(response.correctCount()).isEqualTo(2);
        assertThat(response.overallAccuracy()).isEqualByComparingTo("66.67");
        assertThat(response.homeWinAccuracy()).isEqualByComparingTo("100.00");
        assertThat(response.drawAccuracy()).isEqualByComparingTo("0.00");
        assertThat(response.awayWinAccuracy()).isEqualByComparingTo("100.00");
        assertThat(response.logLoss()).isNotNull().isPositive();
        assertThat(response.brierScore()).isNotNull().isPositive();
        assertThat(response.benchmarks())
                .extracting(BenchmarkEvaluationResponse::name)
                .containsExactly(
                        "baseline-v1",
                        "always-home-win",
                        "higher-season-win-rate"
                );
        assertThat(response.benchmarks().get(1).accuracy())
                .isEqualByComparingTo("33.33");
        assertThat(response.benchmarks().get(2).accuracy())
                .isEqualByComparingTo("66.67");
    }

    @Test
    void noEvaluableSamplesExposeCoverageAndNullMetrics() {
        Game game = finishedGame(1L, GameResult.HOME_WIN);
        when(gameRepository.findByStatusAndGameDateBetweenWithTeams(
                GameStatus.FINISHED, FROM, TO
        )).thenReturn(List.of(game));
        SystemPredictionHistory unavailable = history(
                game,
                PredictionOutcome.HOME_WIN,
                "47.00", "11.00", "42.00", "0.000",
                null, null, false
        );
        when(historyRepository.findForEvaluation(
                "baseline-v1",
                PredictionSource.BACKTEST,
                PredictionStage.FINAL,
                FROM,
                TO
        )).thenReturn(List.of(unavailable));

        PredictionEvaluationResponse response =
                new PredictionEvaluationService(
                        gameRepository,
                        historyRepository
                ).evaluate(FROM, TO, "baseline-v1");

        assertThat(response.featureGeneratedGameCount()).isOne();
        assertThat(response.evaluableGameCount()).isZero();
        assertThat(response.dataCoverage()).isEqualByComparingTo("0.00");
        assertThat(response.averageFeatureCoverage()).isNull();
        assertThat(response.overallAccuracy()).isNull();
        assertThat(response.logLoss()).isNull();
        assertThat(response.brierScore()).isNull();
    }

    private SystemPredictionHistory history(
            Game game,
            PredictionOutcome outcome,
            String home,
            String draw,
            String away,
            String coverage,
            String homeSeasonRate,
            String awaySeasonRate,
            boolean starterData
    ) {
        SystemPredictionHistory history = mock(SystemPredictionHistory.class);
        PredictionFeatureSnapshot snapshot = mock(
                PredictionFeatureSnapshot.class
        );
        when(history.getGame()).thenReturn(game);
        when(history.getFeatureSnapshot()).thenReturn(snapshot);
        BigDecimal featureCoverage = new BigDecimal(coverage);
        when(history.getFeatureCoverage()).thenReturn(featureCoverage);
        if (featureCoverage.signum() > 0) {
            when(history.getPredictedOutcome()).thenReturn(outcome);
            when(history.getHomeWinProbability()).thenReturn(new BigDecimal(home));
            when(history.getDrawProbability()).thenReturn(new BigDecimal(draw));
            when(history.getAwayWinProbability()).thenReturn(new BigDecimal(away));
            when(snapshot.getHomeSeasonWinRate()).thenReturn(decimal(homeSeasonRate));
            when(snapshot.getAwaySeasonWinRate()).thenReturn(decimal(awaySeasonRate));
            when(snapshot.hasStartingPitcherData()).thenReturn(starterData);
        }
        return history;
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private Game finishedGame(Long id, GameResult result) {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Team winner = result == GameResult.HOME_WIN
                ? home
                : result == GameResult.AWAY_WIN ? away : null;
        Game game = Game.createCollected(
                "G" + id,
                2026,
                LocalDate.of(2026, 6, 15),
                LocalTime.of(18, 30),
                home,
                away,
                "잠실",
                GameStatus.FINISHED,
                result == GameResult.AWAY_WIN ? 2 : 5,
                result == GameResult.HOME_WIN ? 2 : 5,
                winner,
                result,
                null,
                LocalDateTime.of(2026, 6, 15, 22, 0)
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
