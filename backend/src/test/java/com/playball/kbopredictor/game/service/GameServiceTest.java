package com.playball.kbopredictor.game.service;

import com.playball.kbopredictor.game.dto.GameResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import com.playball.kbopredictor.prediction.service.SystemPredictionService;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);

    @Mock
    private GameRepository gameRepository;
    @Mock
    private SystemPredictionService systemPredictionService;
    @Mock
    private GameOddsService gameOddsService;
    @Mock
    private StartingPitcherRepository startingPitcherRepository;

    private GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(
                gameRepository,
                systemPredictionService,
                gameOddsService,
                startingPitcherRepository
        );
    }

    @Test
    void mapsBothOneSidedAndUnannouncedStartersByGameWithOneBatchQuery() {
        Game first = game(1L, "20260812LTSK0", 11L, 12L);
        Game second = game(2L, "20260812SSHT0", 21L, 22L);
        Game third = game(3L, "20260812LGWO0", 31L, 32L);
        List<Game> games = List.of(first, second, third);
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(games);
        when(systemPredictionService.getPredictionsByGameIds(List.of(1L, 2L, 3L)))
                .thenReturn(Map.of());
        when(gameOddsService.getOddsForGames(games)).thenReturn(Map.of());

        StartingPitcher firstHome = starter(
                first, first.getHomeTeam(), StartingPitcherSide.HOME,
                101L, "56840", "김민준"
        );
        StartingPitcher firstAway = starter(
                first, first.getAwayTeam(), StartingPitcherSide.AWAY,
                102L, "67539", "나균안"
        );
        StartingPitcher secondAway = starter(
                second, second.getAwayTeam(), StartingPitcherSide.AWAY,
                103L, "56402", "보스"
        );
        when(startingPitcherRepository.findByGameIdInWithPlayer(
                List.of(1L, 2L, 3L)
        )).thenReturn(List.of(firstHome, firstAway, secondAway));

        List<GameResponse> responses = service.getGamesByDate(GAME_DATE);

        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).homeStartingPitcherPlayerId()).isEqualTo(101L);
        assertThat(responses.get(0).homeStartingPitcherName()).isEqualTo("김민준");
        assertThat(responses.get(0).awayStartingPitcherPlayerId()).isEqualTo(102L);
        assertThat(responses.get(0).awayStartingPitcherName()).isEqualTo("나균안");
        assertThat(responses.get(1).homeStartingPitcherName()).isNull();
        assertThat(responses.get(1).awayStartingPitcherName()).isEqualTo("보스");
        assertThat(responses.get(2).homeStartingPitcherName()).isNull();
        assertThat(responses.get(2).awayStartingPitcherName()).isNull();
        verify(startingPitcherRepository)
                .findByGameIdInWithPlayer(List.of(1L, 2L, 3L));
    }

    private Game game(Long id, String externalGameId, Long homeId, Long awayId) {
        Team home = team(homeId, "H" + homeId, "홈 " + homeId);
        Team away = team(awayId, "A" + awayId, "원정 " + awayId);
        Game game = Game.createCollected(
                externalGameId, 2026, GAME_DATE, LocalTime.of(19, 0),
                home, away, "구장", GameStatus.SCHEDULED,
                null, null, null, null, null,
                LocalDateTime.of(2026, 8, 10, 6, 0)
        );
        ReflectionTestUtils.setField(game, "id", id);
        return game;
    }

    private StartingPitcher starter(
            Game game,
            Team team,
            StartingPitcherSide side,
            Long playerId,
            String kboPlayerId,
            String playerName
    ) {
        Player player = Player.create(
                kboPlayerId, team, playerName,
                LocalDateTime.of(2026, 8, 12, 15, 0)
        );
        ReflectionTestUtils.setField(player, "id", playerId);
        return StartingPitcher.create(
                game, team, player, side,
                LocalDateTime.of(2026, 8, 12, 15, 0)
        );
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
