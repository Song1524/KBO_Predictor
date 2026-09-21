package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.prediction.generation.PredictionRefreshReason;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock
    private SystemPredictionGenerationService predictionGenerationService;

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
                predictionGenerationService,
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
        when(writer.upsertBeforeClose(missingAway, GAME_DATE))
                .thenReturn(Optional.of(new StartingPitcherWriteResult(true, true)));

        StartingPitcherPollResult result =
                service.pollMissingBeforeClose(GAME_DATE);
        StartingPitcherSyncResponse response = result.syncResponse();

        assertThat(response.collectedPitcherCount()).isEqualTo(1);
        assertThat(response.insertedCount()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        assertThat(result.newlyCompletedGameIds()).containsExactly(2L);
        assertThat(result.missingAtClose()).isEmpty();
        verify(collector).collect(GAME_DATE, Set.of("20260812LTSK0"));
        verify(writer).upsertBeforeClose(missingAway, GAME_DATE);
        verify(writer, never()).upsertBeforeClose(unchangedHome, GAME_DATE);
        verify(predictionGenerationService).refreshStale(
                2L, PredictionRefreshReason.STARTER_ACQUIRED
        );
    }

    @Test
    void failedPredictionRefreshIsRetriedByNextMissingPollingRun() {
        Game game = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(19, 0));
        StartingPitcher home = starter(
                game, game.getHomeTeam(), StartingPitcherSide.HOME, 101L
        );
        StartingPitcher away = starter(
                game, game.getAwayTeam(), StartingPitcherSide.AWAY, 102L
        );
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(game));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L)))
                .thenReturn(List.of(home))
                .thenReturn(List.of(home, away));
        CollectedStartingPitcher missingAway = collected(
                game.getExternalGameId(), "LT", StartingPitcherSide.AWAY,
                "P102", "원정 선발"
        );
        when(collector.collect(GAME_DATE, Set.of(game.getExternalGameId())))
                .thenReturn(new StartingPitcherCollectionBatch(
                        1, List.of(missingAway), List.of()
                ));
        when(writer.upsertBeforeClose(missingAway, GAME_DATE))
                .thenReturn(Optional.of(new StartingPitcherWriteResult(true, true)));
        when(predictionGenerationService.refreshStale(
                1L, PredictionRefreshReason.STARTER_ACQUIRED
        )).thenThrow(new IllegalStateException("temporary"))
                .thenReturn(null);

        service.pollMissingBeforeClose(GAME_DATE);
        service.pollMissingBeforeClose(GAME_DATE);

        verify(predictionGenerationService, times(2)).refreshStale(
                1L, PredictionRefreshReason.STARTER_ACQUIRED
        );
        verify(collector, times(1)).collect(
                GAME_DATE, Set.of(game.getExternalGameId())
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

        StartingPitcherPollResult result =
                service.pollMissingBeforeClose(GAME_DATE);

        assertThat(result.syncResponse().collectedPitcherCount()).isZero();
        assertThat(result.newlyCompletedGameIds()).isEmpty();
        verify(collector, never()).collect(eq(GAME_DATE), any());
        verify(writer, never()).upsertBeforeClose(any(), any());
    }

    @Test
    void completeVerificationUpdatesChangedHomeStarterAndRefreshesPrediction() {
        Game game = completeGameWithStarters(1L, "20260812HHOB0");
        CollectedStartingPitcher changedHome = collected(
                game.getExternalGameId(), "SK", StartingPitcherSide.HOME,
                "NEW-HOME", "새 홈 선발"
        );
        CollectedStartingPitcher sameAway = collected(
                game.getExternalGameId(), "LT", StartingPitcherSide.AWAY,
                "P102", "기존 원정 선발"
        );
        when(collector.collect(GAME_DATE, Set.of(game.getExternalGameId())))
                .thenReturn(new StartingPitcherCollectionBatch(
                        1, List.of(changedHome, sameAway), List.of()
                ));
        when(writer.upsertBeforeClose(changedHome, GAME_DATE))
                .thenReturn(Optional.of(new StartingPitcherWriteResult(
                        false, true, true, 1L, StartingPitcherSide.HOME
                )));

        StartingPitcherSyncResponse response =
                service.verifyCompleteBeforeClose(GAME_DATE);

        assertThat(response.updatedCount()).isOne();
        verify(writer).upsertBeforeClose(changedHome, GAME_DATE);
        verify(writer, never()).upsertBeforeClose(sameAway, GAME_DATE);
        verify(predictionGenerationService).refreshStale(
                1L, PredictionRefreshReason.STARTER_CHANGED
        );
    }

    @Test
    void completeVerificationUpdatesChangedAwayStarterAndRefreshesPrediction() {
        Game game = completeGameWithStarters(1L, "20260812HHOB0");
        CollectedStartingPitcher sameHome = collected(
                game.getExternalGameId(), "SK", StartingPitcherSide.HOME,
                "P101", "기존 홈 선발"
        );
        CollectedStartingPitcher changedAway = collected(
                game.getExternalGameId(), "LT", StartingPitcherSide.AWAY,
                "NEW-AWAY", "새 원정 선발"
        );
        when(collector.collect(GAME_DATE, Set.of(game.getExternalGameId())))
                .thenReturn(new StartingPitcherCollectionBatch(
                        1, List.of(sameHome, changedAway), List.of()
                ));
        when(writer.upsertBeforeClose(changedAway, GAME_DATE))
                .thenReturn(Optional.of(new StartingPitcherWriteResult(
                        false, true, true, 1L, StartingPitcherSide.AWAY
                )));

        service.verifyCompleteBeforeClose(GAME_DATE);

        verify(writer, never()).upsertBeforeClose(sameHome, GAME_DATE);
        verify(writer).upsertBeforeClose(changedAway, GAME_DATE);
        verify(predictionGenerationService).refreshStale(
                1L, PredictionRefreshReason.STARTER_CHANGED
        );
    }

    @Test
    void completeVerificationDoesNotRefreshWhenOfficialPlayerIdsAreUnchanged() {
        Game game = completeGameWithStarters(1L, "20260812HHOB0");
        when(collector.collect(GAME_DATE, Set.of(game.getExternalGameId())))
                .thenReturn(new StartingPitcherCollectionBatch(
                        1,
                        List.of(
                                collected(game.getExternalGameId(), "SK",
                                        StartingPitcherSide.HOME, "P101", "홈"),
                                collected(game.getExternalGameId(), "LT",
                                        StartingPitcherSide.AWAY, "P102", "원정")
                        ),
                        List.of()
                ));

        StartingPitcherSyncResponse response =
                service.verifyCompleteBeforeClose(GAME_DATE);

        assertThat(response.collectedPitcherCount()).isZero();
        verify(writer, never()).upsertBeforeClose(any(), any());
        verify(predictionGenerationService, never()).refreshStale(any(), any());
    }

    @Test
    void completeVerificationStopsAtCloseWithoutRefreshingPrediction() {
        service = serviceAt(LocalDateTime.of(2026, 8, 12, 18, 20));
        Game game = completeGameWithStarters(1L, "20260812HHOB0");

        StartingPitcherSyncResponse response =
                service.verifyCompleteBeforeClose(GAME_DATE);

        assertThat(response.sourceGameCount()).isZero();
        verify(collector, never()).collect(eq(GAME_DATE), any());
        verify(predictionGenerationService, never()).refreshStale(any(), any());
    }

    @Test
    void manualSyncAppliesSamePredictionRefreshPolicyForStarterChange() {
        Game game = completeGameWithStarters(1L, "20260812HHOB0");
        CollectedStartingPitcher changedHome = collected(
                game.getExternalGameId(), "SK", StartingPitcherSide.HOME,
                "MANUAL-HOME", "수동 변경 홈 선발"
        );
        when(collector.collect(GAME_DATE)).thenReturn(
                new StartingPitcherCollectionBatch(
                        1, List.of(changedHome), List.of()
                )
        );
        when(writer.upsert(
                changedHome,
                GAME_DATE,
                LocalDateTime.of(2026, 8, 12, 16, 0)
        )).thenReturn(new StartingPitcherWriteResult(
                false, true, true, 1L, StartingPitcherSide.HOME
        ));

        service.sync(GAME_DATE);

        verify(predictionGenerationService).refreshStale(
                1L, PredictionRefreshReason.STARTER_CHANGED
        );
    }

    @Test
    void pollingStopsAtPredictionCloseAndReportsMissingSides() {
        Game started = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(15, 30));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(started));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L)))
                .thenReturn(List.of());

        StartingPitcherPollResult result =
                service.pollMissingBeforeClose(GAME_DATE);

        assertThat(result.syncResponse().sourceGameCount()).isZero();
        assertThat(result.missingAtClose()).hasSize(1);
        assertThat(result.missingAtClose().getFirst().missingSides())
                .containsExactly(StartingPitcherSide.HOME, StartingPitcherSide.AWAY);
        verify(collector, never()).collect(eq(GAME_DATE), any());
    }

    @Test
    void pollingUsesEachGamesCloseTimeForEarlyAndEveningGames() {
        service = serviceAt(LocalDateTime.of(2026, 8, 12, 13, 0));
        Game early = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(14, 0));
        Game evening = game(2L, "20260812LTSK0", 21L, 22L, LocalTime.of(18, 30));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(early, evening));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L, 2L)))
                .thenReturn(List.of());
        Set<String> targets = Set.of("20260812HHOB0", "20260812LTSK0");
        when(collector.collect(GAME_DATE, targets))
                .thenReturn(new StartingPitcherCollectionBatch(
                        2, List.of(), List.of()
                ));

        StartingPitcherPollResult result =
                service.pollMissingBeforeClose(GAME_DATE);

        assertThat(result.syncResponse().sourceGameCount()).isEqualTo(2);
        assertThat(result.missingAtClose()).isEmpty();
        verify(collector).collect(GAME_DATE, targets);
    }

    @Test
    void inProgressGameIsNeverCollectedEvenWhenCloseTimeIsInFuture() {
        Game game = game(1L, "20260812HHOB0", 11L, 12L, LocalTime.of(19, 0));
        ReflectionTestUtils.setField(game, "status", GameStatus.IN_PROGRESS);
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(game));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(1L)))
                .thenReturn(List.of());

        StartingPitcherPollResult result =
                service.pollMissingBeforeClose(GAME_DATE);

        assertThat(result.syncResponse().collectedPitcherCount()).isZero();
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

    private Game completeGameWithStarters(Long id, String externalGameId) {
        Game game = game(id, externalGameId, 11L, 12L, LocalTime.of(18, 30));
        when(gameRepository.findByGameDateOrderByGameTimeAsc(GAME_DATE))
                .thenReturn(List.of(game));
        when(startingPitcherRepository.findByGameIdInWithPlayer(List.of(id)))
                .thenReturn(List.of(
                        starter(game, game.getHomeTeam(), StartingPitcherSide.HOME, 101L),
                        starter(game, game.getAwayTeam(), StartingPitcherSide.AWAY, 102L)
                ));
        return game;
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

    private StartingPitcherSyncService serviceAt(LocalDateTime time) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return new StartingPitcherSyncService(
                collector,
                writer,
                gameRepository,
                startingPitcherRepository,
                predictionGenerationService,
                Clock.fixed(time.atZone(zone).toInstant(), zone)
        );
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
