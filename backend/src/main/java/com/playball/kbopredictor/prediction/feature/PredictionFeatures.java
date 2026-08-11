package com.playball.kbopredictor.prediction.feature;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PredictionFeatures(
        Long gameId,
        LocalDate gameDate,
        LocalDateTime gameStartAt,
        TeamPredictionFeatures home,
        TeamPredictionFeatures away
) {
}
