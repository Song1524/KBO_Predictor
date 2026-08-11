package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;

import java.math.BigDecimal;

public record OutcomeOddsResponse(
        PredictionOutcome outcome,
        long betPoints,
        BigDecimal userBettingRate,
        BigDecimal odds
) {
}
