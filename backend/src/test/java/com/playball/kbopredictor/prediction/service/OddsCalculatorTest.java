package com.playball.kbopredictor.prediction.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OddsCalculatorTest {

    private final OddsCalculator calculator =
            new OddsCalculator(new BigDecimal("10.00"));

    @Test
    void calculatesOddsAndRatesIndependentlyFromAiProbability() {
        long total = 100_000;

        assertThat(calculator.calculateOdds(total, 60_000))
                .isEqualByComparingTo("1.67");
        assertThat(calculator.calculateOdds(total, 10_000))
                .isEqualByComparingTo("10.00");
        assertThat(calculator.calculateOdds(total, 30_000))
                .isEqualByComparingTo("3.33");

        assertThat(calculator.calculateBettingRate(total, 60_000))
                .isEqualByComparingTo("60.00");
        assertThat(calculator.calculateBettingRate(total, 10_000))
                .isEqualByComparingTo("10.00");
        assertThat(calculator.calculateBettingRate(total, 30_000))
                .isEqualByComparingTo("30.00");
    }

    @Test
    void usesMaxOddsWhenNobodySelectedAnOutcome() {
        assertThat(calculator.calculateOdds(100_000, 0))
                .isEqualByComparingTo("10.00");
        assertThat(calculator.calculateOdds(0, 0))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void calculatesPayoutFromFinalOdds() {
        assertThat(calculator.calculatePayout(100, new BigDecimal("6.50")))
                .isEqualTo(650);
    }
}
