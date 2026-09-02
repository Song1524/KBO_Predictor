package com.playball.kbopredictor.game.controller;

import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.game.dto.GameResponse;
import com.playball.kbopredictor.game.dto.GameStartingPitchersResponse;
import com.playball.kbopredictor.game.dto.StartingPitcherDetailResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.service.GameService;
import com.playball.kbopredictor.game.service.GameStartingPitcherService;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameController.class)
@Import(SecurityConfig.class)
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private GameStartingPitcherService gameStartingPitcherService;

    @Test
    void gamesApiReturnsStartersForTheCorrectSides() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 12);
        Team home = team(3L, "SK", "SSG 랜더스");
        Team away = team(7L, "LT", "롯데 자이언츠");
        Game game = Game.createCollected(
                "20260812LTSK0", 2026, date, LocalTime.of(19, 0),
                home, away, "문학", GameStatus.SCHEDULED,
                null, null, null, null, null,
                LocalDateTime.of(2026, 8, 10, 6, 0)
        );
        ReflectionTestUtils.setField(game, "id", 2L);
        StartingPitcher homeStarter = starter(
                game, home, StartingPitcherSide.HOME, 101L, "56840", "김민준"
        );
        StartingPitcher awayStarter = starter(
                game, away, StartingPitcherSide.AWAY, 102L, "67539", "나균안"
        );
        when(gameService.getGamesByDate(date)).thenReturn(List.of(
                GameResponse.from(game, null, null, homeStarter, awayStarter)
        ));

        mockMvc.perform(get("/api/games").param("date", "2026-08-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].homeStartingPitcherPlayerId").value(101))
                .andExpect(jsonPath("$[0].homeStartingPitcherName").value("김민준"))
                .andExpect(jsonPath("$[0].awayStartingPitcherPlayerId").value(102))
                .andExpect(jsonPath("$[0].awayStartingPitcherName").value("나균안"));
    }

    @Test
    void gamesApiReturnsPreservedLiveScoreWhileFinalResultIsPending()
            throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 12);
        Team home = team(1L, "OB", "두산 베어스");
        Team away = team(2L, "LG", "LG 트윈스");
        Game game = Game.createCollected(
                "20260812LGOB0", 2026, date, LocalTime.of(18, 30),
                home, away, "잠실", GameStatus.FINISHED,
                5, 3, null, null, null,
                LocalDateTime.of(2026, 8, 12, 22, 0)
        );
        ReflectionTestUtils.setField(game, "id", 1L);
        when(gameService.getGamesByDate(date)).thenReturn(List.of(
                GameResponse.from(game, null, null, null, null)
        ));

        mockMvc.perform(get("/api/games").param("date", "2026-08-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FINISHED"))
                .andExpect(jsonPath("$[0].homeScore").value(5))
                .andExpect(jsonPath("$[0].awayScore").value(3))
                .andExpect(jsonPath("$[0].result").doesNotExist())
                .andExpect(jsonPath("$[0].winnerTeamId").doesNotExist());
    }

    @Test
    void startingPitchersApiIsPublicAndReturnsBothSidesWithStats()
            throws Exception {
        when(gameStartingPitcherService.getByGameId(2L)).thenReturn(
                new GameStartingPitchersResponse(
                        2L,
                        new StartingPitcherDetailResponse(
                                101L, "김민준", true, 2026,
                                LocalDate.of(2026, 8, 12),
                                new BigDecimal("3.72"), 9, 7,
                                "120 1/3", new BigDecimal("1.24")
                        ),
                        new StartingPitcherDetailResponse(
                                102L, "나균안", false, null,
                                null, null, null, null, null, null
                        )
                )
        );

        mockMvc.perform(get("/api/games/2/starting-pitchers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(2))
                .andExpect(jsonPath("$.home.playerName").value("김민준"))
                .andExpect(jsonPath("$.home.statsAvailable").value(true))
                .andExpect(jsonPath("$.home.era").value(3.72))
                .andExpect(jsonPath("$.home.wins").value(9))
                .andExpect(jsonPath("$.home.innings").value("120 1/3"))
                .andExpect(jsonPath("$.away.playerName").value("나균안"))
                .andExpect(jsonPath("$.away.statsAvailable").value(false));
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
