package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;

import java.util.List;

public record HistoricalPredictionFeatures(
        PredictionFeatures features,
        int homeHistoricalGameCount,
        int awayHistoricalGameCount,
        int homeSeasonWins,
        int homeSeasonLosses,
        int homeSeasonDraws,
        int awaySeasonWins,
        int awaySeasonLosses,
        int awaySeasonDraws,
        PredictionGenerationMethod generationMethod,
        String dataSource,
        List<String> missingFeatures
) {

    public HistoricalPredictionFeatures {
        missingFeatures = List.copyOf(missingFeatures);
    }
}
