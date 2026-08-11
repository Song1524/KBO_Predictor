package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineV2PredictionEngineTest {

    private BaselineV2ModelProperties properties;
    private BaselineV2PredictionEngine engine;

    @BeforeEach
    void setUp() {
        properties = new BaselineV2ModelProperties();
        BaselineV2ProbabilityModel model = new BaselineV2ProbabilityModel();
        engine = new BaselineV2PredictionEngine(
                properties,
                new BaselineV2PredictionCalculator(model)
        );
    }

    @Test
    void probabilitiesSumToExactlyOneHundred() {
        PredictionEngineResult result = engine.predict(features(
                team("0.650", "0.700", "0.650", "6.0", "3.0", "5.5", "3.2", "0.650"),
                team("0.400", "0.350", "0.400", "3.0", "5.5", "3.5", "5.0", "0.380")
        ));

        assertThat(result.homeWinProbability()
                .add(result.drawProbability())
                .add(result.awayWinProbability()))
                .isEqualByComparingTo("100.00");
        assertThat(result.modelVersion()).isEqualTo("baseline-v2");
    }

    @Test
    void similarStrengthHasHigherDrawProbabilityThanOneSidedStrength() {
        PredictionEngineResult similar = engine.predict(features(
                team("0.500", "0.500", "0.500", "4.0", "4.0", "4.0", "4.0", "0.500"),
                team("0.500", "0.500", "0.500", "4.0", "4.0", "4.0", "4.0", "0.500")
        ));
        PredictionEngineResult oneSided = engine.predict(features(
                team("0.700", "0.800", "0.750", "7.0", "2.0", "6.5", "2.5", "0.700"),
                team("0.300", "0.200", "0.250", "2.0", "7.0", "2.5", "6.5", "0.300")
        ));

        assertThat(similar.drawProbability())
                .isGreaterThan(oneSided.drawProbability());
    }

    @Test
    void nullFeaturesAreExcludedInsteadOfConvertedToZero() {
        TeamPredictionFeatures missing = new TeamPredictionFeatures(
                1L, "missing", false, null,
                null, null, null, null, null, null, null,
                null, null, null, null
        );
        TeamPredictionFeatures partialHome = new TeamPredictionFeatures(
                2L, "home", true, LocalDate.of(2026, 7, 9),
                new BigDecimal("0.650"), null, null, null, null, null, null,
                null, null, null, null
        );
        TeamPredictionFeatures partialAway = new TeamPredictionFeatures(
                3L, "away", true, LocalDate.of(2026, 7, 9),
                new BigDecimal("0.400"), null, null, null, null, null, null,
                null, null, null, null
        );

        PredictionEngineResult partial = engine.predict(features(
                partialHome, partialAway
        ));
        PredictionEngineResult none = engine.predict(features(missing, missing));

        assertThat(partial.featureCoverage()).isEqualByComparingTo("0.438");
        assertThat(partial.homeWinProbability())
                .isGreaterThan(partial.awayWinProbability());
        assertThat(none.featureCoverage()).isEqualByComparingTo("0.000");
        assertThat(none.homeWinProbability()
                .add(none.drawProbability())
                .add(none.awayWinProbability()))
                .isEqualByComparingTo("100.00");
    }

    private PredictionFeatures features(
            TeamPredictionFeatures home,
            TeamPredictionFeatures away
    ) {
        return new PredictionFeatures(
                1L,
                LocalDate.of(2026, 7, 16),
                LocalDateTime.of(2026, 7, 16, 18, 30),
                home,
                away
        );
    }

    private TeamPredictionFeatures team(
            String season,
            String recent5,
            String recent10,
            String runs5,
            String allowed5,
            String runs10,
            String allowed10,
            String venue
    ) {
        return new TeamPredictionFeatures(
                1L, "team", true, LocalDate.of(2026, 7, 15),
                decimal(season), decimal(recent5), decimal(recent10),
                decimal(runs5), decimal(allowed5),
                decimal(runs10), decimal(allowed10),
                null, null, decimal(venue), null
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
