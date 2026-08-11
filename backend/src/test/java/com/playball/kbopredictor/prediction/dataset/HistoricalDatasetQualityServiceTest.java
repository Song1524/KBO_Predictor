package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalDatasetQualityServiceTest {

    @Test
    void reportsSeasonDrawCoverageAndEarlySeasonShortage() {
        LocalDate from = LocalDate.of(2024, 3, 1);
        LocalDate to = LocalDate.of(2025, 4, 1);
        GameRepository games = mock(GameRepository.class);
        PredictionFeatureSnapshotRepository snapshots =
                mock(PredictionFeatureSnapshotRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        HistoricalDatasetMapper mapper = mock(HistoricalDatasetMapper.class);
        Game draw2024 = game(1L, 2024, GameStatus.FINISHED, GameResult.DRAW);
        Game home2025 = game(
                2L, 2025, GameStatus.FINISHED, GameResult.HOME_WIN
        );
        PredictionFeatureSnapshot first = snapshot(draw2024, 0, 0);
        PredictionFeatureSnapshot mature = snapshot(home2025, 12, 11);
        when(games.findByGameDateBetweenWithTeams(from, to))
                .thenReturn(List.of(draw2024, home2025));
        when(snapshots.findEvaluationSnapshots(from, to))
                .thenReturn(List.of(first, mature));
        when(mapper.toRow(first)).thenReturn(row(1L, 2024, 0));
        when(mapper.toRow(mature)).thenReturn(row(2L, 2025, 6));
        List<Team> mappedTeams = kboTeams();
        when(teams.findAll()).thenReturn(mappedTeams);
        HistoricalDatasetQualityService service =
                new HistoricalDatasetQualityService(
                        games, snapshots, teams, mapper
                );

        HistoricalDatasetQualityResponse response = service.inspect(from, to);

        assertThat(response.seasons()).hasSize(2);
        assertThat(response.overall().drawCount()).isOne();
        assertThat(response.overall().featureGeneratedGameCount()).isOne();
        assertThat(response.overall().recent10AvailableCount()).isOne();
        assertThat(response.overall().noPriorGameCount()).isOne();
        assertThat(response.teamMapping().valid()).isTrue();
    }

    private Game game(
            Long id,
            int season,
            GameStatus status,
            GameResult result
    ) {
        Game game = mock(Game.class);
        when(game.getSeason()).thenReturn(season);
        when(game.getStatus()).thenReturn(status);
        when(game.getResult()).thenReturn(result);
        return game;
    }

    private PredictionFeatureSnapshot snapshot(
            Game game,
            int homeCount,
            int awayCount
    ) {
        PredictionFeatureSnapshot snapshot = mock(
                PredictionFeatureSnapshot.class
        );
        when(snapshot.getGame()).thenReturn(game);
        when(snapshot.getHomeHistoricalGameCount()).thenReturn(homeCount);
        when(snapshot.getAwayHistoricalGameCount()).thenReturn(awayCount);
        return snapshot;
    }

    private HistoricalMlDatasetRow row(
            Long id,
            int season,
            int available
    ) {
        return new HistoricalMlDatasetRow(
                id,
                season,
                LocalDate.of(season, 4, 1),
                null, null, null, null, null, null,
                available,
                java.math.BigDecimal.valueOf(available)
                        .multiply(java.math.BigDecimal.valueOf(100))
                        .divide(java.math.BigDecimal.valueOf(6), 2,
                                java.math.RoundingMode.HALF_UP),
                available == 0
                        ? PredictionOutcome.DRAW
                        : PredictionOutcome.HOME_WIN
        );
    }

    private List<Team> kboTeams() {
        return List.of("LG", "HH", "SK", "SS", "NC", "KT", "LT", "HT", "OB", "WO")
                .stream()
                .map(code -> {
                    Team team = mock(Team.class);
                    when(team.getKboTeamCode()).thenReturn(code);
                    return team;
                })
                .toList();
    }
}
