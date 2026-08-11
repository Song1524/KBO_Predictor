package com.playball.kbopredictor.prediction.backfill;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import com.playball.kbopredictor.prediction.history.*;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BacktestPredictionWriterTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private PredictionFeatureSnapshotRepository snapshotRepository;

    @Mock
    private SystemPredictionHistoryRepository historyRepository;

    @Mock
    private PredictionEngine predictionEngine;

    @Test
    void rerunningTheSameBackfillDoesNotDuplicateSnapshotOrFinalHistory() {
        Game game = game();
        HistoricalPredictionFeatures historical = historical(game);
        PredictionEngineResult prediction = new PredictionEngineResult(
                new BigDecimal("58.00"),
                new BigDecimal("9.00"),
                new BigDecimal("33.00"),
                PredictionOutcome.HOME_WIN,
                "baseline-v1",
                new BigDecimal("0.650"),
                List.of("홈팀 우세")
        );
        BacktestPredictionWriter writer = new BacktestPredictionWriter(
                gameRepository,
                snapshotRepository,
                historyRepository,
                predictionEngine,
                clock()
        );
        when(gameRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(game));
        when(snapshotRepository.findByGameIdAndFeatureAsOfAndGenerationMethod(
                10L,
                LocalDateTime.of(2026, 6, 15, 18, 29, 59),
                PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES
        )).thenReturn(Optional.empty());
        when(predictionEngine.predict(any(PredictionFeatures.class)))
                .thenReturn(prediction);
        when(historyRepository.findByDeduplicationKey(
                "BACKTEST:10:baseline-v1:FINAL"
        )).thenReturn(Optional.empty());

        BacktestWriteResult first = writer.write(historical);

        ArgumentCaptor<PredictionFeatureSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(PredictionFeatureSnapshot.class);
        verify(snapshotRepository).saveAndFlush(snapshotCaptor.capture());
        PredictionFeatureSnapshot storedSnapshot = snapshotCaptor.getValue();
        when(snapshotRepository.findByGameIdAndFeatureAsOfAndGenerationMethod(
                10L,
                LocalDateTime.of(2026, 6, 15, 18, 29, 59),
                PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES
        )).thenReturn(Optional.of(storedSnapshot));
        when(historyRepository.findByDeduplicationKey(
                "BACKTEST:10:baseline-v1:FINAL"
        )).thenReturn(Optional.of(mock(SystemPredictionHistory.class)));

        BacktestWriteResult second = writer.write(historical);

        assertThat(first.snapshotCreated()).isTrue();
        assertThat(first.historyCreated()).isTrue();
        assertThat(second.snapshotCreated()).isFalse();
        assertThat(second.historyCreated()).isFalse();
        verify(snapshotRepository, times(1)).saveAndFlush(any());
        verify(historyRepository, times(1)).saveAndFlush(any());
    }

    private HistoricalPredictionFeatures historical(Game game) {
        LocalDate date = game.getGameDate();
        PredictionFeatures features = new PredictionFeatures(
                game.getId(),
                date,
                LocalDateTime.of(date, game.getGameTime()),
                teamFeatures(game.getHomeTeam(), "0.600", "0.650"),
                teamFeatures(game.getAwayTeam(), "0.450", "0.400")
        );
        return new HistoricalPredictionFeatures(
                features,
                50,
                49,
                30,
                20,
                0,
                22,
                27,
                0,
                PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES,
                "KBO_OFFICIAL_SCHEDULE_RESULTS_STORED_IN_GAMES",
                List.of(
                        "HOME_BATTING_AVERAGE",
                        "AWAY_BATTING_AVERAGE"
                )
        );
    }

    private TeamPredictionFeatures teamFeatures(
            Team team,
            String seasonRate,
            String recentRate
    ) {
        return new TeamPredictionFeatures(
                team.getId(),
                team.getName(),
                true,
                LocalDate.of(2026, 6, 14),
                new BigDecimal(seasonRate),
                new BigDecimal(recentRate),
                new BigDecimal(recentRate),
                new BigDecimal("5.00"),
                new BigDecimal("4.00"),
                new BigDecimal("4.80"),
                new BigDecimal("4.10"),
                null,
                null,
                new BigDecimal(seasonRate),
                null
        );
    }

    private Game game() {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Game game = Game.createCollected(
                "20260615HHLG0",
                2026,
                LocalDate.of(2026, 6, 15),
                LocalTime.of(18, 30),
                home,
                away,
                "잠실",
                GameStatus.FINISHED,
                5,
                2,
                home,
                GameResult.HOME_WIN,
                null,
                LocalDateTime.of(2026, 6, 15, 22, 0)
        );
        ReflectionTestUtils.setField(game, "id", 10L);
        return game;
    }

    private Team team(Long id, String code, String name) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "kboTeamCode", code);
        ReflectionTestUtils.setField(team, "name", name);
        return team;
    }

    private Clock clock() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return Clock.fixed(
                Instant.parse("2026-08-10T03:00:00Z"),
                zone
        );
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
