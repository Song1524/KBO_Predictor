package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.StartingPitcherFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BaselinePredictionEngineTest {

    private BaselinePredictionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BaselinePredictionEngine(
                new BaselineV1ModelProperties()
        );
    }

    @Test
    void probabilitiesAlwaysSumToExactlyOneHundred() {
        PredictionEngineResult result = engine.predict(features(
                strongHome(),
                weakAway()
        ));

        assertThat(result.homeWinProbability()
                .add(result.drawProbability())
                .add(result.awayWinProbability()))
                .isEqualByComparingTo("100.00");
        assertThat(result.homeWinProbability().scale()).isEqualTo(2);
        assertThat(result.drawProbability().scale()).isEqualTo(2);
        assertThat(result.awayWinProbability().scale()).isEqualTo(2);
    }

    @Test
    void baselineV1RegressionFixtureRemainsUnchanged() {
        PredictionEngineResult result = engine.predict(features(
                strongHome(),
                weakAway()
        ));

        assertThat(result.modelVersion()).isEqualTo("baseline-v1");
        assertThat(result.homeWinProbability()).isEqualByComparingTo("81.43");
        assertThat(result.drawProbability()).isEqualByComparingTo("5.02");
        assertThat(result.awayWinProbability()).isEqualByComparingTo("13.55");
    }

    @Test
    void homeAdvantageFeaturesIncreaseHomeWinProbability() {
        PredictionEngineResult neutral = engine.predict(features(
                similarTeam(1L, "LG 트윈스"),
                similarTeam(2L, "한화 이글스")
        ));
        PredictionEngineResult homeStrong = engine.predict(features(
                strongHome(),
                weakAway()
        ));

        assertThat(homeStrong.predictedOutcome())
                .isEqualTo(PredictionOutcome.HOME_WIN);
        assertThat(homeStrong.homeWinProbability())
                .isGreaterThan(neutral.homeWinProbability());
    }

    @Test
    void awayAdvantageFeaturesIncreaseAwayWinProbability() {
        PredictionEngineResult neutral = engine.predict(features(
                similarTeam(1L, "LG 트윈스"),
                similarTeam(2L, "한화 이글스")
        ));
        PredictionEngineResult awayStrong = engine.predict(features(
                weakHome(),
                strongAway()
        ));

        assertThat(awayStrong.predictedOutcome())
                .isEqualTo(PredictionOutcome.AWAY_WIN);
        assertThat(awayStrong.awayWinProbability())
                .isGreaterThan(neutral.awayWinProbability());
    }

    @Test
    void similarStrengthRaisesDrawProbability() {
        PredictionEngineResult similar = engine.predict(features(
                similarTeam(1L, "LG 트윈스"),
                similarTeam(2L, "한화 이글스")
        ));
        PredictionEngineResult oneSided = engine.predict(features(
                strongHome(),
                weakAway()
        ));

        assertThat(similar.drawProbability())
                .isGreaterThan(oneSided.drawProbability());
    }

    @Test
    void partialFeaturesAreRenormalizedWithoutReplacingNullWithZero() {
        TeamPredictionFeatures home = missingTeam(1L, "LG 트윈스");
        TeamPredictionFeatures away = missingTeam(2L, "한화 이글스");
        home = withSeasonWinRate(home, new BigDecimal("0.650"));
        away = withSeasonWinRate(away, new BigDecimal("0.400"));

        PredictionEngineResult result = engine.predict(features(home, away));

        assertThat(result.featureCoverage()).isEqualByComparingTo("0.180");
        assertThat(result.homeWinProbability())
                .isGreaterThan(result.awayWinProbability());
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void completelyMissingFeaturesUseNeutralLowConfidenceFallback() {
        PredictionEngineResult result = engine.predict(features(
                missingTeam(1L, "LG 트윈스"),
                missingTeam(2L, "한화 이글스")
        ));

        assertThat(result.featureCoverage()).isEqualByComparingTo("0.000");
        assertThat(result.homeWinProbability()
                .add(result.drawProbability())
                .add(result.awayWinProbability()))
                .isEqualByComparingTo("100.00");
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("데이터가 일부 부족"));
    }

    @Test
    void reasonsContainOnlyTopInfluentialFactors() {
        PredictionEngineResult result = engine.predict(features(
                strongHome(),
                weakAway()
        ));

        assertThat(result.reasons()).hasSizeBetween(3, 5);
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("최근"));
        assertThat(result.reasons())
                .anyMatch(reason -> reason.contains("LG 트윈스"));
    }

    private PredictionFeatures features(
            TeamPredictionFeatures home,
            TeamPredictionFeatures away
    ) {
        return new PredictionFeatures(
                10L,
                LocalDate.of(2026, 8, 12),
                LocalDateTime.of(2026, 8, 12, 18, 30),
                home,
                away
        );
    }

    private TeamPredictionFeatures strongHome() {
        return team(
                1L, "LG 트윈스", "0.650", "0.750", "0.700",
                "6.00", "3.00", "5.50", "3.20",
                "0.290", "3.20", "0.680", "2.50", "1.10"
        );
    }

    private TeamPredictionFeatures weakAway() {
        return team(
                2L, "한화 이글스", "0.420", "0.350", "0.400",
                "3.00", "5.50", "3.50", "5.00",
                "0.250", "5.20", "0.380", "5.00", "1.55"
        );
    }

    private TeamPredictionFeatures weakHome() {
        return team(
                1L, "LG 트윈스", "0.400", "0.300", "0.350",
                "2.80", "5.80", "3.20", "5.30",
                "0.245", "5.50", "0.350", "5.30", "1.60"
        );
    }

    private TeamPredictionFeatures strongAway() {
        return team(
                2L, "한화 이글스", "0.680", "0.800", "0.720",
                "6.20", "2.80", "5.80", "3.00",
                "0.295", "3.00", "0.700", "2.30", "1.05"
        );
    }

    private TeamPredictionFeatures similarTeam(Long id, String name) {
        return team(
                id, name, "0.520", "0.500", "0.510",
                "4.50", "4.20", "4.40", "4.20",
                "0.270", "4.20", "0.520", "4.00", "1.35"
        );
    }

    private TeamPredictionFeatures team(
            Long id,
            String name,
            String season,
            String recent5,
            String recent10,
            String runs5,
            String allowed5,
            String runs10,
            String allowed10,
            String batting,
            String era,
            String venue,
            String pitcherEra,
            String pitcherWhip
    ) {
        return new TeamPredictionFeatures(
                id,
                name,
                true,
                LocalDate.of(2026, 8, 11),
                decimal(season),
                decimal(recent5),
                decimal(recent10),
                decimal(runs5),
                decimal(allowed5),
                decimal(runs10),
                decimal(allowed10),
                decimal(batting),
                decimal(era),
                decimal(venue),
                new StartingPitcherFeatures(
                        id + 100,
                        String.valueOf(id + 10000),
                        name + " 선발",
                        true,
                        true,
                        LocalDate.of(2026, 8, 11),
                        decimal(pitcherEra),
                        8,
                        4,
                        "100",
                        decimal(pitcherWhip)
                )
        );
    }

    private TeamPredictionFeatures missingTeam(Long id, String name) {
        return new TeamPredictionFeatures(
                id, name, false, null,
                null, null, null, null, null, null, null,
                null, null, null, null
        );
    }

    private TeamPredictionFeatures withSeasonWinRate(
            TeamPredictionFeatures source,
            BigDecimal seasonWinRate
    ) {
        return new TeamPredictionFeatures(
                source.teamId(),
                source.teamName(),
                true,
                LocalDate.of(2026, 8, 11),
                seasonWinRate,
                source.recent5WinRate(),
                source.recent10WinRate(),
                source.recent5AvgRuns(),
                source.recent5AvgRunsAllowed(),
                source.recent10AvgRuns(),
                source.recent10AvgRunsAllowed(),
                source.battingAverage(),
                source.era(),
                source.venueWinRate(),
                source.startingPitcher()
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
