package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShadowEvaluationResponse(
        LocalDate from,
        LocalDate to,
        int commonEvaluatedGameCount,
        int featureSnapshotMismatchCount,
        int artifactMismatchCount,
        ShadowModelMetrics baseline,
        ShadowModelMetrics logistic,
        BigDecimal predictedOutcomeAgreementRate,
        int logisticCorrectBaselineWrongCount,
        int baselineCorrectLogisticWrongCount,
        int bothCorrectCount,
        int bothWrongCount,
        ShadowDrawProbabilityMetrics baselineDrawProbabilities,
        ShadowDrawProbabilityMetrics logisticDrawProbabilities
) {
}
