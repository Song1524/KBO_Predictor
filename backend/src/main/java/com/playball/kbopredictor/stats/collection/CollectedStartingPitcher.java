package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.StartingPitcherSide;

public record CollectedStartingPitcher(
        String externalGameId,
        String teamCode,
        StartingPitcherSide side,
        String kboPlayerId,
        String playerName,
        Integer season,
        CollectedPitcherSeasonStat seasonStat
) {
}
