package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:starting-pitcher-polling;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
@ActiveProfiles("test")
class StartingPitcherPollingIntegrationTest {

    @MockitoBean
    private StartingPitcherCollector collector;

    @Autowired
    private StartingPitcherSyncService syncService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private StartingPitcherRepository startingPitcherRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    void pollingPersistsOnlyMissingStartersAndStopsRequestingCompletedGame() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Team home = insertTeam("LG", "LG 트윈스", now);
        Team away = insertTeam("HH", "한화 이글스", now);
        LocalDateTime startAt = now.plusHours(2);
        Game game = gameRepository.saveAndFlush(Game.createCollected(
                "POLL-INTEGRATION-1",
                startAt.getYear(),
                startAt.toLocalDate(),
                startAt.toLocalTime(),
                home,
                away,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                now
        ));
        CollectedStartingPitcher awayPitcher = pitcher(
                game, away, StartingPitcherSide.AWAY, "54729", "황준서"
        );
        CollectedStartingPitcher homePitcher = pitcher(
                game, home, StartingPitcherSide.HOME, "56103", "카라스코"
        );
        when(collector.collect(
                game.getGameDate(), Set.of(game.getExternalGameId())
        )).thenReturn(new StartingPitcherCollectionBatch(
                1, List.of(awayPitcher, homePitcher), List.of()
        ));

        StartingPitcherPollResult first =
                syncService.pollMissingBeforeClose(game.getGameDate());

        assertThat(first.syncResponse().insertedCount()).isEqualTo(2);
        assertThat(first.newlyCompletedGameIds()).containsExactly(game.getId());
        assertThat(first.missingAtClose()).isEmpty();
        assertThat(startingPitcherRepository
                .findByGameIdInWithPlayer(List.of(game.getId())))
                .extracting(value -> value.getPlayer().getName())
                .containsExactlyInAnyOrder("황준서", "카라스코");

        clearInvocations(collector);
        StartingPitcherPollResult second =
                syncService.pollMissingBeforeClose(game.getGameDate());

        assertThat(second.syncResponse().collectedPitcherCount()).isZero();
        assertThat(second.newlyCompletedGameIds()).isEmpty();
        verify(collector, never()).collect(
                game.getGameDate(), Set.of(game.getExternalGameId())
        );
    }

    private Team insertTeam(String code, String name, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO teams (
                    kbo_team_code, name, short_name, primary_color,
                    secondary_color, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                code, name, code, "#000000", "#FFFFFF", now
        );
        return teamRepository.findByKboTeamCode(code).orElseThrow();
    }

    private CollectedStartingPitcher pitcher(
            Game game,
            Team team,
            StartingPitcherSide side,
            String playerId,
            String playerName
    ) {
        return new CollectedStartingPitcher(
                game.getExternalGameId(),
                team.getKboTeamCode(),
                side,
                playerId,
                playerName,
                game.getSeason(),
                null
        );
    }
}
