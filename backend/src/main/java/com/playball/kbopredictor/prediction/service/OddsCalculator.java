package com.playball.kbopredictor.prediction.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OddsCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final BigDecimal maxOdds;

    public OddsCalculator(
            @Value("${app.odds.max:10.00}") BigDecimal maxOdds
    ) {
        this.maxOdds = maxOdds.setScale(2, RoundingMode.HALF_UP);
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
        BigDecimal payout = BigDecimal.valueOf(pointAmount)
                .multiply(finalOdds)
                .setScale(0, RoundingMode.DOWN);

        if (payout.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalStateException("지급 포인트가 허용 범위를 초과했습니다.");
        }
        return payout.intValueExact();
    }
}
