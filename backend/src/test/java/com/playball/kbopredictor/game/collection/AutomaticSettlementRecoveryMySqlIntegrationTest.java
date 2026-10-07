package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.*;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.entity.*;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionRequest;
import com.playball.kbopredictor.prediction.repository.*;
import com.playball.kbopredictor.prediction.service.GameSettlementRecoveryService;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real MySQL + Flyway schema; only the remote KBO boundary and clock are mocked. */
@SpringBootTest
@ActiveProfiles("test")
class AutomaticSettlementRecoveryMySqlIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    @Autowired AutomaticSettlementRecoveryService recovery;
    @Autowired AutomaticSettlementRecoveryProcessor processor;
    @Autowired GameSettlementRecoveryService adminRecovery;
    @Autowired GameRepository games;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired UserPredictionRepository predictions;
    @Autowired GameSettlementRepository settlements;
    @Autowired GameOddsRepository odds;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean GameDataCollector collector;
    @MockitoBean Clock clock;
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach
    void isolatedMySqlOnly() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getURL()).contains("_test")
                    .matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*");
        }
        when(clock.getZone()).thenReturn(ZONE);
        setTime(TODAY.atTime(0, 30));
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        transaction.executeWithoutResult(status -> {
            for (Fixture fixture : fixtures) {
                jdbc.update("delete from point_histories where user_id = ? and reversal_of_id is not null", fixture.userId());
                jdbc.update("delete from point_histories where user_id = ?", fixture.userId());
                jdbc.update("delete from user_predictions where game_id = ?", fixture.gameId());
                jdbc.update("delete from game_settlements where game_id = ?", fixture.gameId());
                jdbc.update("delete from game_odds where game_id = ?", fixture.gameId());
                jdbc.update("delete from games where id = ?", fixture.gameId());
                jdbc.update("delete from users where id = ?", fixture.userId());
            }
        });
    }

    @Test
    void previousDayPendingGameIsSettledThroughScheduler() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(fixture.result(GameStatus.FINISHED, true));
        new AutomaticSettlementRecoveryScheduler(recovery).recoverPendingGames();
        assertPaidOnce(fixture, 1100);
        assertThat(settlements.findFirstByGameIdOrderByRevisionDesc(fixture.gameId()).orElseThrow()
                .getSource()).isEqualTo(GameSettlementSource.AUTOMATIC);
    }

    @Test
    void gameCrossingMidnightRecoversOnNextDay() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.IN_PROGRESS, null, 900);
        publish(fixture.result(GameStatus.IN_PROGRESS, false));
        setTime(TODAY.minusDays(1).atTime(23, 59));
        recovery.recoverPendingGames();
        assertPending(fixture);
        publish(fixture.result(GameStatus.FINISHED, true));
        setTime(TODAY.atTime(0, 30));
        recovery.recoverPendingGames();
        assertPaidOnce(fixture, 1100);
    }

    @Test
    void unconfirmedFinalResultRemainsPendingAndRetries() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.IN_PROGRESS, null, 900);
        publish(fixture.result(GameStatus.FINISHED, false));
        recovery.recoverPendingGames();
        assertPending(fixture);
        publish(fixture.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPaidOnce(fixture, 1100);
    }

    @Test
    void completedGameAndRepeatedSchedulerDoNotPayAgain() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(fixture.result(GameStatus.FINISHED, true));
        var scheduler = new AutomaticSettlementRecoveryScheduler(recovery);
        scheduler.recoverPendingGames();
        scheduler.recoverPendingGames();
        scheduler.recoverPendingGames();
        assertPaidOnce(fixture, 1100);
        verify(collector, times(1)).collect(fixture.date());
    }

    @Test
    void rolledBackManualWaitingGameIsExcludedEvenWithStaleCandidate() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        CollectedGame result = fixture.result(GameStatus.FINISHED, true);
        publish(result);
        recovery.recoverPendingGames();
        adminRecovery.rollback(fixture.gameId(), 1, fixture.userId(), "manual review");
        clearInvocations(collector);
        recovery.recoverPendingGames();
        assertThat(processor.recover(fixture.gameId(), result)).isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verifyNoInteractions(collector);
        assertThat(point(fixture)).isEqualTo(900);
        assertThat(settlements.findFirstByGameIdOrderByRevisionDesc(fixture.gameId()).orElseThrow()
                .getState()).isEqualTo(GameSettlementState.ROLLED_BACK);
        assertThat(predictions.existsByGameIdAndSettledFalse(fixture.gameId())).isTrue();
    }

    @Test
    void knownResultCorrectionRequiresReviewWithoutOverwriting() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.FINISHED, GameResult.AWAY_WIN, 900);
        publish(fixture.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPending(fixture);
        assertThat(games.findById(fixture.gameId()).orElseThrow().getResult()).isEqualTo(GameResult.AWAY_WIN);
    }

    @Test
    void administratorCorrectedRollbackRemainsManualOnly() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        CollectedGame result = fixture.result(GameStatus.FINISHED, true);
        publish(result);
        recovery.recoverPendingGames();
        adminRecovery.rollback(fixture.gameId(), 1, fixture.userId(), "result review");
        adminRecovery.correctResult(fixture.gameId(), fixture.userId(),
                new GameResultCorrectionRequest(1, GameStatus.FINISHED, 2, 5, null, "official correction"));
        clearInvocations(collector);
        recovery.recoverPendingGames();
        assertThat(processor.recover(fixture.gameId(), result)).isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verifyNoInteractions(collector);
        assertThat(games.findById(fixture.gameId()).orElseThrow().getResult()).isEqualTo(GameResult.AWAY_WIN);
        assertThat(point(fixture)).isEqualTo(900);
        assertThat(settlements.countByGameId(fixture.gameId())).isEqualTo(1);
    }

    @Test
    void rollbackWhileHttpIsInFlightIsRecheckedUnderLock() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        CollectedGame result = fixture.result(GameStatus.FINISHED, true);
        doAnswer(invocation -> {
            processor.recover(fixture.gameId(), result);
            adminRecovery.rollback(fixture.gameId(), 1, fixture.userId(), "during collection");
            return new GameCollectionBatch(1, List.of(result), List.of());
        }).when(collector).collect(fixture.date());
        recovery.recoverPendingGames();
        assertThat(point(fixture)).isEqualTo(900);
        assertThat(predictions.existsByGameIdAndSettledFalse(fixture.gameId())).isTrue();
        assertThat(settlements.countByGameId(fixture.gameId())).isEqualTo(1);
    }

    @Test
    void simultaneousSchedulersWithSameCandidateDoNotPayTwice() throws Exception {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        CountDownLatch bothCollecting = new CountDownLatch(2);
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            bothCollecting.countDown();
            if (!bothCollecting.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("collection barrier timeout");
            return new GameCollectionBatch(1, List.of(fixture.result(GameStatus.FINISHED, true)), List.of());
        }).when(collector).collect(fixture.date());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var scheduler = new AutomaticSettlementRecoveryScheduler(recovery);
            Future<?> first = executor.submit(scheduler::recoverPendingGames);
            Future<?> second = executor.submit(scheduler::recoverPendingGames);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        verify(collector, times(2)).collect(fixture.date());
        assertPaidOnce(fixture, 1100);
    }

    @Test
    void settlementFailureRollsBackWholeGameButOtherGameContinuesAndLaterRetries() {
        Fixture failed = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, Integer.MAX_VALUE);
        Fixture good = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(failed.result(GameStatus.FINISHED, true), good.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPending(failed);
        assertThat(games.findById(failed.gameId()).orElseThrow().getStatus()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(point(failed)).isEqualTo(Integer.MAX_VALUE);
        assertPaidOnce(good, 1100);
        jdbc.update("update users set point = 900 where id = ?", failed.userId());
        recovery.recoverPendingGames();
        assertPaidOnce(failed, 1100);
        assertPaidOnce(good, 1100);
    }

    @Test
    void newServiceInstanceAfterRestartUsesDatabaseState() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(fixture.result(GameStatus.FINISHED, true));
        new AutomaticSettlementRecoveryService(games, collector, processor, clock, 14, 100).recoverPendingGames();
        new AutomaticSettlementRecoveryService(games, collector, processor, clock, 14, 100).recoverPendingGames();
        assertPaidOnce(fixture, 1100);
    }

    @Test
    void simultaneousRecoveryOfSameGamePaysOnlyOnceOnMySql() throws Exception {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        CollectedGame result = fixture.result(GameStatus.FINISHED, true);
        // Both workers have the same candidate/result before either commits.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<GameSettlementTriggerResult> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return processor.recover(fixture.gameId(), result);
            };
            Future<GameSettlementTriggerResult> first = executor.submit(task);
            Future<GameSettlementTriggerResult> second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(GameSettlementTriggerResult.SETTLED, GameSettlementTriggerResult.NOT_REQUIRED);
        } finally {
            start.countDown();
        }
        assertPaidOnce(fixture, 1100);
    }

    @Test
    void cancelledPastGameRefundsOnce() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(fixture.result(GameStatus.CANCELLED, false));
        recovery.recoverPendingGames();
        recovery.recoverPendingGames();
        assertPaidOnce(fixture, 1000);
        assertThat(jdbc.queryForObject("select type from point_histories where user_id = ?", String.class,
                fixture.userId())).isEqualTo("GAME_CANCEL_REFUND");
    }

    @Test
    void futureAndOutsideLookbackGamesAreExcluded() {
        Fixture future = create(TODAY.plusDays(1), GameStatus.SCHEDULED, null, 900);
        Fixture laterToday = create(TODAY, GameStatus.SCHEDULED, null, 900);
        Fixture old = create(TODAY.minusDays(15), GameStatus.SCHEDULED, null, 900);
        recovery.recoverPendingGames();
        verifyNoInteractions(collector);
        assertPending(future);
        assertPending(laterToday);
        assertPending(old);
    }

    @Test
    void collectionFailureDoesNotStopOtherDatesAndMissingResultRetries() {
        Fixture failed = create(TODAY.minusDays(2), GameStatus.IN_PROGRESS, null, 900);
        Fixture good = create(TODAY.minusDays(1), GameStatus.IN_PROGRESS, null, 900);
        when(collector.collect(failed.date())).thenThrow(new IllegalStateException("HTTP timeout"));
        publish(good.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPending(failed);
        assertPaidOnce(good, 1100);
        doReturn(new GameCollectionBatch(0, List.of(), List.of())).when(collector).collect(failed.date());
        recovery.recoverPendingGames();
        assertPending(failed);
        publish(failed.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPaidOnce(failed, 1100);
    }

    @Test
    void batchLimitIsAppliedAndNextRunProcessesRemainingGame() {
        Fixture first = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        Fixture second = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        publish(first.result(GameStatus.FINISHED, true), second.result(GameStatus.FINISHED, true));
        var limited = new AutomaticSettlementRecoveryService(games, collector, processor, clock, 14, 1);
        limited.recoverPendingGames();
        assertPaidOnce(first, 1100);
        assertPending(second);
        limited.recoverPendingGames();
        assertPaidOnce(second, 1100);
    }

    @Test
    void legacyGameWithoutExternalIdUsesExactDateTimeAndTeams() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        jdbc.update("update games set external_game_id = null where id = ?", fixture.gameId());
        publish(fixture.result(GameStatus.FINISHED, true));
        recovery.recoverPendingGames();
        assertPaidOnce(fixture, 1100);
        assertThat(games.findById(fixture.gameId()).orElseThrow().getExternalGameId()).isEqualTo(fixture.externalId());
    }

    @Test
    void gameWithoutPendingUserPredictionsIsNotCollected() {
        Fixture fixture = create(TODAY.minusDays(1), GameStatus.SCHEDULED, null, 900);
        jdbc.update("delete from user_predictions where game_id = ?", fixture.gameId());
        recovery.recoverPendingGames();
        verifyNoInteractions(collector);
        assertThat(settlements.countByGameId(fixture.gameId())).isZero();
        assertThat(point(fixture)).isEqualTo(900);
    }

    private void setTime(LocalDateTime now) {
        when(clock.instant()).thenReturn(now.atZone(ZONE).toInstant());
    }

    private Fixture create(LocalDate date, GameStatus status, GameResult result, int balance) {
        Fixture fixture = transaction.execute(tx -> {
            List<Team> seeded = teams.findAll();
            Team home = seeded.get(0), away = seeded.get(1);
            String id = "recovery-" + UUID.randomUUID().toString().substring(0, 16);
            User user = User.createLocal(id + "@example.com", "hash", id, home, LocalDateTime.now(clock));
            user.changePoint(balance);
            users.saveAndFlush(user);
            LocalTime time = LocalTime.of(23, 0).minusMinutes(fixtures.size());
            Game game = Game.createCollected(id, date.getYear(), date, time, home, away, "잠실", status,
                    result == null ? null : 2, result == null ? null : 5,
                    result == null ? null : away, result, null, LocalDateTime.now(clock));
            games.saveAndFlush(game);
            predictions.saveAndFlush(UserPrediction.create(user, game, PredictionOutcome.HOME_WIN, 100));
            GameOdds gameOdds = GameOdds.create(game, LocalDateTime.now(clock));
            gameOdds.finalizeOdds(new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("2.00"),
                    LocalDateTime.now(clock));
            odds.saveAndFlush(gameOdds);
            return new Fixture(game.getId(), user.getId(), id, date, time,
                    home.getKboTeamCode(), away.getKboTeamCode());
        });
        fixtures.add(fixture);
        return fixture;
    }

    private void publish(CollectedGame... results) {
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return new GameCollectionBatch(results.length, List.of(results), List.of());
        }).when(collector).collect(results[0].gameDate());
    }

    private int point(Fixture fixture) {
        return users.findById(fixture.userId()).orElseThrow().getPoint();
    }

    private void assertPending(Fixture fixture) {
        assertThat(predictions.existsByGameIdAndSettledFalse(fixture.gameId())).isTrue();
        assertThat(settlements.countByGameId(fixture.gameId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from point_histories where user_id = ?",
                Long.class, fixture.userId())).isZero();
    }

    private void assertPaidOnce(Fixture fixture, int balance) {
        assertThat(point(fixture)).isEqualTo(balance);
        assertThat(predictions.existsByGameIdAndSettledFalse(fixture.gameId())).isFalse();
        assertThat(settlements.countByGameId(fixture.gameId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from point_histories where user_id = ?",
                Long.class, fixture.userId())).isEqualTo(1);
        assertThat(settlements.findFirstByGameIdOrderByRevisionDesc(fixture.gameId()).orElseThrow()
                .getRevision()).isEqualTo(1);
    }

    private record Fixture(Long gameId, Long userId, String externalId, LocalDate date,
                           LocalTime time, String home, String away) {
        CollectedGame result(GameStatus status, boolean confirmed) {
            return new CollectedGame(externalId, date.getYear(), date, time, away, home, "잠실", status,
                    confirmed ? 2 : null, confirmed ? 5 : null,
                    confirmed ? GameResult.HOME_WIN : null, confirmed,
                    status == GameStatus.CANCELLED ? "rain" : null);
        }
    }
}
