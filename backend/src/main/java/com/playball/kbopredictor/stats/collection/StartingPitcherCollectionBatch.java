package com.playball.kbopredictor.stats.collection;

import java.util.List;

public record StartingPitcherCollectionBatch(
        int sourceGameCount,
        List<CollectedStartingPitcher> pitchers,
        List<String> errors
) {
}
