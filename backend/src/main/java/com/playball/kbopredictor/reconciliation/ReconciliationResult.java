package com.playball.kbopredictor.reconciliation;

import java.util.List;

public record ReconciliationResult(
        String scope, long id, Status status, BalanceEvidence balance, List<Finding> findings
) {
    public enum Status { NORMAL, INCONSISTENT, UNVERIFIED, INCOMPLETE }
    public enum Code {
        BALANCE_MISMATCH, MISSING_LEDGER, DUPLICATE_LEDGER, LEDGER_MISMATCH,
        POOL_MISMATCH, SETTLEMENT_REVISION_MISMATCH, REVERSAL_MISMATCH,
        BASELINE_UNVERIFIED, LIMIT_EXCEEDED
    }
    public record Finding(Code code, String entity, long entityId, String detail) {}
    public record BalanceEvidence(long current, Long openingBalance, Long expected, boolean signupAnchored, int ledgerRows) {}
}
