package com.playball.kbopredictor.stats.collection;

import java.util.List;

public record StartingPitcherPollResult(
        StartingPitcherSyncResponse syncResponse,
        List<Long> newlyCompletedGameIds,
        List<MissingStartingPitcherGame> missingAtClose
) {
}
