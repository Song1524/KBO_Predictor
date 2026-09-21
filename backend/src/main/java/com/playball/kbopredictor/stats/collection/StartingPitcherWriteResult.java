package com.playball.kbopredictor.stats.collection;

public record StartingPitcherWriteResult(
        boolean inserted,
        boolean pitcherStatSaved,
        boolean playerChanged,
        Long gameId,
        com.playball.kbopredictor.stats.entity.StartingPitcherSide side
) {
    public StartingPitcherWriteResult(
            boolean inserted,
            boolean pitcherStatSaved
    ) {
        this(inserted, pitcherStatSaved, false, null, null);
    }
}
