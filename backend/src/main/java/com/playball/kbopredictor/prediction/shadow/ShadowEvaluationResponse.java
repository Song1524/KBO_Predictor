package com.playball.kbopredictor.prediction.shadow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ShadowEvaluationResponse(
        String evaluationType,
        LocalDate from,
        LocalDate to,
        String baselineModelVersion,
        String logisticModelVersion,
        String logisticArtifactSha256,
        int baselineEligibleFinalGameCount,
        int logisticEligibleFinalGameCount,
        int commonEvaluatedGameCount,
        int featureSnapshotMismatchCount,
        int nonOperationalSnapshotCount,
        int pregameCutoffViolationCount,
        int artifactMismatchCount,
        Map<String, BigDecimal> actualOutcomeRates,
        ShadowModelMetrics baseline,
        ShadowModelMetrics logistic,
        Map<String, ShadowPairedMetricComparison> pairedMetrics,
        ShadowSampleSizeAssessment sampleSizeAssessment,
        BigDecimal predictedOutcomeAgreementRate,
        int logisticCorrectBaselineWrongCount,
        int baselineCorrectLogisticWrongCount,
        int bothCorrectCount,
        int bothWrongCount,
        ShadowDrawProbabilityMetrics baselineDrawProbabilities,
        ShadowDrawProbabilityMetrics logisticDrawProbabilities
) {
}
