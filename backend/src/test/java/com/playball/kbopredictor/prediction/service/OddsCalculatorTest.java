package com.playball.kbopredictor.prediction.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void derivesSafePredictionLimitFromMaximumOddsAndPointUnit() {
        assertThat(calculator.maxSafePointAmount(100))
                .isEqualTo(214_748_300);
        assertThat(calculator.calculateMaximumPayout(214_748_300))
                .isEqualTo(2_147_483_000);
    }

    @Test
    void rejectsPayoutThatExceedsIntegerRange() {
        assertThatThrownBy(() -> calculator.calculatePayout(
                214_748_400,
                new BigDecimal("10.00")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("지급 포인트가 허용 범위를 초과");
    }

    @Test
    void rejectsFinalOddsAboveConfiguredMaximum() {
        assertThatThrownBy(() -> calculator.calculatePayout(
                100,
                new BigDecimal("10.01")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최종 배당이 허용 범위");
    }
}
