package com.playball.kbopredictor.prediction.entity;

import com.playball.kbopredictor.game.entity.GameResult;

public enum PredictionOutcome {
    HOME_WIN,
    DRAW,
    AWAY_WIN;

    public boolean matches(GameResult result) {
        return result != null && name().equals(result.name());
    }
}
