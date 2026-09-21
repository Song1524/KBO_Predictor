package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.StartingPitcherSide;

import java.time.LocalDateTime;
import java.util.List;

public record MissingStartingPitcherGame(
        Long gameId,
        String externalGameId,
        LocalDateTime predictionCloseAt,
        List<StartingPitcherSide> missingSides
) {
}
