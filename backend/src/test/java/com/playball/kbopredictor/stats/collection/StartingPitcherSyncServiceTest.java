package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartingPitcherSyncServiceTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);

    @Mock
    private StartingPitcherCollector collector;
    @Mock
    private StartingPitcherWriter writer;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private StartingPitcherRepository startingPitcherRepository;

    private StartingPitcherSyncService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-12T07:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        service = new StartingPitcherSyncService(
                collector,
                writer,
                gameRepository,
                startingPitcherRepository,
                clock
        );
    }

    @Test
    void retryRequestsOnlyIncompleteGamesAndWritesOnlyMissingSide() {
        Game complete = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(19, 0));
        Game incomplete = game(2L, "20260812LTSK0", 21L, 22L, LocalTime.of(19, 0));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(complete, incomplete));

        StartingPitcher completeHome = starter(
                complete, complete.getHomeTeam(), StartingPitcherSide.HOME, 101L
        );
        StartingPitcher completeAway = starter(
                complete, complete.getAwayTeam(), StartingPitcherSide.AWAY, 102L
        );
        StartingPitcher existingHome = starter(
                incomplete, incomplete.getHomeTeam(), StartingPitcherSide.HOME, 103L
        );
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L, 2L)))
                .thenReturn(List.of(completeHome, completeAway, existingHome));

        CollectedStartingPitcher unchangedHome = collected(
                "20260812LTSK0", "SK", StartingPitcherSide.HOME,
                "56840", "김민준"
        );
        CollectedStartingPitcher missingAway = collected(
                "20260812LTSK0", "LT", StartingPitcherSide.AWAY,
                "67539", "나균안"
        );
        when(collector.collect(GAME_DATE, Set.of("20260812LTSK0")))
                .thenReturn(new StartingPitcherCollectionBatch(
                        5, List.of(unchangedHome, missingAway), List.of()
                ));
        when(writer.upsert(eq(missingAway), eq(GAME_DATE), any(LocalDateTime.class)))
                .thenReturn(new StartingPitcherWriteResult(true, true));

        StartingPitcherSyncResponse response =
                service.retryMissingBeforeStart(GAME_DATE);

        assertThat(response.collectedPitcherCount()).isEqualTo(1);
        assertThat(response.insertedCount()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        verify(collector).collect(GAME_DATE, Set.of("20260812LTSK0"));
        verify(writer).upsert(eq(missingAway), eq(GAME_DATE), any(LocalDateTime.class));
        verify(writer, never()).upsert(
                eq(unchangedHome), eq(GAME_DATE), any(LocalDateTime.class)
        );
    }

    @Test
    void retrySkipsExternalRequestWhenAllPregameStartersExist() {
        Game game = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(19, 0));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(game));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L)))
                .thenReturn(List.of(
                        starter(game, game.getHomeTeam(), StartingPitcherSide.HOME, 101L),
                        starter(game, game.getAwayTeam(), StartingPitcherSide.AWAY, 102L)
                ));

        StartingPitcherSyncResponse response =
                service.retryMissingBeforeStart(GAME_DATE);

        assertThat(response.collectedPitcherCount()).isZero();
        verify(collector, never()).collect(eq(GAME_DATE), any());
        verify(writer, never()).upsert(any(), any(), any());
    }

    @Test
    void retryStopsForGamesThatAlreadyStarted() {
        Game started = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(15, 30));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(started));

        StartingPitcherSyncResponse response =
                service.retryMissingBeforeStart(GAME_DATE);

        assertThat(response.sourceGameCount()).isZero();
        verify(startingPitcherRepository, never()).findByGameIdInWithPlayer(any());
        verify(collector, never()).collect(eq(GAME_DATE), any());
    }

    private CollectedStartingPitcher collected(
            String externalGameId,
            String teamCode,
            StartingPitcherSide side,
            String playerId,
            String name
    ) {
        return new CollectedStartingPitcher(
                externalGameId, teamCode, side, playerId, name, 2026, null
        );
    }

    private Game game(
            Long id,
            String externalGameId,
            Long homeId,
            Long awayId,
            LocalTime time
    ) {
        Team home = team(homeId, "SK", "홈");
        Team away = team(awayId, "LT", "원정");
        Game game = Game.createCollected(
                externalGameId, 2026, GAME_DATE, time,
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
            Long playerId
    ) {
        Player player = Player.create(
                "P" + playerId, team, "선발 " + playerId,
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
