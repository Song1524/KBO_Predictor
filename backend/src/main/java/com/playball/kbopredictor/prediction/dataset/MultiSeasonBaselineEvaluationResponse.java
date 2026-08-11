package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.prediction.evaluation.ModelEvaluationMetrics;

import java.time.LocalDate;
import java.util.List;

public record MultiSeasonBaselineEvaluationResponse(
        LocalDate from,
        LocalDate to,
        int snapshotCount,
        int evaluatedGameCount,
        int excludedForNoFeaturesCount,
        String probabilisticBenchmarkPolicy,
        List<SeasonEvaluation> seasons,
        SeasonEvaluation overall
) {

    public record SeasonEvaluation(
            String label,
            Integer season,
            int sampleCount,
            List<ModelEvaluationMetrics> models
    ) {
    }
}
