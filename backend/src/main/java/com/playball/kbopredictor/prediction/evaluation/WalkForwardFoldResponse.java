package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;

import java.time.LocalDate;
import java.util.List;

public record WalkForwardFoldResponse(
        LocalDate trainingFrom,
        LocalDate trainingTo,
        int trainingGameCount,
        LocalDate evaluationFrom,
        LocalDate evaluationTo,
        int evaluationGameCount,
        int candidateCount,
        BaselineV2Parameters selectedParameters,
        List<ModelEvaluationMetrics> metrics
) {
    public WalkForwardFoldResponse {
        metrics = List.copyOf(metrics);
    }
}
