package com.playball.kbopredictor.reconciliation;

import com.playball.kbopredictor.game.collection.GameDataCollector;
import com.playball.kbopredictor.game.collection.GameUpsertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class FinancialConcurrencyMySqlIntegrationTest extends FinancialMySqlHttpTestSupport {
    @Autowired GameDataCollector collector;
    @Autowired GameUpsertService upsert;

    @Test void sameUserPredictionsOnDifferentGamesKeepBothDebits() throws Exception {
        long secondGame = secondGame();
        overlapping(() -> predict(user, gameId, "HOME_WIN", 200), () -> predict(user, secondGame, "HOME_WIN", 300));
        assertUserPoint(user, 550);
        assertThat(get(user, "/api/user-predictions/me").size()).isEqualTo(2);
        assertThat(reconciliation.checkUser(user.id).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
        assertThat(reconciliation.checkGame(gameId).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
        assertThat(reconciliation.checkGame(secondGame).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
    }
    @Test void sameUserConcurrentSpendingCannotOverdrawBalance() throws Exception {
        long secondGame = secondGame();
        overlapping(() -> predict(user, gameId, "HOME_WIN", 700),
                () -> api(user, HttpMethod.POST, "/api/user-predictions", Map.of("gameId", secondGame, "selectedOutcome", "HOME_WIN", "pointAmount", 700), 400));
        assertUserPoint(user, 350);
        assertThat(get(user, "/api/user-predictions/me").size()).isEqualTo(1);
        assertThat(reconciliation.checkUser(user.id).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
    }
    @Test void sameGameDuplicatePredictionMakesExactlyOneBetAndLedger() throws Exception {
        overlapping(() -> predict(user, gameId, "HOME_WIN", 100),
                () -> api(user, HttpMethod.POST, "/api/user-predictions", Map.of("gameId", gameId, "selectedOutcome", "AWAY_WIN", "pointAmount", 100), 409));
        assertUserPoint(user, 950);
        assertThat(jdbc.queryForObject("select count(*) from user_predictions where game_id = ?", Long.class, gameId)).isEqualTo(1);
        assertThat(get(user, "/api/games/" + gameId + "/odds").path("totalBetPoints").asLong()).isEqualTo(100);
        assertNormal();
    }
    @Test void simultaneousInitialSettlementPaysOnlyOnce() throws Exception {
        betBoth(); terminalWithoutSettlement();
        overlapping(this::settle, this::settle);
        assertUserPoint(user, 1150);
        assertThat(jdbc.queryForObject("select count(*) from game_settlements where game_id = ?", Long.class, gameId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from point_histories where game_id = ? and type = 'PREDICTION_REWARD'", Long.class, gameId)).isEqualTo(1);
        assertNormal();
    }
    @Test void simultaneousRollbackReversesExactlyOnce() throws Exception {
        betBoth(); finishedFixture(true); sync();
        overlapping(this::rollback, this::rollback);
        assertUserPoint(user, 950);
        assertThat(jdbc.queryForObject("select count(*) from point_histories where game_id = ? and reversal_of_id is not null", Long.class, gameId)).isEqualTo(1);
        assertNormal();
    }
    @Test void settlementThenCompetingRollbackLeavesOneFullyReversedRevision() throws Exception {
        betBoth(); terminalWithoutSettlement();
        overlapping(this::settle, this::rollback);
        assertUserPoint(user, 950); assertPrediction(user, "PENDING"); assertNormal();
    }
    @Test void rollbackThenCompetingSettlementCannotBypassCorrectionRequirement() throws Exception {
        betBoth(); finishedFixture(true); sync();
        overlapping(this::rollback, () -> api(admin, HttpMethod.POST, "/api/admin/games/" + gameId + "/settlement", null, 409));
        assertUserPoint(user, 950); assertPrediction(user, "PENDING"); assertNormal();
    }
    @Test void concurrentSettlementCreditAndAnotherGameBetDoNotLoseEitherChange() throws Exception {
        long secondGame = secondGame(); betBoth(); terminalWithoutSettlement();
        overlapping(this::settle, () -> predict(user, secondGame, "HOME_WIN", 100));
        assertUserPoint(user, 1050);
        assertThat(reconciliation.checkUser(user.id).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
        assertThat(reconciliation.checkGame(gameId).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
        assertThat(reconciliation.checkGame(secondGame).status()).isEqualTo(ReconciliationResult.Status.NORMAL);
    }
    @Test void readOnlyReconciliationDoesNotWaitOnUserWriteLockAndSeesCommittedSnapshot() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> transactions.executeWithoutResult(status -> {
                try { predict(user, gameId, "HOME_WIN", 100); entered.countDown(); await(release); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var reader = executor.submit(() -> reconciliation.checkUser(user.id));
                var result = reader.get(3, TimeUnit.SECONDS);
                assertThat(result.status()).withFailMessage(result.toString()).isEqualTo(ReconciliationResult.Status.NORMAL);
                assertThat(result.balance().current()).isEqualTo(1050);
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertUserPoint(user, 950); assertNormal();
    }

    private long secondGame() throws Exception {
        scheduledFixture(2); sync();
        long id = jdbc.queryForObject("select id from games where external_game_id = '20990612LGOB1'", Long.class);
        ownGames.add(id); return id;
    }
    private void terminalWithoutSettlement() {
        finishedFixture(true); upsert.upsert(collector.collect(DATE).games().getFirst());
    }
    private void settle() throws Exception { api(admin, HttpMethod.POST, "/api/admin/games/" + gameId + "/settlement", null, 200); }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private void overlapping(Operation first, Operation second) throws Exception {
        var holding = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> transactions.executeWithoutResult(status -> {
                try { first.run(); holding.countDown(); await(release); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }));
            try {
                assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
                var b = executor.submit(() -> { second.run(); return null; });
                boolean waiting = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !b.isDone()) {
                    if (jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits", Long.class) > 0) { waiting = true; break; }
                    Thread.sleep(20);
                }
                assertThat(waiting).as("second HTTP request must wait on an actual MySQL lock").isTrue();
                release.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
    }
}
