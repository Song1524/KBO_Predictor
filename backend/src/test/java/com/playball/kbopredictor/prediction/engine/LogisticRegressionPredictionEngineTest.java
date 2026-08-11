package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LogisticRegressionPredictionEngineTest {

    private LogisticModelArtifact artifact;
    private LogisticRegressionPredictionEngine engine;

    @BeforeEach
    void setUp() {
        LogisticModelArtifactLoader loader = new LogisticModelArtifactLoader(
                new ObjectMapper(),
                new FileSystemResource("ml/artifacts/logistic-v1.json"),
                "7B2663ACD7465FFB45FB05C245DDF35C46B8D74B1F06C75B6C0CFF383C22EADB"
        );
        artifact = loader.artifact();
        engine = new LogisticRegressionPredictionEngine(loader);
    }

    @Test
    void javaSoftmaxMatchesEveryPythonArtifactFixture() {
        assertThat(artifact.classes())
                .containsExactly("AWAY_WIN", "DRAW", "HOME_WIN");

        for (LogisticModelArtifact.VerificationSample sample
                : artifact.verificationSamples()) {
            LogisticRawPrediction actual = engine.predictRaw(
                    features(sample.features())
            );
            for (String className : artifact.classes()) {
                PredictionOutcome outcome = PredictionOutcome.valueOf(className);
                assertThat(actual.probabilities().get(outcome))
                        .as("gameId=%s, class=%s", sample.gameId(), className)
                        .isCloseTo(
                                sample.probabilities().get(className),
                                within(1.0e-12)
                        );
            }
        }
    }

    @Test
    void missingValuesUseTrainingMedianAndProbabilityStillSumsToOne() {
        PredictionFeatures features = new PredictionFeatures(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDateTime.of(2026, 4, 1, 18, 30),
                emptyTeam(1L, "홈"),
                emptyTeam(2L, "원정")
        );

        LogisticRawPrediction raw = engine.predictRaw(features);
        PredictionEngineResult response = engine.predict(features);

        assertThat(raw.availableFeatureCount()).isZero();
        assertThat(raw.probabilities().values().stream()
                .mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, within(1.0e-12));
        assertThat(response.homeWinProbability()
                .add(response.drawProbability())
                .add(response.awayWinProbability()))
                .isEqualByComparingTo("100.00");
        assertThat(response.modelVersion()).isEqualTo("logistic-v1");
        assertThat(response.featureCoverage()).isEqualByComparingTo("0.000");
    }

    private PredictionFeatures features(Map<String, Double> values) {
        TeamPredictionFeatures home = teamFromDifferences(
                1L, "홈", values, true
        );
        TeamPredictionFeatures away = teamFromDifferences(
                2L, "원정", values, false
        );
        return new PredictionFeatures(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDateTime.of(2026, 4, 1, 18, 30),
                home,
                away
        );
    }

    private TeamPredictionFeatures teamFromDifferences(
            Long id,
            String name,
            Map<String, Double> values,
            boolean home
    ) {
        return new TeamPredictionFeatures(
                id,
                name,
                true,
                LocalDate.of(2026, 3, 31),
                side(values.get(LogisticFeatureValues.SEASON_WIN_RATE_DIFF), home),
                side(values.get(LogisticFeatureValues.RECENT_5_WIN_RATE_DIFF), home),
                side(values.get(LogisticFeatureValues.RECENT_10_WIN_RATE_DIFF), home),
                side(values.get(LogisticFeatureValues.RECENT_5_RUN_DIFF), home),
                BigDecimal.ZERO,
                side(values.get(LogisticFeatureValues.RECENT_10_RUN_DIFF), home),
                BigDecimal.ZERO,
                null,
                null,
                side(values.get(LogisticFeatureValues.HOME_AWAY_WIN_RATE_DIFF), home),
                null
        );
    }

    private BigDecimal side(Double difference, boolean home) {
        if (difference == null) {
            return null;
        }
        double value = home
                ? Math.max(difference, 0.0)
                : Math.max(-difference, 0.0);
        return BigDecimal.valueOf(value);
    }

    private TeamPredictionFeatures emptyTeam(Long id, String name) {
        return new TeamPredictionFeatures(
                id, name, false, null,
                null, null, null, null, null, null, null,
                null, null, null, null
        );
    }
}
