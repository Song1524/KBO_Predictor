package com.playball.kbopredictor.game.service;

import com.playball.kbopredictor.game.dto.GameStartingPitchersResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.stats.entity.PitcherStat;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameStartingPitcherServiceTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDateTime GAME_START = LocalDateTime.of(
            GAME_DATE,
            LocalTime.of(18, 30)
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private StartingPitcherRepository startingPitcherRepository;

    @Mock
    private PitcherStatRepository pitcherStatRepository;

    private GameStartingPitcherService service;
    private Game game;
    private Team home;
    private Team away;

    @BeforeEach
    void setUp() {
        service = new GameStartingPitcherService(
                gameRepository,
                startingPitcherRepository,
                pitcherStatRepository
        );
        home = team(1L, "LG", "LG 트윈스");
        away = team(2L, "HH", "한화 이글스");
        game = Game.createCollected(
                "20260812HHLG0", 2026, GAME_DATE, GAME_START.toLocalTime(),
                home, away, "잠실", GameStatus.SCHEDULED,
                null, null, null, null, null,
                GAME_START.minusDays(2)
        );
        ReflectionTestUtils.setField(game, "id", 10L);
        when(gameRepository.findById(10L)).thenReturn(Optional.of(game));
    }

    @Test
    void returnsBothPitchersWithSnapshotsAvailableBeforeGameStart() {
        StartingPitcher homeStarter = starter(
                home, StartingPitcherSide.HOME, 101L, "61101", "임찬규",
                GAME_START.minusHours(3)
        );
        StartingPitcher awayStarter = starter(
                away, StartingPitcherSide.AWAY, 102L, "62202", "류현진",
                GAME_START.minusHours(3)
        );
        PitcherStat homeStat = stat(
                homeStarter.getPlayer(), GAME_DATE,
                "3.21", 9, 4, "120 1/3", "1.18"
        );
        PitcherStat awayStat = stat(
                awayStarter.getPlayer(), GAME_DATE.minusDays(1),
                "2.87", 11, 5, "135", "1.09"
        );
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(10L)))
                .thenReturn(List.of(homeStarter, awayStarter));
        when(pitcherStatRepository
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        101L, GAME_DATE, GAME_START
                )).thenReturn(Optional.of(homeStat));
        when(pitcherStatRepository
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        102L, GAME_DATE, GAME_START
                )).thenReturn(Optional.of(awayStat));

        GameStartingPitchersResponse response = service.getByGameId(10L);

        assertThat(response.gameId()).isEqualTo(10L);
        assertThat(response.home().playerName()).isEqualTo("임찬규");
        assertThat(response.home().statsAvailable()).isTrue();
        assertThat(response.home().era()).isEqualByComparingTo("3.21");
        assertThat(response.home().wins()).isEqualTo(9);
        assertThat(response.home().losses()).isEqualTo(4);
        assertThat(response.home().innings()).isEqualTo("120 1/3");
        assertThat(response.home().whip()).isEqualByComparingTo("1.18");
        assertThat(response.away().playerName()).isEqualTo("류현진");
        assertThat(response.away().statDate()).isEqualTo(GAME_DATE.minusDays(1));
        verify(pitcherStatRepository)
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        101L, GAME_DATE, GAME_START
                );
    }

    @Test
    void lateStarterKeepsIdentityButDoesNotUsePostgameStats() {
        StartingPitcher lateStarter = starter(
                home, StartingPitcherSide.HOME, 101L, "61101", "임찬규",
                GAME_START.plusMinutes(1)
        );
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(10L)))
                .thenReturn(List.of(lateStarter));

        GameStartingPitchersResponse response = service.getByGameId(10L);

        assertThat(response.home().playerName()).isEqualTo("임찬규");
        assertThat(response.home().statsAvailable()).isFalse();
        assertThat(response.home().statDate()).isNull();
        assertThat(response.away()).isNull();
        verify(pitcherStatRepository, never())
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    private StartingPitcher starter(
            Team team,
            StartingPitcherSide side,
            Long playerId,
            String kboPlayerId,
            String playerName,
            LocalDateTime collectedAt
    ) {
        Player player = Player.create(kboPlayerId, team, playerName, collectedAt);
        ReflectionTestUtils.setField(player, "id", playerId);
        return StartingPitcher.create(game, team, player, side, collectedAt);
    }

    private PitcherStat stat(
            Player player,
            LocalDate statDate,
            String era,
            int wins,
            int losses,
            String innings,
            String whip
    ) {
        PitcherStat stat = PitcherStat.create(player, 2026, statDate);
        stat.update(
                new BigDecimal(era), wins, losses, innings,
                new BigDecimal(whip), GAME_START.minusHours(2)
        );
        return stat;
    }

    private Team team(Long id, String code, String name) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "kboTeamCode", code);
        ReflectionTestUtils.setField(team, "name", name);
        return team;
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
