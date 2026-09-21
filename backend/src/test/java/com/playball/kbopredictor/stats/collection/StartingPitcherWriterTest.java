package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.player.repository.PlayerRepository;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartingPitcherWriterTest {

    @Mock
    private GameRepository gameRepository;
    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private StartingPitcherRepository startingPitcherRepository;
    @Mock
    private PitcherStatRepository pitcherStatRepository;

    @Test
    void pollingWriteRechecksCloseAfterLockAndSkipsAtExactDeadline() {
        LocalDate gameDate = LocalDate.of(2026, 9, 20);
        Game game = game(gameDate, LocalTime.of(14, 0));
        when(gameRepository.findByExternalGameId(game.getExternalGameId()))
                .thenReturn(Optional.of(game));
        StartingPitcherWriter writer = new StartingPitcherWriter(
                gameRepository,
                playerRepository,
                startingPitcherRepository,
                pitcherStatRepository,
                fixed(game.getPredictionCloseAt())
        );

        Optional<StartingPitcherWriteResult> result = writer.upsertBeforeClose(
                new CollectedStartingPitcher(
                        game.getExternalGameId(),
                        game.getAwayTeam().getKboTeamCode(),
                        StartingPitcherSide.AWAY,
                        "54729",
                        "황준서",
                        2026,
                        null
                ),
                gameDate
        );

        assertThat(result).isEmpty();
        verify(playerRepository, never()).findByKboPlayerIdForUpdate(any());
        verify(startingPitcherRepository, never()).save(any());
    }

    @Test
    void pollingWriteReplacesExistingStarterWhenOfficialPlayerIdChanges() {
        LocalDate gameDate = LocalDate.of(2026, 9, 20);
        LocalDateTime now = LocalDateTime.of(gameDate, LocalTime.NOON);
        Game game = game(gameDate, LocalTime.of(18, 30));
        Player oldPlayer = Player.create(
                "OLD-HOME", game.getHomeTeam(), "기존 선발", now.minusDays(1)
        );
        ReflectionTestUtils.setField(oldPlayer, "id", 100L);
        StartingPitcher existing = StartingPitcher.create(
                game,
                game.getHomeTeam(),
                oldPlayer,
                StartingPitcherSide.HOME,
                now.minusDays(1)
        );
        when(gameRepository.findByExternalGameId(game.getExternalGameId()))
                .thenReturn(Optional.of(game));
        when(playerRepository.findByKboPlayerIdForUpdate("NEW-HOME"))
                .thenReturn(Optional.empty());
        when(playerRepository.save(any(Player.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(startingPitcherRepository.findByGameIdAndSide(
                game.getId(), StartingPitcherSide.HOME
        )).thenReturn(Optional.of(existing));
        when(startingPitcherRepository.save(any(StartingPitcher.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StartingPitcherWriter writer = new StartingPitcherWriter(
                gameRepository,
                playerRepository,
                startingPitcherRepository,
                pitcherStatRepository,
                fixed(now)
        );

        StartingPitcherWriteResult result = writer.upsertBeforeClose(
                new CollectedStartingPitcher(
                        game.getExternalGameId(),
                        game.getHomeTeam().getKboTeamCode(),
                        StartingPitcherSide.HOME,
                        "NEW-HOME",
                        "새 선발",
                        2026,
                        null
                ),
                gameDate
        ).orElseThrow();

        assertThat(result.inserted()).isFalse();
        assertThat(result.playerChanged()).isTrue();
        assertThat(result.gameId()).isEqualTo(game.getId());
        assertThat(existing.getPlayer().getKboPlayerId()).isEqualTo("NEW-HOME");
    }

    private Game game(LocalDate date, LocalTime time) {
        Team home = team(1L, "LG", "LG 트윈스");
        Team away = team(2L, "HH", "한화 이글스");
        Game game = Game.createCollected(
                "20260920HHLG0",
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
