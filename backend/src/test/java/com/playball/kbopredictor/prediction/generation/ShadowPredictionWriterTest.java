package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.prediction.history.PredictionStage;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRecorder;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ShadowPredictionWriterTest {

    @Test
    void neverWritesAtOrAfterPredictionClose() {
        GameRepository games = mock(GameRepository.class);
        PredictionFeatureSnapshotRepository snapshots =
                mock(PredictionFeatureSnapshotRepository.class);
        SystemPredictionHistoryRecorder recorder =
                mock(SystemPredictionHistoryRecorder.class);
        Game game = game();
        when(games.findByIdForUpdate(10L)).thenReturn(Optional.of(game));
        Clock clock = fixed(game.getPredictionCloseAt());
        ShadowPredictionWriter writer = new ShadowPredictionWriter(
                games, snapshots, recorder, clock
        );

        boolean stored = writer.write(
                10L, 99L, PredictionStage.INITIAL,
                result(), "HASH", game.getPredictionCloseAt().minusHours(1)
        );

        assertThat(stored).isFalse();
        verifyNoInteractions(snapshots, recorder);
    }

    private PredictionEngineResult result() {
        return new PredictionEngineResult(
                new BigDecimal("52.00"), new BigDecimal("3.00"),
                new BigDecimal("45.00"), PredictionOutcome.HOME_WIN,
                "logistic-v1", BigDecimal.ONE, List.of()
        );
    }

    private Game game() {
        Team home = team(1L, "LG", "LG");
        Team away = team(2L, "HH", "Hanwha");
        Game game = Game.createCollected(
                "20260812HHLG0", 2026, LocalDate.of(2026, 8, 12),
                LocalTime.of(18, 30), home, away, "Jamsil",
                GameStatus.SCHEDULED, null, null, null, null, null,
                LocalDateTime.of(2026, 8, 11, 12, 0)
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

    private Clock fixed(LocalDateTime value) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return Clock.fixed(value.atZone(zone).toInstant(), zone);
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
