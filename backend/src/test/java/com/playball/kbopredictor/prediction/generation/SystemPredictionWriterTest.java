package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.StartingPitcherFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRecorder;
import com.playball.kbopredictor.prediction.history.PredictionStage;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPredictionWriterTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private SystemPredictionRepository systemPredictionRepository;

    @Mock
    private SystemPredictionHistoryRecorder historyRecorder;

    @Mock
    private PredictionFeatureSnapshotRepository snapshotRepository;

    @Test
    void createsThenUpdatesTheSinglePredictionBeforeClose() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 12, 0);
        Game game = game(10L, LocalDate.of(2026, 8, 12), LocalTime.of(18, 30));
        PredictionFeatures features = features(game);
        SystemPredictionWriter writer = new SystemPredictionWriter(
                gameRepository,
                systemPredictionRepository,
                snapshotRepository,
                historyRecorder,
                clock(now)
        );
        when(gameRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(game));
        when(systemPredictionRepository.findByGameId(10L))
                .thenReturn(Optional.empty());
        mockSnapshotPersistence();

        SystemPredictionGenerationResponse created = writer.upsert(
                features,
                result(PredictionOutcome.HOME_WIN, "58.00", "8.00", "34.00")
        );

        assertThat(created.status())
                .isEqualTo(SystemPredictionGenerationStatus.CREATED);
        ArgumentCaptor<SystemPrediction> captor =
                ArgumentCaptor.forClass(SystemPrediction.class);
        verify(systemPredictionRepository).saveAndFlush(captor.capture());
        SystemPrediction prediction = captor.getValue();
        assertThat(prediction.getPredictedOutcome())
                .isEqualTo(PredictionOutcome.HOME_WIN);
        assertThat(prediction.getPredictedWinnerTeam())
                .isSameAs(game.getHomeTeam());
        assertThat(prediction.getHomeStatDate())
                .isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(prediction.getHomePitcherStatDate())
                .isEqualTo(LocalDate.of(2026, 8, 11));

        when(systemPredictionRepository.findByGameId(10L))
                .thenReturn(Optional.of(prediction));
        SystemPredictionGenerationResponse updated = writer.upsert(
                features,
                result(PredictionOutcome.AWAY_WIN, "35.00", "9.00", "56.00")
        );

        assertThat(updated.status())
                .isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertThat(prediction.getPredictedOutcome())
                .isEqualTo(PredictionOutcome.AWAY_WIN);
        assertThat(prediction.getPredictedWinnerTeam())
                .isSameAs(game.getAwayTeam());
        verify(historyRecorder).recordOperational(
                org.mockito.ArgumentMatchers.eq(prediction),
                org.mockito.ArgumentMatchers.any(PredictionFeatureSnapshot.class),
                org.mockito.ArgumentMatchers.eq(PredictionStage.INITIAL)
        );
        verify(historyRecorder).recordOperational(
                org.mockito.ArgumentMatchers.eq(prediction),
                org.mockito.ArgumentMatchers.any(PredictionFeatureSnapshot.class),
                org.mockito.ArgumentMatchers.eq(PredictionStage.STARTER_UPDATED)
        );
    }

    @Test
    void predictionCannotChangeAtOrAfterClose() {
        Game game = game(10L, LocalDate.of(2026, 8, 12), LocalTime.of(18, 30));
        LocalDateTime closeAt = game.getPredictionCloseAt();
        SystemPredictionWriter writer = new SystemPredictionWriter(
                gameRepository,
                systemPredictionRepository,
                snapshotRepository,
                historyRecorder,
                clock(closeAt)
        );
        when(gameRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(game));

        SystemPredictionGenerationResponse response = writer.upsert(
                features(game),
                result(PredictionOutcome.HOME_WIN, "58.00", "8.00", "34.00")
        );

        assertThat(response.status())
                .isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
        verify(systemPredictionRepository, never()).findByGameId(10L);
        verify(systemPredictionRepository, never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    private PredictionEngineResult result(
            PredictionOutcome outcome,
            String home,
            String draw,
            String away
    ) {
        return new PredictionEngineResult(
                new BigDecimal(home),
                new BigDecimal(draw),
                new BigDecimal(away),
                outcome,
                "baseline-v1",
                new BigDecimal("0.850"),
                List.of("LG 트윈스가 최근 10경기 승률에서 우세합니다.")
        );
    }

    private PredictionFeatures features(Game game) {
        return new PredictionFeatures(
                game.getId(),
                game.getGameDate(),
                LocalDateTime.of(game.getGameDate(), game.getGameTime()),
                teamFeatures(game.getHomeTeam()),
                teamFeatures(game.getAwayTeam())
        );
    }

    private TeamPredictionFeatures teamFeatures(Team team) {
        LocalDate statDate = LocalDate.of(2026, 8, 11);
        return new TeamPredictionFeatures(
                team.getId(), team.getName(), true, statDate,
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
                new StartingPitcherFeatures(
                        team.getId() + 100,
                        "100" + team.getId(),
                        team.getName() + " 선발",
                        true,
                        true,
                        statDate,
                        new BigDecimal("3.50"),
                        8,
                        4,
                        "100",
                        new BigDecimal("1.25")
                )
        );
    }

    private Game game(Long id, LocalDate date, LocalTime time) {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Game game = Game.createCollected(
                "20260812HHLG0",
                2026,
                date,
                time,
                home,
                away,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
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

    private Clock clock(LocalDateTime time) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Instant instant = time.atZone(zone).toInstant();
        return Clock.fixed(instant, zone);
    }

    private void mockSnapshotPersistence() {
        when(snapshotRepository.findByGameIdAndFeatureAsOfAndGenerationMethod(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.empty());
        when(snapshotRepository.saveAndFlush(
                org.mockito.ArgumentMatchers.any(PredictionFeatureSnapshot.class)
        )).thenAnswer(invocation -> {
            PredictionFeatureSnapshot snapshot = invocation.getArgument(0);
            ReflectionTestUtils.setField(snapshot, "id", 100L);
            return snapshot;
        });
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
