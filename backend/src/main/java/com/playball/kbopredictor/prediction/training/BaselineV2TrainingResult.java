package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.evaluation.ModelEvaluationMetrics;

import java.time.LocalDate;

public record BaselineV2TrainingResult(
        LocalDate trainingFrom,
        LocalDate trainingTo,
        int trainingGameCount,
        int homeWinCount,
        int drawCount,
        int awayWinCount,
        String objective,
        long searchSeed,
        BaselineV2OptimizationResult optimization,
        ModelEvaluationMetrics trainingMetrics
) {
}
