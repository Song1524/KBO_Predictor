package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemPredictionHistoryRecorderTest {

    @Test
    void identicalShadowStageAndSnapshotIsStoredOnlyOnce() {
        SystemPredictionHistoryRepository repository =
                mock(SystemPredictionHistoryRepository.class);
        SystemPredictionHistoryRecorder recorder =
                new SystemPredictionHistoryRecorder(repository, clock());
        Game game = mock(Game.class);
        PredictionFeatureSnapshot snapshot = mock(PredictionFeatureSnapshot.class);
        when(game.getId()).thenReturn(10L);
        when(snapshot.getId()).thenReturn(99L);
        when(repository.findByDeduplicationKey(
                "SHADOW:10:logistic-v1:INITIAL:SNAPSHOT:99"
        )).thenReturn(Optional.empty(), Optional.of(mock(SystemPredictionHistory.class)));
        PredictionEngineResult result = new PredictionEngineResult(
                new BigDecimal("52.00"), new BigDecimal("3.00"),
                new BigDecimal("45.00"), PredictionOutcome.HOME_WIN,
                "logistic-v1", BigDecimal.ONE, List.of("reason")
        );
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 11, 17, 0);

        assertThat(recorder.recordShadow(
                game, snapshot, result, PredictionStage.INITIAL,
                "HASH", generatedAt
        )).isTrue();
        assertThat(recorder.recordShadow(
                game, snapshot, result, PredictionStage.INITIAL,
                "HASH", generatedAt
        )).isFalse();

        verify(repository, times(1)).saveAndFlush(any(SystemPredictionHistory.class));
    }

    private Clock clock() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), zone);
    }
}
