package com.playball.kbopredictor.game.collection;

import java.util.List;
import java.util.Map;

public record OfficialFinalScoreBatch(
        Map<String, OfficialFinalScore> scoresByExternalGameId,
        Map<String, OfficialGameState> statesByExternalGameId,
        List<String> errors
) {

    public OfficialFinalScoreBatch(
            Map<String, OfficialFinalScore> scoresByExternalGameId,
            List<String> errors
    ) {
        this(scoresByExternalGameId, Map.of(), errors);
    }
}
