package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record SettlementCorrectionRequest(
        @NotNull Long expectedUserId,
        @NotBlank String expectedExternalGameId,
        @NotNull PredictionOutcome expectedOutcome,
        @NotNull @Positive Integer expectedPointAmount,
        @NotNull @DecimalMin("0.01") BigDecimal expectedFinalOdds,
        @NotNull @PositiveOrZero Integer expectedCurrentPoint
) {
}
