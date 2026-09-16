package com.playball.kbopredictor.prediction.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OddsCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal INTEGER_MAX =
            BigDecimal.valueOf(Integer.MAX_VALUE);

    private final BigDecimal maxOdds;

    public OddsCalculator(
            @Value("${app.odds.max:10.00}") BigDecimal maxOdds
    ) {
        this.maxOdds = maxOdds.setScale(2, RoundingMode.HALF_UP);
        if (this.maxOdds.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum odds must be greater than zero."
            );
        }
    }

    public BigDecimal calculateOdds(long totalPoints, long outcomePoints) {
        if (totalPoints <= 0 || outcomePoints <= 0) {
            return maxOdds;
        }

        BigDecimal calculated = BigDecimal.valueOf(totalPoints)
                .divide(BigDecimal.valueOf(outcomePoints), 2, RoundingMode.HALF_UP);

        return calculated.min(maxOdds).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateBettingRate(long totalPoints, long outcomePoints) {
        if (totalPoints <= 0 || outcomePoints <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }

        return BigDecimal.valueOf(outcomePoints)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalPoints), 2, RoundingMode.HALF_UP);
    }

    public int calculatePayout(int pointAmount, BigDecimal finalOdds) {
        if (pointAmount <= 0) {
            throw new IllegalArgumentException(
                    "Prediction points must be greater than zero."
            );
        }
        if (finalOdds == null || finalOdds.signum() <= 0
                || finalOdds.compareTo(maxOdds) > 0) {
            throw new IllegalStateException(
                    "최종 배당이 허용 범위를 벗어났습니다."
            );
        }
        BigDecimal payout = BigDecimal.valueOf(pointAmount)
                .multiply(finalOdds)
                .setScale(0, RoundingMode.DOWN);

        if (payout.compareTo(INTEGER_MAX) > 0) {
            throw new IllegalStateException("지급 포인트가 허용 범위를 초과했습니다.");
        }
        return payout.intValueExact();
    }

    public int calculateMaximumPayout(int pointAmount) {
        return calculatePayout(pointAmount, maxOdds);
    }

    public int maxSafePointAmount(int pointUnit) {
        if (pointUnit <= 0) {
            throw new IllegalArgumentException(
                    "Prediction point unit must be greater than zero."
            );
        }

        BigDecimal pointLimit = INTEGER_MAX
                .divide(maxOdds, 0, RoundingMode.DOWN)
                .min(INTEGER_MAX);
        return pointLimit
                .divide(BigDecimal.valueOf(pointUnit), 0, RoundingMode.DOWN)
                .multiply(BigDecimal.valueOf(pointUnit))
                .intValueExact();
    }
}
