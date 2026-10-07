package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.*;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.player.repository.PlayerRepository;
import com.playball.kbopredictor.prediction.engine.ActivePredictionEngine;
import com.playball.kbopredictor.prediction.feature.PredictionFeatureService;
import com.playball.kbopredictor.prediction.history.*;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.stats.collection.*;
import com.playball.kbopredictor.stats.entity.*;
import com.playball.kbopredictor.stats.repository.*;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Flyway + MySQL locks, actual feature builder and operational engine. No remote KBO calls. */
@SpringBootTest
@ActiveProfiles("test")
class SystemPredictionRecoveryMySqlIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    @Autowired SystemPredictionGenerationService generation;
    @Autowired PredictionFeatureService features;
    @Autowired SystemPredictionFinalizationService finalization;
    @Autowired GameRepository games;
    @Autowired TeamRepository teams;
    @Autowired SystemPredictionRepository predictions;
    @Autowired PredictionFeatureSnapshotRepository snapshots;
    @Autowired TeamStatRepository teamStats;
    @Autowired PitcherStatRepository pitcherStats;
    @Autowired StartingPitcherRepository starters;
    @Autowired PlayerRepository players;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean Clock clock;
    @MockitoBean ShadowPredictionService shadow;
    @MockitoSpyBean(name = "activePredictionEngine") ActivePredictionEngine engine;
    private final List<Long> gameIds = new ArrayList<>();
    private final List<Long> statIds = new ArrayList<>();
    private final List<Long> playerIds = new ArrayList<>();
    private Team home;
    private Team away;

    @BeforeEach
    void isolatedMySql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getURL()).contains("_test")
                    .matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*");
        }
        when(clock.getZone()).thenReturn(ZONE);
        setTime(TODAY.atTime(12, 0));
        home = teams.findAll().get(0);
        away = teams.findAll().get(1);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        transaction.executeWithoutResult(tx -> {
            for (Long id : gameIds) {
                jdbc.update("delete from system_prediction_histories where game_id = ?", id);
                jdbc.update("delete from system_predictions where game_id = ?", id);
                jdbc.update("delete from prediction_feature_snapshots where game_id = ?", id);
                jdbc.update("delete from starting_pitchers where game_id = ?", id);
                jdbc.update("delete from games where id = ?", id);
            }
            for (Long id : statIds) jdbc.update("delete from team_stats where id = ?", id);
            for (Long id : playerIds) {
                jdbc.update("delete from pitcher_stats where player_id = ?", id);
                jdbc.update("delete from players where id = ?", id);
            }
        });
    }

    @Test
    void missingPredictionCreatesInitialFromRefresh() {
        Long id = create(TODAY);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.CREATED);
        assertRows(id, 1);
        assertStage(id, PredictionStage.INITIAL);
        verify(shadow).generate(any(), any());
    }

    @Test
    void dailyGenerationFailureIsRecoveredOnNextPeriodicRefresh() {
        Long id = create(TODAY);
        doThrow(new IllegalStateException("temporary engine failure")).doCallRealMethod().when(engine).predict(any());
        assertThat(generation.generateForDate(TODAY).failedCount()).isEqualTo(1);
        assertThat(predictions.findByGameId(id)).isEmpty();
        assertRows(id, 0);
        scheduler().recoverMissingOrStalePredictions();
        assertRows(id, 1);
        assertStage(id, PredictionStage.INITIAL);
    }

    @Test
    void gameAddedAfterDailySchedulerIsCreatedByRefresh() {
        scheduler().syncDailyTeamStats();
        Long id = create(TODAY.plusDays(1));
        scheduler().recoverMissingOrStalePredictions();
        assertRows(id, 1);
        assertStage(id, PredictionStage.INITIAL);
    }

    @Test
    void startupAfterDailySchedulerRecoversTodayAndUpcomingMissingPredictions() {
        Long today = create(TODAY);
        Long tomorrow = create(TODAY.plusDays(1));
        scheduler().refreshStalePredictionsAfterStartup();
        assertRows(today, 1);
        assertRows(tomorrow, 1);
        scheduler().refreshStalePredictionsAfterStartup();
        assertRows(today, 1);
        assertRows(tomorrow, 1);
    }

    @Test
    void starterAcquisitionCreatesInitialWhenPredictionIsAbsent() {
        Long id = create(TODAY);
        addPitcher(id, 2026, "3.20");
        assertThat(generation.refreshStale(id, PredictionRefreshReason.STARTER_ACQUIRED).status())
                .isEqualTo(SystemPredictionGenerationStatus.CREATED);
        assertRows(id, 1);
        assertStage(id, PredictionStage.INITIAL);
        assertThat(predictions.findByGameId(id).orElseThrow().getHomeStartingPitcherKboPlayerId()).isNotNull();
    }

    @Test
    void unchangedInputSkipsEngineHistoryAndShadowForBothDailyAndRefresh() {
        Long id = create(TODAY);
        generation.generate(id);
        clearInvocations(engine, shadow);
        assertThat(generation.generate(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        verify(engine, never()).predict(any());
        verifyNoInteractions(shadow);
        assertRows(id, 1);
    }

    @Test
    void starterChangeRefreshesUsingStarterUpdatedStage() {
        Long id = create(TODAY);
        Long first = addPitcher(id, 2026, "3.20");
        generation.generate(id);
        Long changed = addPitcher(id, 2026, "2.10");
        assertThat(changed).isNotEqualTo(first);
        assertThat(generation.refreshStale(id, PredictionRefreshReason.STARTER_CHANGED).status())
                .isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertRows(id, 2);
        assertStage(id, PredictionStage.STARTER_UPDATED);
        assertThat(predictions.findByGameId(id).orElseThrow().getReason()).contains("[선발 교체]");
    }

    @Test
    void sameDateTeamStatCorrectionRefreshesWithoutDateChange() {
        Long id = create(TODAY);
        Long stat = addTeamStat(2026, "0.500");
        generation.generate(id);
        jdbc.update("update team_stats set win_rate = 0.700 where id = ?", stat);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertRows(id, 2);
        assertThat(latestSnapshot(id).getHomeSeasonWinRate()).isEqualByComparingTo("0.700");
    }

    @Test
    void sameDatePitcherStatCorrectionRefreshesWithoutIdentityChange() {
        Long id = create(TODAY);
        Long pitcher = addPitcher(id, 2026, "3.20");
        generation.generate(id);
        jdbc.update("update pitcher_stats set era = 2.10 where player_id = ?", pitcher);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertRows(id, 2);
        assertThat(latestSnapshot(id).getHomeStartingPitcherEra()).isEqualByComparingTo("2.10");
    }

    @Test
    void statsArrivingLaterRefreshMissingCoverage() {
        Long id = create(TODAY);
        generation.refreshStale(id);
        assertThat(latestSnapshot(id).getHomeSeasonWinRate()).isNull();
        addTeamStat(2026, "0.650");
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertRows(id, 2);
        assertThat(latestSnapshot(id).getHomeSeasonWinRate()).isEqualByComparingTo("0.650");
    }

    @Test
    void differentSeasonStatsAreMissingAndCurrentSeasonIsSelectedWhenAvailable() {
        Long id = create(TODAY);
        addTeamStat(2025, "0.900");
        Long pitcher = addPitcher(id, 2025, "1.00");
        var before = features.build(id);
        assertThat(before.home().teamStatsAvailable()).isFalse();
        assertThat(before.home().startingPitcher().statsAvailable()).isFalse();
        generation.refreshStale(id);
        addTeamStat(2026, "0.550");
        transaction.executeWithoutResult(tx -> {
            PitcherStat stat = PitcherStat.create(players.findById(pitcher).orElseThrow(), 2026, TODAY.minusDays(1));
            stat.update(new BigDecimal("3.50"), 2, 1, "20", new BigDecimal("1.20"), LocalDateTime.now(clock));
            pitcherStats.saveAndFlush(stat);
        });
        var after = features.build(id);
        assertThat(after.home().seasonWinRate()).isEqualByComparingTo("0.550");
        assertThat(after.home().startingPitcher().era()).isEqualByComparingTo("3.50");
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertRows(id, 2);
    }

    @Test
    void missingPredictionAtCloseIsNotCreated() {
        Long id = create(TODAY);
        setTime(TODAY.atTime(18, 20));
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
        assertThat(generation.generate(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
        assertRows(id, 0);
        verify(engine, never()).predict(any());
    }

    @Test
    void finalHistoryAndPublicPredictionStayFrozenAfterClose() {
        Long id = create(TODAY);
        generation.generate(id);
        setTime(TODAY.atTime(18, 20));
        assertThat(finalization.finalizeClosedGame(id)).isTrue();
        clearInvocations(engine, shadow);
        addTeamStat(2026, "0.900");
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
        assertThat(jdbc.queryForObject("select count(*) from system_prediction_histories where game_id = ? and prediction_stage = 'FINAL'",
                Long.class, id)).isEqualTo(1);
        verify(engine, never()).predict(any());
        verifyNoInteractions(shadow);
        // A schedule edit after FINAL must not reopen the published prediction.
        jdbc.update("update games set prediction_close_at = '2026-10-08 18:20:00' where id = ?", id);
        setTime(TODAY.atTime(12, 0));
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_CLOSED);
    }

    @Test
    void concurrentDailyStartupAndStarterRefreshHaveOneCurrentAndOneInitial() throws Exception {
        Long id = create(TODAY);
        CountDownLatch ready = new CountDownLatch(3), start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<SystemPredictionGenerationStatus>> jobs = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                final int mode = index;
                jobs.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                    return (mode == 0 ? generation.generate(id)
                            : generation.refreshStale(id, mode == 1 ? PredictionRefreshReason.DATA_REFRESH
                                    : PredictionRefreshReason.STARTER_ACQUIRED)).status();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<SystemPredictionGenerationStatus> results = new ArrayList<>();
            for (var job : jobs) results.add(job.get(20, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(SystemPredictionGenerationStatus.CREATED,
                    SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE, SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        } finally { start.countDown(); }
        assertRows(id, 1);
        assertStage(id, PredictionStage.INITIAL);
        verify(engine, times(1)).predict(any());
        verify(shadow, times(1)).generate(any(), any());
    }

    @Test
    void shadowFailureKeepsCommittedPublicPredictionAndOperationalHistory() {
        Long id = create(TODAY);
        doThrow(new IllegalStateException("shadow failed")).when(shadow).generate(any(), any());
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.CREATED);
        assertRows(id, 1);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        assertRows(id, 1);
    }

    @Test
    void concurrentRefreshOfChangedInputWritesExactlyOneNewVersion() throws Exception {
        Long id = create(TODAY);
        Long stat = addTeamStat(2026, "0.500");
        generation.generate(id);
        jdbc.update("update team_stats set win_rate = 0.650 where id = ?", stat);
        clearInvocations(engine, shadow);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<SystemPredictionGenerationStatus> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                return generation.refreshStale(id).status();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(SystemPredictionGenerationStatus.UPDATED,
                            SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        } finally { start.countDown(); }
        assertRows(id, 2);
        verify(engine, times(1)).predict(any());
        verify(shadow, times(1)).generate(any(), any());
    }

    @Test
    void legacySnapshotWithoutHashRefreshesOnceThenSkips() {
        Long id = create(TODAY);
        generation.generate(id);
        jdbc.update("update prediction_feature_snapshots set data_source = 'PredictionFeatureService pregame snapshot' where game_id = ?", id);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.UPDATED);
        assertThat(generation.refreshStale(id).status()).isEqualTo(SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE);
        assertRows(id, 2);
    }

    private PregameDataSyncScheduler scheduler() {
        var scheduler = new PregameDataSyncScheduler(mock(TeamStatsSyncService.class), mock(StartingPitcherSyncService.class), generation, clock);
        ReflectionTestUtils.setField(scheduler, "predictionLookAheadDays", 1);
        return scheduler;
    }

    private void setTime(LocalDateTime time) { when(clock.instant()).thenReturn(time.atZone(ZONE).toInstant()); }

    private Long create(LocalDate date) {
        Long id = transaction.execute(tx -> games.saveAndFlush(Game.createCollected(
                "pr-" + UUID.randomUUID().toString().substring(0, 16), 2026, date, LocalTime.of(18, 30),
                home, away, "잠실", GameStatus.SCHEDULED, null, null, null, null, null,
                LocalDateTime.now(clock))).getId());
        gameIds.add(id);
        return id;
    }

    private Long addTeamStat(int season, String rate) {
        Long id = transaction.execute(tx -> {
            TeamStat stat = TeamStat.create(home, season, TODAY.minusDays(1));
            ReflectionTestUtils.setField(stat, "winRate", new BigDecimal(rate));
            ReflectionTestUtils.setField(stat, "collectedAt", LocalDateTime.now(clock));
            return teamStats.saveAndFlush(stat).getId();
        });
        statIds.add(id);
        return id;
    }

    private Long addPitcher(Long gameId, int season, String era) {
        Long id = transaction.execute(tx -> {
            Game game = games.findById(gameId).orElseThrow();
            Player player = players.saveAndFlush(Player.create("pr" + UUID.randomUUID().toString().substring(0, 12),
                    home, "test pitcher", LocalDateTime.now(clock)));
            var starter = starters.findByGameIdAndSide(gameId, StartingPitcherSide.HOME).orElse(null);
            if (starter == null) starter = StartingPitcher.create(game, home, player, StartingPitcherSide.HOME, LocalDateTime.now(clock));
            else starter.update(home, player, LocalDateTime.now(clock));
            starters.saveAndFlush(starter);
            PitcherStat stat = PitcherStat.create(player, season, TODAY.minusDays(1));
            stat.update(new BigDecimal(era), 2, 1, "20", new BigDecimal("1.20"), LocalDateTime.now(clock));
            pitcherStats.saveAndFlush(stat);
            return player.getId();
        });
        playerIds.add(id);
        return id;
    }

    private PredictionFeatureSnapshot latestSnapshot(Long id) {
        return snapshots.findTopByGameIdAndGenerationMethodOrderByFeatureAsOfDescIdDesc(id,
                PredictionGenerationMethod.OPERATIONAL_PREGAME).orElseThrow();
    }

    private void assertRows(Long id, long versions) {
        assertThat(jdbc.queryForObject("select count(*) from system_predictions where game_id = ?", Long.class, id))
                .isEqualTo(versions == 0 ? 0 : 1);
        assertThat(jdbc.queryForObject("select count(*) from prediction_feature_snapshots where game_id = ?", Long.class, id)).isEqualTo(versions);
        assertThat(jdbc.queryForObject("select count(*) from system_prediction_histories where game_id = ?", Long.class, id)).isEqualTo(versions);
    }

    private void assertStage(Long id, PredictionStage stage) {
        assertThat(jdbc.queryForObject("select prediction_stage from system_prediction_histories where game_id = ? order by id desc limit 1",
                String.class, id)).isEqualTo(stage.name());
    }
}
