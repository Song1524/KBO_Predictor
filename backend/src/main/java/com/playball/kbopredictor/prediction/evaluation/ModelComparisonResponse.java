package com.playball.kbopredictor.prediction.evaluation;

import java.time.LocalDate;
import java.util.List;

public record ModelComparisonResponse(
        String activeModel,
        String productionRecommendation,
        BaselineV1ConfigurationResponse baselineV1Configuration,
        BaselineV2ConfigurationResponse baselineV2Configuration,
        LocalDate trainingFrom,
        LocalDate trainingTo,
        int trainingGameCount,
        LocalDate validationFrom,
        LocalDate validationTo,
        int validationGameCount,
        List<ModelEvaluationMetrics> trainingMetrics,
        List<ModelEvaluationMetrics> validationMetrics,
        DrawSignalAnalysisResponse drawAnalysis,
        List<WalkForwardFoldResponse> walkForward
) {
    public ModelComparisonResponse {
        trainingMetrics = List.copyOf(trainingMetrics);
        validationMetrics = List.copyOf(validationMetrics);
        walkForward = List.copyOf(walkForward);
    }
}
