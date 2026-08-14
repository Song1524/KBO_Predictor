package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.game.entity.*;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.*;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
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

class ShadowEvaluationServiceTest {

    private static final String HASH =
            "7B2663ACD7465FFB45FB05C245DDF35C46B8D74B1F06C75B6C0CFF383C22EADB";

    @Test
    void comparesOnlyPairedFinalPredictionsAndTracksDrawProbabilities() {
        SystemPredictionHistoryRepository repository =
                mock(SystemPredictionHistoryRepository.class);
        LogisticModelArtifactLoader loader = mock(LogisticModelArtifactLoader.class);
        when(loader.artifactSha256()).thenReturn(HASH);
        LocalDate from = LocalDate.of(2026, 8, 11);
        LocalDate to = LocalDate.of(2026, 8, 13);

        Game home = game(1L, from, GameResult.HOME_WIN);
        Game away = game(2L, from.plusDays(1), GameResult.AWAY_WIN);
        Game draw = game(3L, from.plusDays(2), GameResult.DRAW);
        Game reconstructed = game(4L, from.plusDays(2), GameResult.HOME_WIN);
        PredictionFeatureSnapshot first = snapshot(101L, home);
        PredictionFeatureSnapshot second = snapshot(102L, away);
        PredictionFeatureSnapshot third = snapshot(103L, draw);
        PredictionFeatureSnapshot reconstructedSnapshot = snapshot(
                104L, reconstructed
        );
        ReflectionTestUtils.setField(
                reconstructedSnapshot,
                "generationMethod",
                PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES
        );

        List<SystemPredictionHistory> baseline = List.of(
                history(home, first, PredictionSource.OPERATIONAL,
                        "baseline-v1", PredictionOutcome.HOME_WIN, "60", "5", "35", null),
                history(away, second, PredictionSource.OPERATIONAL,
                        "baseline-v1", PredictionOutcome.HOME_WIN, "55", "6", "39", null),
                history(draw, third, PredictionSource.OPERATIONAL,
                        "baseline-v1", PredictionOutcome.HOME_WIN, "51", "8", "41", null),
                history(reconstructed, reconstructedSnapshot, PredictionSource.OPERATIONAL,
                        "baseline-v1", PredictionOutcome.HOME_WIN, "60", "5", "35", null)
        );
        List<SystemPredictionHistory> logistic = List.of(
                history(home, first, PredictionSource.SHADOW,
                        "logistic-v1", PredictionOutcome.AWAY_WIN, "49", "3", "48", HASH),
                history(away, second, PredictionSource.SHADOW,
                        "logistic-v1", PredictionOutcome.AWAY_WIN, "40", "2", "58", HASH),
                history(draw, third, PredictionSource.SHADOW,
                        "logistic-v1", PredictionOutcome.HOME_WIN, "52", "4", "44", HASH),
                history(reconstructed, reconstructedSnapshot, PredictionSource.SHADOW,
                        "logistic-v1", PredictionOutcome.HOME_WIN, "55", "2", "43", HASH)
        );
        when(repository.findForEvaluation(
                "baseline-v1", PredictionSource.OPERATIONAL,
                PredictionStage.FINAL, from, to
        )).thenReturn(baseline);
        when(repository.findForEvaluation(
                "logistic-v1", PredictionSource.SHADOW,
                PredictionStage.FINAL, from, to
        )).thenReturn(logistic);

        ShadowEvaluationResponse response = new ShadowEvaluationService(
                repository, new ShadowMetricCalculator(), loader
        ).evaluate(from, to);

        assertThat(response.commonEvaluatedGameCount()).isEqualTo(3);
        assertThat(response.baselineEligibleFinalGameCount()).isEqualTo(4);
        assertThat(response.logisticEligibleFinalGameCount()).isEqualTo(4);
        assertThat(response.featureSnapshotMismatchCount()).isZero();
        assertThat(response.nonOperationalSnapshotCount()).isOne();
        assertThat(response.pregameCutoffViolationCount()).isZero();
        assertThat(response.artifactMismatchCount()).isZero();
        assertThat(response.baselineCorrectLogisticWrongCount()).isEqualTo(1);
        assertThat(response.logisticCorrectBaselineWrongCount()).isEqualTo(1);
        assertThat(response.bothWrongCount()).isEqualTo(1);
        assertThat(response.predictedOutcomeAgreementRate())
                .isEqualByComparingTo("0.333333");
        assertThat(response.baseline().accuracy()).isEqualByComparingTo("0.333333");
        assertThat(response.logistic().accuracy()).isEqualByComparingTo("0.333333");
        assertThat(response.baselineDrawProbabilities().actualDrawCount()).isOne();
        assertThat(response.baselineDrawProbabilities().averageOnActualDraw())
                .isEqualByComparingTo("0.080000");
        assertThat(response.logisticDrawProbabilities().averageOnActualDraw())
                .isEqualByComparingTo("0.040000");
        assertThat(response.logistic().confusionMatrix().get("AWAY_WIN")
                .get("AWAY_WIN")).isEqualTo(1);
        assertThat(response.actualOutcomeRates().get("DRAW"))
                .isEqualByComparingTo("0.333333");
        assertThat(response.baseline().averageProbabilities().get("HOME_WIN"))
                .isEqualByComparingTo("0.553333");
        assertThat(response.logistic().calibration().get("DRAW").bins())
                .hasSize(10);
        assertThat(response.pairedMetrics().get("accuracy")
                .logisticMinusBaseline()).isEqualByComparingTo("0.000000");
        assertThat(response.pairedMetrics().get("logLoss")
                .bootstrap95Lower()).isNull();
        assertThat(response.sampleSizeAssessment().bootstrapEligible()).isFalse();
        assertThat(response.sampleSizeAssessment().additionalCommonGamesNeeded())
                .isEqualTo(197);
        assertThat(response.sampleSizeAssessment().additionalDrawsNeeded())
                .isEqualTo(9);
    }

    private Game game(Long id, LocalDate date, GameResult result) {
        Team home = team(1L, "LG", "LG");
        Team away = team(2L, "HH", "Hanwha");
        Game game = Game.createCollected(
                "G" + id, 2026, date, LocalTime.of(18, 30), home, away,
                "stadium", GameStatus.FINISHED, 5, 3,
                result == GameResult.HOME_WIN ? home
                        : result == GameResult.AWAY_WIN ? away : null,
                result, null, LocalDateTime.of(date, LocalTime.NOON)
        );
        ReflectionTestUtils.setField(game, "id", id);
        return game;
    }

    private PredictionFeatureSnapshot snapshot(Long id, Game game) {
        PredictionFeatureSnapshot snapshot = instantiate(PredictionFeatureSnapshot.class);
        ReflectionTestUtils.setField(snapshot, "id", id);
        ReflectionTestUtils.setField(snapshot, "game", game);
        ReflectionTestUtils.setField(snapshot, "featureAsOf",
                LocalDateTime.of(game.getGameDate(), LocalTime.of(17, 0)));
        ReflectionTestUtils.setField(snapshot, "generationMethod",
                PredictionGenerationMethod.OPERATIONAL_PREGAME);
        return snapshot;
    }

    private SystemPredictionHistory history(
            Game game,
            PredictionFeatureSnapshot snapshot,
            PredictionSource source,
            String model,
            PredictionOutcome outcome,
            String home,
            String draw,
            String away,
            String hash
    ) {
        SystemPredictionHistory history = instantiate(SystemPredictionHistory.class);
        ReflectionTestUtils.setField(history, "game", game);
        ReflectionTestUtils.setField(history, "featureSnapshot", snapshot);
        ReflectionTestUtils.setField(history, "predictionSource", source);
        ReflectionTestUtils.setField(history, "predictionStage", PredictionStage.FINAL);
        ReflectionTestUtils.setField(history, "modelVersion", model);
        ReflectionTestUtils.setField(history, "modelArtifactHash", hash);
        ReflectionTestUtils.setField(history, "predictedOutcome", outcome);
        ReflectionTestUtils.setField(history, "homeWinProbability", new BigDecimal(home));
        ReflectionTestUtils.setField(history, "drawProbability", new BigDecimal(draw));
        ReflectionTestUtils.setField(history, "awayWinProbability", new BigDecimal(away));
        ReflectionTestUtils.setField(history, "featureCoverage", BigDecimal.ONE);
        ReflectionTestUtils.setField(
                history,
                "generatedAt",
                LocalDateTime.of(game.getGameDate(), LocalTime.of(17, 0))
        );
        return history;
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
