package com.playball.kbopredictor.admin.service;

import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.ActivePredictionModelProperties;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifact;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.history.PredictionSource;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRepository;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminDashboardServiceTest {

    @Test
    void aggregatesTodayAndExposesReadOnlyModelIdentity() {
        GameRepository games = mock(GameRepository.class);
        SystemPredictionRepository systems = mock(SystemPredictionRepository.class);
        SystemPredictionHistoryRepository histories =
                mock(SystemPredictionHistoryRepository.class);
        UserPredictionRepository users = mock(UserPredictionRepository.class);
        ActivePredictionModelProperties properties =
                new ActivePredictionModelProperties();
        properties.setActiveModel("baseline-v1");
        LogisticModelArtifactLoader loader = mock(LogisticModelArtifactLoader.class);
        LogisticModelArtifact artifact = mock(LogisticModelArtifact.class);
        LocalDate today = LocalDate.of(2026, 8, 11);

        List<GameRepository.StatusCount> counts = List.of(
                status(GameStatus.SCHEDULED, 2),
                status(GameStatus.IN_PROGRESS, 1),
                status(GameStatus.FINISHED, 3),
                status(GameStatus.CANCELLED, 1)
        );
        when(games.countStatusesByGameDate(today)).thenReturn(counts);
        when(systems.countByGameGameDate(today)).thenReturn(5L);
        when(histories.countDistinctGamesBySourceAndGameDate(
                PredictionSource.SHADOW, today
        )).thenReturn(4L);
        when(users.countBySettledFalse()).thenReturn(12L);
        when(loader.artifact()).thenReturn(artifact);
        when(artifact.modelVersion()).thenReturn("logistic-v1");
        when(loader.artifactSha256()).thenReturn("PINNED-HASH");

        var response = new AdminDashboardService(
                games, systems, histories, users, properties, loader,
                Clock.fixed(Instant.parse("2026-08-11T03:00:00Z"),
                        ZoneId.of("Asia/Seoul"))
        ).getSummary();

        assertThat(response.date()).isEqualTo(today);
        assertThat(response.totalGameCount()).isEqualTo(7);
        assertThat(response.scheduledGameCount()).isEqualTo(2);
        assertThat(response.inProgressGameCount()).isEqualTo(1);
        assertThat(response.finishedGameCount()).isEqualTo(3);
        assertThat(response.cancelledGameCount()).isEqualTo(1);
        assertThat(response.systemPredictionCount()).isEqualTo(5);
        assertThat(response.shadowPredictionGameCount()).isEqualTo(4);
        assertThat(response.pendingUserPredictionCount()).isEqualTo(12);
        assertThat(response.productionModelVersion()).isEqualTo("baseline-v1");
        assertThat(response.shadowModelVersion()).isEqualTo("logistic-v1");
        assertThat(response.shadowArtifactSha256()).isEqualTo("PINNED-HASH");
    }

    private GameRepository.StatusCount status(GameStatus status, long count) {
        GameRepository.StatusCount value = mock(GameRepository.StatusCount.class);
        when(value.getStatus()).thenReturn(status);
        when(value.getCount()).thenReturn(count);
        return value;
    }
}
