package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatureService;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPredictionGenerationServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private PredictionFeatureService featureService;

    @Mock
    private PredictionEngine predictionEngine;

    @Mock
    private SystemPredictionWriter writer;

    @Mock
    private ShadowPredictionService shadowPredictionService;

    private Clock clock;
    private Game game;
    private SystemPredictionGenerationService service;

    @BeforeEach
    void setUp() {
        clock = fixed(LocalDateTime.of(2026, 8, 12, 12, 0));
        game = game(10L, LocalDate.of(2026, 8, 12), LocalTime.of(18, 30));
        service = new SystemPredictionGenerationService(
                gameRepository,
                featureService,
                predictionEngine,
                writer,
                shadowPredictionService,
                clock
        );
    }

    @Test
    void recalculatesSameGameWhenPitcherFeaturesArriveBeforeClose() {
        PredictionFeatures withoutPitcher = features(false);
        PredictionFeatures withPitcher = features(true);
        PredictionEngineResult first = result("50.00", "11.00", "39.00");
        PredictionEngineResult second = result("57.00", "9.00", "34.00");
        when(gameRepository.findById(10L)).thenReturn(Optional.of(game));
        when(featureService.build(10L))
                .thenReturn(withoutPitcher)
                .thenReturn(withPitcher);
        when(predictionEngine.predict(withoutPitcher)).thenReturn(first);
        when(predictionEngine.predict(withPitcher)).thenReturn(second);
        SystemPredictionWriteResult firstWrite = writeResult(
                SystemPredictionGenerationStatus.CREATED, first, 101L
        );
        SystemPredictionWriteResult secondWrite = writeResult(
                SystemPredictionGenerationStatus.UPDATED, second, 102L
        );
        when(writer.write(withoutPitcher, first)).thenReturn(firstWrite);
        when(writer.write(withPitcher, second)).thenReturn(secondWrite);

        assertThat(service.generate(10L).status())
                .isEqualTo(SystemPredictionGenerationStatus.CREATED);
        assertThat(service.generate(10L).status())
                .isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        verify(writer, times(2)).write(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(shadowPredictionService).generate(withoutPitcher, firstWrite);
        verify(shadowPredictionService).generate(withPitcher, secondWrite);
    }

    @Test
    void closedGameSkipsFeatureBuildAndEngineEntirely() {
        service = new SystemPredictionGenerationService(
                gameRepository,
                featureService,
                predictionEngine,
                writer,
                shadowPredictionService,
                fixed(game.getPredictionCloseAt())
        );
        when(gameRepository.findById(10L)).thenReturn(Optional.of(game));

        SystemPredictionGenerationResponse response = service.generate(10L);

        assertThat(response.status())
                .isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
        verify(featureService, never()).build(10L);
        verify(predictionEngine, never())
                .predict(org.mockito.ArgumentMatchers.any());
        verify(writer, never()).write(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private PredictionFeatures features(boolean pitcherAvailable) {
        TeamPredictionFeatures home = teamFeatures(
                1L, "LG 트윈스", pitcherAvailable
        );
        TeamPredictionFeatures away = teamFeatures(
                2L, "한화 이글스", pitcherAvailable
        );
        return new PredictionFeatures(
                10L,
                game.getGameDate(),
                LocalDateTime.of(game.getGameDate(), game.getGameTime()),
                home,
                away
        );
    }

    private TeamPredictionFeatures teamFeatures(
            Long id,
            String name,
            boolean pitcherAvailable
    ) {
        return new TeamPredictionFeatures(
                id, name, true, LocalDate.of(2026, 8, 11),
                new BigDecimal("0.550"),
                new BigDecimal("0.600"),
                new BigDecimal("0.550"),
                new BigDecimal("5.00"),
                new BigDecimal("4.00"),
                new BigDecimal("4.80"),
                new BigDecimal("4.20"),
                new BigDecimal("0.275"),
                new BigDecimal("4.00"),
                new BigDecimal("0.550"),
                pitcherAvailable
                        ? new com.playball.kbopredictor.prediction.feature.StartingPitcherFeatures(
                        id + 100,
                        "P" + id,
                        name + " 선발",
                        true,
                        true,
                        LocalDate.of(2026, 8, 11),
                        new BigDecimal("3.20"),
                        8,
                        4,
                        "100",
                        new BigDecimal("1.20")
                )
                        : null
        );
    }

    private PredictionEngineResult result(
            String home,
            String draw,
            String away
    ) {
        return new PredictionEngineResult(
                new BigDecimal(home),
                new BigDecimal(draw),
                new BigDecimal(away),
                PredictionOutcome.HOME_WIN,
                "baseline-v1",
                new BigDecimal("0.850"),
                List.of("근거")
        );
    }

    private SystemPredictionGenerationResponse response(
            SystemPredictionGenerationStatus status,
            PredictionEngineResult result
    ) {
        return new SystemPredictionGenerationResponse(
                10L,
                status,
                result.predictedOutcome(),
                result.homeWinProbability(),
                result.drawProbability(),
                result.awayWinProbability(),
                result.modelVersion(),
                result.featureCoverage(),
                LocalDateTime.now(clock),
                "ok"
        );
    }

    private SystemPredictionWriteResult writeResult(
            SystemPredictionGenerationStatus status,
            PredictionEngineResult result,
            Long snapshotId
    ) {
        return new SystemPredictionWriteResult(
                response(status, result),
                snapshotId,
                status == SystemPredictionGenerationStatus.CREATED
                        ? com.playball.kbopredictor.prediction.history.PredictionStage.INITIAL
                        : com.playball.kbopredictor.prediction.history.PredictionStage.STARTER_UPDATED
        );
    }

    private Game game(Long id, LocalDate date, LocalTime time) {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Game game = Game.createCollected(
                "20260812HHLG0", 2026, date, time,
                home, away, "잠실", GameStatus.SCHEDULED,
                null, null, null, null, null,
                LocalDateTime.of(date.minusDays(1), LocalTime.NOON)
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

    private Clock fixed(LocalDateTime time) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Instant instant = time.atZone(zone).toInstant();
        return Clock.fixed(instant, zone);
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
