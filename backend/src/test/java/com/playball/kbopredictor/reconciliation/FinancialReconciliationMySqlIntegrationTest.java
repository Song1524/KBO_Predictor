package com.playball.kbopredictor.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import com.playball.kbopredictor.prediction.service.OddsCalculator;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.playball.kbopredictor.reconciliation.ReconciliationResult.*;

class FinancialReconciliationMySqlIntegrationTest extends FinancialMySqlHttpTestSupport {
    @Test void detectsBalanceDriftWithoutChangingData() throws Exception {
        jdbc.update("update users set point = point + 1 where id = ?", user.id);
        assertCode(reconciliation.checkUser(user.id), Code.BALANCE_MISMATCH);
        assertUserPoint(user, 1051);
    }
    @Test void missingBetIsReportedByBothUserAndGameScopes() throws Exception {
        predict(user, gameId, "HOME_WIN", 100);
        jdbc.update("delete from point_histories where user_id = ? and type = 'PREDICTION_BET'", user.id);
        assertCode(reconciliation.checkUser(user.id), Code.MISSING_LEDGER);
        assertCode(reconciliation.checkGame(gameId), Code.MISSING_LEDGER);
    }
    @Test void missingRewardIsNotHiddenByASettledPrediction() throws Exception {
        wonScenario();
        jdbc.update("delete from point_histories where user_id = ? and type = 'PREDICTION_REWARD'", user.id);
        assertCode(reconciliation.checkUser(user.id), Code.MISSING_LEDGER);
        assertCode(reconciliation.checkGame(gameId), Code.SETTLEMENT_REVISION_MISMATCH);
    }
    @Test void duplicateSignupLedgerIsDetectedDespiteNullableUniqueKey() {
        jdbc.update("""
                insert into point_histories(user_id, point_change, balance_after, type, description, created_at, settlement_revision)
                select user_id, point_change, balance_after, type, description, created_at, settlement_revision
                from point_histories where user_id = ? and type = 'SIGNUP_BONUS'
                """, user.id);
        assertCode(reconciliation.checkUser(user.id), Code.DUPLICATE_LEDGER);
    }
    @Test void wrongPoolIsDetectedAfterSettlement() throws Exception {
        wonScenario(); jdbc.update("update game_odds set home_win_points = home_win_points + 100 where game_id = ?", gameId);
        assertCode(reconciliation.checkGame(gameId), Code.POOL_MISMATCH);
    }
    @Test void wrongHistoryRevisionIsDetected() throws Exception {
        wonScenario(); jdbc.update("update point_histories set settlement_revision = 7 where user_id = ? and type = 'PREDICTION_REWARD'", user.id);
        assertCode(reconciliation.checkGame(gameId), Code.SETTLEMENT_REVISION_MISMATCH);
    }
    @Test void predictionCannotReferenceAnOlderRolledBackRevision() throws Exception {
        wonScenario(); rollback(); correctAndResettle();
        jdbc.update("update user_predictions set settlement_id = (select id from game_settlements where game_id = ? and revision = 1) where game_id = ? and user_id = ?", gameId, gameId, user.id);
        assertCode(reconciliation.checkUser(user.id), Code.SETTLEMENT_REVISION_MISMATCH);
    }
    @Test void missingReversalIsDetected() throws Exception {
        wonScenario(); rollback();
        jdbc.update("delete from point_histories where user_id = ? and type = 'PREDICTION_REWARD_ROLLBACK'", user.id);
        assertCode(reconciliation.checkGame(gameId), Code.REVERSAL_MISMATCH);
    }
    @Test void wrongReversalAmountAndLinkAreDetected() throws Exception {
        wonScenario(); rollback();
        jdbc.update("update point_histories set point_change = -199 where user_id = ? and type = 'PREDICTION_REWARD_ROLLBACK'", user.id);
        assertCode(reconciliation.checkUser(user.id), Code.REVERSAL_MISMATCH);
        jdbc.update("""
                update point_histories set reversal_of_id = (select id from
                  (select id from point_histories where user_id = ? and type = 'SIGNUP_BONUS') original)
                where user_id = ? and type = 'PREDICTION_REWARD_ROLLBACK'
                """, user.id, user.id);
        assertCode(reconciliation.checkGame(gameId), Code.REVERSAL_MISMATCH);
    }
    @Test void legacyOpeningBalanceIsInferredButNeverCalledFullyVerified() {
        jdbc.update("delete from point_histories where user_id = ? and type = 'SIGNUP_BONUS'", user.id);
        var result = reconciliation.checkUser(user.id);
        assertThat(result.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(result.balance().openingBalance()).isEqualTo(1000);
        assertThat(result.balance().expected()).isEqualTo(1050);
        assertThat(result.balance().signupAnchored()).isFalse();
    }
    @Test void noLedgerMeansUnknownBaselineNotAnInventedZeroBalance() {
        jdbc.update("delete from point_histories where user_id = ?", user.id);
        var result = reconciliation.checkUser(user.id);
        assertThat(result.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(result.balance().openingBalance()).isNull(); assertThat(result.balance().expected()).isNull();
    }
    @Test void perTableLimitReturnsIncompleteInsteadOfPartialSuccess() {
        var bounded = new FinancialReconciliationService(jdbc, new OddsCalculator(new BigDecimal("10.00")), 1);
        var result = bounded.checkUser(user.id);
        assertThat(result.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(result.findings()).extracting(Finding::code).containsExactly(Code.LIMIT_EXCEEDED);
        assertThat(result.balance()).isNull();
    }
    @Test void adminEndpointsAreReadOnlyAndDenyAnonymousAndNormalUsers() throws Exception {
        wonScenario();
        List<List<Map<String, Object>>> before = snapshot();
        api(null, HttpMethod.GET, "/api/admin/reconciliation/users/" + user.id, null, 401);
        api(user, HttpMethod.GET, "/api/admin/reconciliation/games/" + gameId, null, 403);
        api(admin, HttpMethod.GET, "/api/admin/reconciliation/users/9223372036854775807", null, 404);
        assertNormal(); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void invalidFinalOddsProduceFindingsInsteadOfAnInternalError() throws Exception {
        wonScenario(); jdbc.update("update game_odds set final_home_win_odds = 11 where game_id = ?", gameId);
        assertCode(reconciliation.checkGame(gameId), Code.LEDGER_MISMATCH);
        assertCode(reconciliation.checkGame(gameId), Code.POOL_MISMATCH);
    }

    private void wonScenario() throws Exception { betBoth(); finishedFixture(true); sync(); assertNormal(); }
    private void assertCode(ReconciliationResult result, Code code) {
        assertThat(result.status()).withFailMessage(result.toString()).isEqualTo(Status.INCONSISTENT);
        assertThat(result.findings()).extracting(Finding::code).contains(code);
    }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("select * from users where id in (?, ?, ?) order by id", user.id, other.id, admin.id),
                jdbc.queryForList("select * from point_histories where user_id in (?, ?, ?) order by id", user.id, other.id, admin.id),
                jdbc.queryForList("select * from user_predictions where game_id = ? order by id", gameId),
                jdbc.queryForList("select * from game_odds where game_id = ?", gameId),
                jdbc.queryForList("select * from game_settlements where game_id = ? order by id", gameId));
    }
}
