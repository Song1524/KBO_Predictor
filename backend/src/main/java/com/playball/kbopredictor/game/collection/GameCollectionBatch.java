package com.playball.kbopredictor.game.collection;

import java.util.List;

public record GameCollectionBatch(
        int sourceRowCount,
        List<CollectedGame> games,
        List<String> errors
) {

    public GameCollectionBatch {
        games = List.copyOf(games);
        errors = List.copyOf(errors);
    }
}
