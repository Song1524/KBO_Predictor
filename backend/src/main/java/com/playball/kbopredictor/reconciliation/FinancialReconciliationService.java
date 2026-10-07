package com.playball.kbopredictor.reconciliation;

import com.playball.kbopredictor.prediction.service.OddsCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static com.playball.kbopredictor.reconciliation.ReconciliationResult.*;

/** Bounded, non-locking checks of one user's ledger or one game's financial state. No repair path. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
public class FinancialReconciliationService {
    private static final String PREDICTIONS = """
            select p.*, s.game_id as settlement_game_id, s.revision as linked_revision,
                   s.state as linked_state, s.game_status as settled_game_status, s.game_result as settled_game_result,
                   (select max(latest.revision) from game_settlements latest where latest.game_id = p.game_id) as latest_revision,
                   o.finalized, o.final_home_win_odds, o.final_draw_odds, o.final_away_win_odds
            from user_predictions p left join game_settlements s on s.id = p.settlement_id
            left join game_odds o on o.game_id = p.game_id
            """;
    private static final String HISTORIES = """
            select h.*, s.game_id as settlement_game_id, s.revision as linked_revision, s.state as linked_state,
                   p.user_id as prediction_user_id, p.game_id as prediction_game_id
            from point_histories h left join game_settlements s on s.id = h.settlement_id
            left join user_predictions p on p.id = h.user_prediction_id
            """;
    private static final Set<String> PAYMENTS = Set.of("PREDICTION_REWARD", "GAME_CANCEL_REFUND");
    private static final Set<String> REVERSALS = Set.of("PREDICTION_REWARD_ROLLBACK", "GAME_CANCEL_REFUND_ROLLBACK");
    private final JdbcTemplate jdbc;
    private final OddsCalculator odds;
    private final int maxRows;

    public FinancialReconciliationService(JdbcTemplate jdbc, OddsCalculator odds,
            @Value("${app.reconciliation.max-rows:1000}") int maxRows) {
        if (maxRows < 1 || maxRows > 10000) throw new IllegalArgumentException("Reconciliation max-rows must be 1..10000");
        this.jdbc = jdbc; this.odds = odds; this.maxRows = maxRows;
    }

    public ReconciliationResult checkUser(long userId) {
        var user = one("select id, point from users where id = ?", userId);
        if (tooLarge("point_histories", "user_id", userId) || tooLarge("user_predictions", "user_id", userId)) return incomplete("USER", userId);
        var histories = load(HISTORIES + " where h.user_id = ? order by h.id", userId);
        var predictions = load(PREDICTIONS + " where p.user_id = ?", userId);
        if (exceeded(histories, predictions)) return incomplete("USER", userId);
        List<Finding> findings = new ArrayList<>();
        boolean anchored = !histories.isEmpty() && text(histories.getFirst(), "type").equals("SIGNUP_BONUS");
        Long opening = histories.isEmpty() ? null : number(histories.getFirst(), "balance_after") - number(histories.getFirst(), "point_change");
        if (!anchored) add(findings, Code.BASELINE_UNVERIFIED, "user", userId,
                "No opening signup ledger: balance_before_first is inferred; missing pre-ledger activity cannot be proven.");
        if (anchored && opening != 0) add(findings, Code.LEDGER_MISMATCH, "user", userId, "Signup opening balance must be zero.");
        Long expected = opening;
        for (var history : histories) {
            expected = Math.addExact(expected, number(history, "point_change"));
            if (expected != number(history, "balance_after")) add(findings, Code.BALANCE_MISMATCH,
                    "history", number(history, "id"), "Ledger balance chain does not match cumulative changes.");
        }
        if (expected != null && expected != number(user, "point")) add(findings, Code.BALANCE_MISMATCH,
                "user", userId, "Stored balance differs from opening balance plus all ledger changes.");
        checkHistories(histories, findings);
        checkPredictions(predictions, histories, findings);
        return result("USER", userId, new BalanceEvidence(number(user, "point"), opening, expected, anchored, histories.size()), findings);
    }

    public ReconciliationResult checkGame(long gameId) {
        var game = one("select id, status, result, home_score, away_score from games where id = ?", gameId);
        if (tooLarge("point_histories", "game_id", gameId) || tooLarge("user_predictions", "game_id", gameId)
                || tooLarge("game_settlements", "game_id", gameId)) return incomplete("GAME", gameId);
        var predictions = load(PREDICTIONS + " where p.game_id = ?", gameId);
        var histories = load(HISTORIES + " where h.game_id = ? order by h.id", gameId);
        var settlements = load("select * from game_settlements where game_id = ? order by revision", gameId);
        if (exceeded(predictions, histories, settlements)) return incomplete("GAME", gameId);
        List<Finding> findings = new ArrayList<>();
        checkHistories(histories, findings);
        checkPredictions(predictions, histories, findings);
        checkPool(gameId, predictions, findings);
        checkSettlements(game, settlements, predictions, histories, findings);
        return result("GAME", gameId, null, findings);
    }

    private void checkHistories(List<Map<String, Object>> histories, List<Finding> findings) {
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        histories.forEach(h -> byId.put(number(h, "id"), h));
        Set<String> unique = new HashSet<>(); Set<Long> reversedIds = new HashSet<>();
        for (var h : histories) {
            long id = number(h, "id"); String type = text(h, "type"); Long prediction = nullableNumber(h, "user_prediction_id");
            String key = prediction != null ? "prediction:" + prediction + ":" + type + ":" + number(h, "settlement_revision")
                    : type.equals("SIGNUP_BONUS") ? "signup:" + number(h, "user_id")
                    : type.equals("DAILY_LOGIN_BONUS") ? "daily:" + number(h, "user_id") + ":" + h.get("bonus_date") : null;
            if (key != null && !unique.add(key)) add(findings, Code.DUPLICATE_LEDGER, "history", id, "Duplicate logical ledger key.");
            if (prediction != null && (!Objects.equals(h.get("user_id"), h.get("prediction_user_id"))
                    || !Objects.equals(h.get("game_id"), h.get("prediction_game_id"))))
                add(findings, Code.LEDGER_MISMATCH, "history", id, "Prediction/user/game linkage differs.");
            if (type.equals("PREDICTION_BET")) {
                if (prediction == null || h.get("settlement_id") != null || number(h, "settlement_revision") != 0
                        || number(h, "point_change") >= 0 || h.get("reversal_of_id") != null)
                    add(findings, Code.LEDGER_MISMATCH, "history", id, "Bet must be negative, prediction-linked and revision zero.");
            } else if (PAYMENTS.contains(type) || REVERSALS.contains(type)) {
                if (prediction == null || h.get("settlement_id") == null
                        || !Objects.equals(h.get("game_id"), h.get("settlement_game_id"))
                        || !Objects.equals(nullableNumber(h, "settlement_revision"), nullableNumber(h, "linked_revision")))
                    add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "history", id, "Settlement linkage/revision differs.");
                if (PAYMENTS.contains(type) && (number(h, "point_change") <= 0 || h.get("reversal_of_id") != null))
                    add(findings, Code.LEDGER_MISMATCH, "history", id, "Payment must be positive and cannot reverse another entry.");
            } else if (type.equals("SIGNUP_BONUS") || type.equals("DAILY_LOGIN_BONUS")) {
                if (number(h, "point_change") <= 0 || prediction != null || h.get("game_id") != null
                        || h.get("settlement_id") != null || number(h, "settlement_revision") != 0 || h.get("reversal_of_id") != null
                        || (type.equals("DAILY_LOGIN_BONUS") && h.get("bonus_date") == null))
                    add(findings, Code.LEDGER_MISMATCH, "history", id, "Invalid signup/daily bonus linkage or amount.");
            } else add(findings, Code.LEDGER_MISMATCH, "history", id, "Unknown ledger type.");
            if (REVERSALS.contains(type)) {
                Long originalId = nullableNumber(h, "reversal_of_id");
                var original = byId.get(originalId);
                if (originalId == null || !reversedIds.add(originalId) || original == null
                        || !type.equals(text(original, "type") + "_ROLLBACK")
                        || number(h, "point_change") != -number(original, "point_change")
                        || !Objects.equals(h.get("user_id"), original.get("user_id"))
                        || !Objects.equals(prediction, nullableNumber(original, "user_prediction_id"))
                        || !Objects.equals(h.get("settlement_id"), original.get("settlement_id"))
                        || number(h, "settlement_revision") != number(original, "settlement_revision")
                        || !text(h, "linked_state").equals("ROLLED_BACK"))
                    add(findings, Code.REVERSAL_MISMATCH, "history", id, "Reversal must uniquely negate its matching rolled-back payment.");
            } else if (h.get("reversal_of_id") != null) add(findings, Code.REVERSAL_MISMATCH, "history", id, "Unexpected reversal link.");
        }
        for (var h : histories) if (PAYMENTS.contains(text(h, "type"))) {
            boolean reversed = reversedIds.contains(number(h, "id"));
            if (text(h, "linked_state").equals("ROLLED_BACK") != reversed)
                add(findings, Code.REVERSAL_MISMATCH, "history", number(h, "id"), "Rolled-back payment needs one reversal; active payment needs none.");
        }
    }

    private void checkPredictions(List<Map<String, Object>> predictions, List<Map<String, Object>> histories, List<Finding> findings) {
        Map<Long, List<Map<String, Object>>> byPrediction = new HashMap<>();
        for (var h : histories) if (h.get("user_prediction_id") != null)
            byPrediction.computeIfAbsent(number(h, "user_prediction_id"), ignored -> new ArrayList<>()).add(h);
        for (var p : predictions) {
            long id = number(p, "id"); var entries = byPrediction.getOrDefault(id, List.of());
            var bets = entries.stream().filter(h -> text(h, "type").equals("PREDICTION_BET")).toList();
            if (bets.isEmpty()) add(findings, Code.MISSING_LEDGER, "prediction", id, "Bet ledger missing.");
            else if (bets.size() != 1) add(findings, Code.DUPLICATE_LEDGER, "prediction", id, "Exactly one bet is required.");
            else if (number(bets.getFirst(), "point_change") != -number(p, "point_amount"))
                add(findings, Code.LEDGER_MISMATCH, "prediction", id, "Bet amount differs from stake.");
            String status = text(p, "settlement_status"); boolean settled = bool(p, "settled");
            if (!settled) {
                if (!status.equals("PENDING") || p.get("settlement_id") != null || p.get("settled_at") != null || p.get("is_correct") != null)
                    add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "prediction", id, "Pending prediction retains settlement state.");
                if (entries.stream().anyMatch(h -> PAYMENTS.contains(text(h, "type")) && text(h, "linked_state").equals("SETTLED")))
                    add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "prediction", id, "Pending prediction has active payment.");
                continue;
            }
            if (p.get("settlement_id") == null || p.get("settled_at") == null || !text(p, "linked_state").equals("SETTLED")
                    || !Objects.equals(p.get("game_id"), p.get("settlement_game_id"))
                    || !Objects.equals(nullableNumber(p, "linked_revision"), nullableNumber(p, "latest_revision"))) {
                add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "prediction", id, "Prediction must reference the current active settlement.");
                continue;
            }
            String expectedStatus = text(p, "settled_game_status").equals("CANCELLED") ? "REFUNDED"
                    : text(p, "selected_outcome").equals(text(p, "settled_game_result")) ? "WON" : "LOST";
            Boolean expectedCorrect = expectedStatus.equals("REFUNDED") ? null : expectedStatus.equals("WON");
            if (!status.equals(expectedStatus) || !Objects.equals(nullableBool(p, "is_correct"), expectedCorrect))
                add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "prediction", id, "Prediction grade contradicts settlement result.");
            var payments = entries.stream().filter(h -> PAYMENTS.contains(text(h, "type"))
                    && Objects.equals(h.get("settlement_id"), p.get("settlement_id"))).toList();
            if (expectedStatus.equals("LOST")) {
                if (!payments.isEmpty()) add(findings, Code.LEDGER_MISMATCH, "prediction", id, "Lost prediction has payment.");
            } else if (payments.isEmpty()) add(findings, Code.MISSING_LEDGER, "prediction", id, "Current payout/refund ledger missing.");
            else if (payments.size() != 1) add(findings, Code.DUPLICATE_LEDGER, "prediction", id, "Multiple current payments.");
            else {
                String type = expectedStatus.equals("WON") ? "PREDICTION_REWARD" : "GAME_CANCEL_REFUND";
                try {
                    long amount = number(p, "point_amount");
                    if (expectedStatus.equals("WON")) {
                        String column = switch (text(p, "selected_outcome")) {
                            case "HOME_WIN" -> "final_home_win_odds"; case "DRAW" -> "final_draw_odds"; default -> "final_away_win_odds";
                        };
                        if (!bool(p, "finalized")) throw new IllegalArgumentException("Final odds unavailable");
                        amount = odds.calculatePayout(Math.toIntExact(amount), (BigDecimal) p.get(column));
                    }
                    if (!text(payments.getFirst(), "type").equals(type) || number(payments.getFirst(), "point_change") != amount)
                        add(findings, Code.LEDGER_MISMATCH, "prediction", id, "Current payment type/amount differs from final odds or stake.");
                } catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
                    add(findings, Code.LEDGER_MISMATCH, "prediction", id, "Final odds/stake cannot produce a valid payout.");
                }
            }
        }
    }

    private void checkPool(long gameId, List<Map<String, Object>> predictions, List<Finding> findings) {
        var rows = jdbc.queryForList("select * from game_odds where game_id = ?", gameId);
        if (rows.isEmpty()) {
            if (!predictions.isEmpty()) add(findings, Code.POOL_MISMATCH, "game", gameId, "Predictions exist without odds pool.");
            return;
        }
        var pool = rows.getFirst(); long total = 0;
        Map<String, Long> expected = new HashMap<>();
        for (var p : predictions) { long stake = number(p, "point_amount"); total = Math.addExact(total, stake); expected.merge(text(p, "selected_outcome"), stake, Math::addExact); }
        for (String outcome : List.of("HOME_WIN", "DRAW", "AWAY_WIN")) {
            String prefix = switch (outcome) { case "HOME_WIN" -> "home_win"; case "DRAW" -> "draw"; default -> "away_win"; };
            long value = expected.getOrDefault(outcome, 0L);
            if (number(pool, prefix + "_points") != value) add(findings, Code.POOL_MISMATCH, "game", gameId, outcome + " pool differs from cumulative stakes (including refunded/rolled-back predictions).");
            if (bool(pool, "finalized") && !Objects.equals(pool.get("final_" + prefix + "_odds"), odds.calculateOdds(total, value)))
                add(findings, Code.POOL_MISMATCH, "game", gameId, outcome + " final odds differ from the retained pool.");
        }
    }

    private void checkSettlements(Map<String, Object> game, List<Map<String, Object>> settlements,
            List<Map<String, Object>> predictions, List<Map<String, Object>> histories, List<Finding> findings) {
        int revision = 1; long activeCount = settlements.stream().filter(s -> text(s, "state").equals("SETTLED")).count();
        if (activeCount > 1) add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "game", number(game, "id"), "Multiple active settlements.");
        for (var s : settlements) {
            long id = number(s, "id"); boolean active = text(s, "state").equals("SETTLED");
            if (number(s, "revision") != revision++ || (active && s != settlements.getLast()))
                add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Revisions must be contiguous and only the latest may be active.");
            long expectedRefunds = text(s, "game_status").equals("CANCELLED") ? predictions.size() : 0;
            long expectedWins = expectedRefunds == 0 ? predictions.stream()
                    .filter(p -> text(p, "selected_outcome").equals(text(s, "game_result"))).count() : 0;
            if (number(s, "prediction_count") != predictions.size() || number(s, "correct_count") != expectedWins
                    || number(s, "refunded_count") != expectedRefunds
                    || number(s, "incorrect_count") != predictions.size() - expectedWins - expectedRefunds)
                add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Historical grade counts contradict the immutable stakes and this revision's result.");
            var payments = histories.stream().filter(h -> Objects.equals(h.get("settlement_id"), s.get("id")) && PAYMENTS.contains(text(h, "type"))).toList();
            long paid = payments.stream().mapToLong(h -> number(h, "point_change")).sum();
            long reversed = -histories.stream().filter(h -> Objects.equals(h.get("settlement_id"), s.get("id")) && REVERSALS.contains(text(h, "type"))).mapToLong(h -> number(h, "point_change")).sum();
            if (paid != number(s, "total_paid_points") || number(s, "correct_count") + number(s, "incorrect_count") + number(s, "refunded_count") != number(s, "prediction_count")
                    || payments.stream().filter(h -> text(h, "type").equals("PREDICTION_REWARD")).count() != number(s, "correct_count")
                    || payments.stream().filter(h -> text(h, "type").equals("GAME_CANCEL_REFUND")).count() != number(s, "refunded_count"))
                add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Settlement totals/counts differ from payment ledgers.");
            if (active) {
                if (predictions.stream().filter(p -> Objects.equals(p.get("settlement_id"), s.get("id")) && bool(p, "settled")).count() != number(s, "prediction_count")
                        || number(s, "prediction_count") != predictions.size()
                        || !Objects.equals(game.get("status"), s.get("game_status")) || !Objects.equals(game.get("result"), s.get("game_result"))
                        || !Objects.equals(game.get("home_score"), s.get("home_score")) || !Objects.equals(game.get("away_score"), s.get("away_score")))
                    add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Active settlement differs from predictions or current game result.");
                if (reversed != 0 || number(s, "reversed_point_total") != 0 || s.get("rolled_back_at") != null)
                    add(findings, Code.REVERSAL_MISMATCH, "settlement", id, "Active settlement has rollback evidence.");
            } else if (text(s, "state").equals("ROLLED_BACK")) {
                if (paid != reversed || number(s, "reversed_point_total") != reversed || s.get("rolled_back_at") == null
                        || predictions.stream().anyMatch(p -> Objects.equals(p.get("settlement_id"), s.get("id"))))
                    add(findings, Code.REVERSAL_MISMATCH, "settlement", id, "Rolled-back settlement must be fully reversed and detached.");
                if (s == settlements.getLast() && s.get("result_corrected_at") != null
                        && (!Objects.equals(game.get("status"), s.get("corrected_game_status"))
                        || !Objects.equals(game.get("result"), s.get("corrected_game_result"))
                        || !Objects.equals(game.get("home_score"), s.get("corrected_home_score"))
                        || !Objects.equals(game.get("away_score"), s.get("corrected_away_score"))))
                    add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Current game differs from the admin correction awaiting resettlement.");
            } else add(findings, Code.SETTLEMENT_REVISION_MISMATCH, "settlement", id, "Unknown settlement state.");
        }
    }

    private List<Map<String, Object>> load(String sql, long id) { return jdbc.queryForList(sql + " limit ?", id, maxRows + 1); }
    private boolean tooLarge(String table, String column, long id) {
        // Fixed internal identifiers only; indexed preflight prevents sorting a huge scoped ledger.
        return jdbc.queryForList("select id from " + table + " where " + column + " = ? limit ?", id, maxRows + 1).size() > maxRows;
    }
    @SafeVarargs private boolean exceeded(List<Map<String, Object>>... lists) { return Arrays.stream(lists).anyMatch(l -> l.size() > maxRows); }
    private Map<String, Object> one(String sql, long id) {
        var rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "점검 대상을 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private ReconciliationResult incomplete(String scope, long id) {
        return new ReconciliationResult(scope, id, Status.INCOMPLETE, null,
                List.of(new Finding(Code.LIMIT_EXCEEDED, scope, id, "Per-table row limit exceeded; no partial correctness verdict.")));
    }
    private ReconciliationResult result(String scope, long id, BalanceEvidence balance, List<Finding> findings) {
        Status status = findings.stream().anyMatch(f -> f.code() != Code.BASELINE_UNVERIFIED) ? Status.INCONSISTENT
                : findings.isEmpty() ? Status.NORMAL : Status.UNVERIFIED;
        return new ReconciliationResult(scope, id, status, balance, List.copyOf(findings));
    }
    private static void add(List<Finding> findings, Code code, String entity, long id, String detail) { findings.add(new Finding(code, entity, id, detail)); }
    private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long nullableNumber(Map<String, Object> row, String key) { return row.get(key) == null ? null : number(row, key); }
    private static Boolean nullableBool(Map<String, Object> row, String key) { return row.get(key) == null ? null : bool(row, key); }
    private static boolean bool(Map<String, Object> row, String key) {
        var value = row.get(key); return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
