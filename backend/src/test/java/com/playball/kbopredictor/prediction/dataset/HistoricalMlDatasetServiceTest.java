package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalMlDatasetServiceTest {

    @Mock
    private PredictionFeatureSnapshotRepository snapshotRepository;

    @Test
    void exportsFeatureDifferencesAndKeepsDrawAsTrainingOnlyTarget() {
        LocalDate date = LocalDate.of(2024, 6, 15);
        PredictionFeatureSnapshot snapshot = snapshot(
                10L, 2024, date, GameResult.DRAW, true
        );
        when(snapshotRepository.findEvaluationSnapshots(date, date))
                .thenReturn(List.of(snapshot));
        HistoricalMlDatasetService service = new HistoricalMlDatasetService(
                snapshotRepository,
                new HistoricalDatasetMapper()
        );

        HistoricalMlDatasetResponse result = service.load(date, date);

        assertThat(result.exportedCount()).isOne();
        HistoricalMlDatasetRow row = result.rows().getFirst();
        assertThat(row.season()).isEqualTo(2024);
        assertThat(row.actualResult()).isEqualTo(PredictionOutcome.DRAW);
        assertThat(row.seasonWinRateDiff()).isEqualByComparingTo("0.200");
        assertThat(row.recent5RunDiff()).isEqualByComparingTo("2.00");
        assertThat(row.availableFeatureCount()).isEqualTo(6);
        assertThat(row.featureCoverage()).isEqualByComparingTo("100.00");
        assertThat(service.toCsv(date, date))
                .contains("actualResult")
                .contains(",DRAW\n");
    }

    @Test
    void openingGameWithoutPriorFeaturesIsReportedButNotExported() {
        LocalDate date = LocalDate.of(2025, 3, 22);
        PredictionFeatureSnapshot snapshot = snapshot(
                11L, 2025, date, GameResult.HOME_WIN, false
        );
        when(snapshotRepository.findEvaluationSnapshots(date, date))
                .thenReturn(List.of(snapshot));
        HistoricalMlDatasetService service = new HistoricalMlDatasetService(
                snapshotRepository,
                new HistoricalDatasetMapper()
        );

        HistoricalMlDatasetResponse result = service.load(date, date);

        assertThat(result.snapshotCount()).isOne();
        assertThat(result.exportedCount()).isZero();
        assertThat(result.excludedForNoFeaturesCount()).isOne();
    }

    private PredictionFeatureSnapshot snapshot(
            Long id,
            int season,
            LocalDate date,
            GameResult result,
            boolean withFeatures
    ) {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Game game = Game.createCollected(
                "G" + id,
                season,
                date,
                LocalTime.of(18, 30),
                home,
                away,
                "잠실",
                GameStatus.FINISHED,
                result == GameResult.AWAY_WIN ? 2 : 4,
                result == GameResult.HOME_WIN ? 2 : 4,
                result == GameResult.HOME_WIN
                        ? home
                        : result == GameResult.AWAY_WIN ? away : null,
                result,
                null,
                LocalDateTime.of(date, LocalTime.of(22, 0))
        );
        ReflectionTestUtils.setField(game, "id", id);
        PredictionFeatures features = new PredictionFeatures(
                id,
                date,
                LocalDateTime.of(date, LocalTime.of(18, 30)),
                features(home, withFeatures, true),
                features(away, withFeatures, false)
        );
        HistoricalPredictionFeatures historical =
                new HistoricalPredictionFeatures(
                        features,
                        withFeatures ? 20 : 0,
                        withFeatures ? 20 : 0,
                        0, 0, 0, 0, 0, 0,
                        PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES,
                        "TEST",
                        List.of()
                );
        return PredictionFeatureSnapshot.create(
                game,
                historical,
                LocalDateTime.of(date, LocalTime.of(22, 1))
        );
    }

    private TeamPredictionFeatures features(
            Team team,
            boolean available,
            boolean home
    ) {
        if (!available) {
            return new TeamPredictionFeatures(
                    team.getId(), team.getName(), false, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null
            );
        }
        return new TeamPredictionFeatures(
                team.getId(),
                team.getName(),
                true,
                LocalDate.of(2024, 6, 14),
                new BigDecimal(home ? "0.600" : "0.400"),
                new BigDecimal(home ? "0.650" : "0.450"),
                new BigDecimal(home ? "0.620" : "0.420"),
                new BigDecimal(home ? "5.00" : "4.00"),
                new BigDecimal(home ? "3.00" : "4.00"),
                new BigDecimal(home ? "4.80" : "4.10"),
                new BigDecimal(home ? "3.20" : "4.00"),
                null,
                null,
                new BigDecimal(home ? "0.610" : "0.410"),
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
