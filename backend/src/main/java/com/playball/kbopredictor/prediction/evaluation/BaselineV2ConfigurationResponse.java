package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.BaselineV2ModelProperties;
import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BaselineV2ConfigurationResponse(
        String modelVersion,
        LocalDate trainingFrom,
        LocalDate trainingTo,
        LocalDate validationFrom,
        LocalDate validationTo,
        String objective,
        int searchCandidateCount,
        long searchSeed,
        BigDecimal objectiveScore,
        BaselineV2Parameters parameters,
        boolean operationalExtraFeaturesEnabled
) {
    public static BaselineV2ConfigurationResponse from(
            BaselineV2ModelProperties value
    ) {
        return new BaselineV2ConfigurationResponse(
                value.getModelVersion(),
                value.getTrainingFrom(),
                value.getTrainingTo(),
                value.getValidationFrom(),
                value.getValidationTo(),
                value.getObjective(),
                value.getSearchCandidateCount(),
                value.getSearchSeed(),
                BigDecimal.valueOf(value.getObjectiveScore()),
                value.toParameters(),
                value.isOperationalExtraFeaturesEnabled()
        );
    }
}
