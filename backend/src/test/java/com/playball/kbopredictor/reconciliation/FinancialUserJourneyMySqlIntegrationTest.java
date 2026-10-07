package com.playball.kbopredictor.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FinancialUserJourneyMySqlIntegrationTest extends FinancialMySqlHttpTestSupport {
    @Test void signupLoginPredictionOfficialWinLedgerMyPageAndRankingsStayConsistent() throws Exception {
        var list = get(user, "/api/games?date=" + DATE);
        assertThat(list.size()).isEqualTo(1); assertThat(list.get(0).path("id").asLong()).isEqualTo(gameId);
        betBoth(); assertUserPoint(user, 950); assertUserPoint(other, 950);
        var pool = get(user, "/api/games/" + gameId + "/odds");
        assertThat(pool.path("totalBetPoints").asLong()).isEqualTo(200);
        assertThat(pool.path("homeWin").path("betPoints").asLong()).isEqualTo(100);
        assertThat(pool.path("awayWin").path("betPoints").asLong()).isEqualTo(100);
        assertNormal();
        finishedFixture(true); sync();
        var game = get(user, "/api/games/" + gameId);
        assertThat(game.path("homeScore").asInt()).isEqualTo(4); assertThat(game.path("awayScore").asInt()).isEqualTo(2);
        assertUserPoint(user, 1150); assertPrediction(user, "WON"); assertPeriodProfit(user, 100);
        assertThat(get(user, "/api/rankings?type=TOTAL_POINT").path("myRanking").path("currentPoint").asLong()).isEqualTo(1150);
        var history = get(user, "/api/points/me/history"); assertThat(history.size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select point_change from point_histories where user_id = ? and type = 'PREDICTION_REWARD'", Integer.class, user.id)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select revision from game_settlements where game_id = ? and state = 'SETTLED'", Integer.class, gameId)).isEqualTo(1);
        assertNormal();
    }

    @Test void wrongPredictionKeepsStakeDeductedHasNoRewardAndNegativePeriodProfit() throws Exception {
        betBoth(); finishedFixture(true); sync();
        assertPrediction(other, "LOST"); assertUserPoint(other, 950); assertPeriodProfit(other, -100);
        assertThat(get(other, "/api/points/me/history").size()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from point_histories where user_id = ? and type in ('PREDICTION_REWARD','GAME_CANCEL_REFUND')", Long.class, other.id)).isZero();
        assertNormal();
    }

    @Test void cancellationRefundsWholeStakeExactlyOnceAndHasZeroNetProfit() throws Exception {
        betBoth(); cancelledFixture(); sync(); sync();
        api(admin, HttpMethod.POST, "/api/admin/games/" + gameId + "/settlement", null, 200);
        assertPrediction(user, "REFUNDED"); assertPrediction(other, "REFUNDED");
        assertUserPoint(user, 1050); assertUserPoint(other, 1050);
        assertThat(jdbc.queryForObject("select sum(point_change) from point_histories where user_id = ? and user_prediction_id is not null", Long.class, user.id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from point_histories where game_id = ? and type = 'GAME_CANCEL_REFUND'", Long.class, gameId)).isEqualTo(2);
        // Refund-only users are intentionally excluded from period rankings (HAVING graded count > 0).
        assertThat(get(user, "/api/rankings?type=WEEKLY_PROFIT").path("myRanking").isNull()).isTrue();
        assertThat(get(user, "/api/games/" + gameId + "/odds").path("totalBetPoints").asLong()).isEqualTo(200);
        assertNormal();
    }

    @Test void rollbackCorrectionAndNewRevisionKeepHistoryReversalsBalanceAndCurrentRanking() throws Exception {
        betBoth(); finishedFixture(true); sync(); rollback();
        assertPrediction(user, "PENDING"); assertUserPoint(user, 950); assertUserPoint(other, 950);
        assertThat(get(user, "/api/rankings?type=WEEKLY_PROFIT").path("myRanking").isNull()).isTrue();
        assertNormal();
        correctAndResettle();
        assertPrediction(user, "LOST"); assertPrediction(other, "WON");
        assertUserPoint(user, 950); assertUserPoint(other, 1150);
        assertPeriodProfit(user, -100); assertPeriodProfit(other, 100);
        assertThat(jdbc.queryForList("select revision, state from game_settlements where game_id = ? order by revision", gameId))
                .containsExactly(Map.of("revision", 1, "state", "ROLLED_BACK"), Map.of("revision", 2, "state", "SETTLED"));
        assertThat(jdbc.queryForObject("select count(*) from point_histories r join point_histories o on o.id = r.reversal_of_id where r.game_id = ? and r.point_change = -o.point_change and r.settlement_id = o.settlement_id", Long.class, gameId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(point_change) from point_histories where user_id = ? and user_prediction_id is not null", Long.class, user.id)).isEqualTo(-100);
        assertNormal();
    }
}
