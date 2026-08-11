package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

public record ModelProbabilities(
        double home,
        double draw,
        double away,
        PredictionOutcome predictedOutcome
) {

    public static ModelProbabilities from(PredictionEngineResult result) {
        return new ModelProbabilities(
                result.homeWinProbability().doubleValue() / 100.0,
                result.drawProbability().doubleValue() / 100.0,
                result.awayWinProbability().doubleValue() / 100.0,
                result.predictedOutcome()
        );
    }

    public static ModelProbabilities of(
            double home,
            double draw,
            double away,
            PredictionOutcome forcedOutcome
    ) {
        double total = home + draw + away;
        if (total <= 0.0) {
            throw new IllegalArgumentException("Probability sum must be positive.");
        }
        return new ModelProbabilities(
                home / total,
                draw / total,
                away / total,
                forcedOutcome == null ? max(home, draw, away) : forcedOutcome
        );
    }

    private static PredictionOutcome max(double home, double draw, double away) {
        if (draw > home && draw >= away) {
            return PredictionOutcome.DRAW;
        }
        return home >= away
                ? PredictionOutcome.HOME_WIN
                : PredictionOutcome.AWAY_WIN;
    }
}
