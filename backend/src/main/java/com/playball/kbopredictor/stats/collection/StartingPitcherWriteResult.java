package com.playball.kbopredictor.stats.collection;

public record StartingPitcherWriteResult(
        boolean inserted,
        boolean pitcherStatSaved
) {
}
