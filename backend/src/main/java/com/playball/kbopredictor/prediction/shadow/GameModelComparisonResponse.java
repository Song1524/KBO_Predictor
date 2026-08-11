package com.playball.kbopredictor.prediction.shadow;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;

import java.time.LocalDate;
import java.time.LocalTime;

public record GameModelComparisonResponse(
        Long gameId,
        LocalDate gameDate,
        LocalTime gameTime,
        String homeTeamName,
        String awayTeamName,
        GameStatus gameStatus,
        GameResult actualResult,
        boolean sameFeatureSnapshot,
        ModelPredictionView baseline,
        ModelPredictionView logistic
) {
}
