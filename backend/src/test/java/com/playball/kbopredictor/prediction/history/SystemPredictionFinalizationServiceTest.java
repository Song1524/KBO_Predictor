package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemPredictionFinalizationServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private SystemPredictionRepository predictionRepository;

    @Mock
    private SystemPredictionHistoryRepository historyRepository;

    @Mock
    private SystemPredictionHistoryRecorder historyRecorder;

    @Test
    void finalHistoryIsCreatedOnceAndNeverReplaced() {
        Game game = game();
        SystemPrediction prediction = prediction(game, "58.00", "33.00");
        SystemPredictionFinalizationService service =
                new SystemPredictionFinalizationService(
                        gameRepository,
                        predictionRepository,
                        historyRepository,
                        historyRecorder,
                        clock()
                );
        when(gameRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(game));
        when(historyRepository.existsByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
                10L,
                "baseline-v1",
                PredictionSource.OPERATIONAL,
                PredictionStage.FINAL
        )).thenReturn(false, true);
        when(predictionRepository.findByGameId(10L))
                .thenReturn(Optional.of(prediction));
        when(historyRecorder.recordOperational(
                prediction,
                null,
                PredictionStage.FINAL
        )).thenReturn(true);

        assertThat(service.finalizeClosedGame(10L)).isTrue();

        prediction.update(
                game.getAwayTeam(),
                PredictionOutcome.AWAY_WIN,
                new BigDecimal("30.00"),
                new BigDecimal("8.00"),
                new BigDecimal("62.00"),
                "baseline-v1",
                new BigDecimal("0.650"),
                null, null, null, null,
                "later value",
                LocalDateTime.of(2026, 6, 15, 19, 1)
        );

        assertThat(service.finalizeClosedGame(10L)).isFalse();
        verify(historyRecorder, times(1)).recordOperational(
                any(SystemPrediction.class),
                isNull(),
                eq(PredictionStage.FINAL)
        );
    }

    @Test
    void shadowFinalCopiesLatestShadowHistoryOnlyOnce() {
        Game game = game();
        SystemPrediction prediction = prediction(game, "58.00", "33.00");
        SystemPredictionHistory shadow = mock(SystemPredictionHistory.class);
        SystemPredictionFinalizationService service =
                new SystemPredictionFinalizationService(
                        gameRepository, predictionRepository, historyRepository,
                        historyRecorder, clock()
                );
        when(gameRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(game));
        when(predictionRepository.findByGameId(10L)).thenReturn(Optional.of(prediction));
        when(historyRepository.existsByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
                10L, "baseline-v1", PredictionSource.OPERATIONAL, PredictionStage.FINAL
        )).thenReturn(true);
        when(historyRepository.existsByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
                10L, "logistic-v1", PredictionSource.SHADOW, PredictionStage.FINAL
        )).thenReturn(false, true);
        when(historyRepository.findTopByGameIdAndModelVersionAndPredictionSourceOrderByGeneratedAtDescIdDesc(
                10L, "logistic-v1", PredictionSource.SHADOW
        )).thenReturn(Optional.of(shadow));
        when(historyRecorder.finalizeHistory(shadow)).thenReturn(true);

        assertThat(service.finalizeClosedGame(10L)).isTrue();
        assertThat(service.finalizeClosedGame(10L)).isFalse();
        verify(historyRecorder, times(1)).finalizeHistory(shadow);
    }

    private SystemPrediction prediction(
            Game game,
            String home,
            String away
    ) {
        LocalDateTime generatedAt = LocalDateTime.of(2026, 6, 15, 17, 0);
        SystemPrediction prediction = SystemPrediction.create(game, generatedAt);
        prediction.update(
                game.getHomeTeam(),
                PredictionOutcome.HOME_WIN,
                new BigDecimal(home),
                new BigDecimal("9.00"),
                new BigDecimal(away),
                "baseline-v1",
                new BigDecimal("0.650"),
                null, null, null, null,
                "initial",
                generatedAt
        );
        return prediction;
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
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 6, 14, 12, 0)
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
                ZonedDateTime.of(
                        2026, 6, 15, 19, 0, 0, 0, zone
                ).toInstant(),
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
