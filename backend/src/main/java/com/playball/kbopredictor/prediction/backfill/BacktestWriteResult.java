package com.playball.kbopredictor.prediction.backfill;

public record BacktestWriteResult(
        Long gameId,
        boolean snapshotCreated,
        boolean historyCreated
) {
}
