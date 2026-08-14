package com.playball.kbopredictor.ranking.dto;

import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.repository.RankingQueryRow;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record RankingEntryResponse(
        long rank,
        long userId,
        String nickname,
        Long currentPoint,
        Long periodProfit,
        long predictionCount,
        long correctCount,
        BigDecimal hitRate
) {
    public static RankingEntryResponse from(
            RankingQueryRow row,
            RankingType type
    ) {
        BigDecimal hitRate = row.gradedPredictionCount() == 0
                ? null
                : BigDecimal.valueOf(row.correctCount())
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(row.gradedPredictionCount()),
                        1,
                        RoundingMode.HALF_UP
                );
        return new RankingEntryResponse(
                row.rank(),
                row.userId(),
                row.nickname(),
                type == RankingType.TOTAL_POINT ? row.score() : null,
                type.isPeriodRanking() ? row.score() : null,
                row.predictionCount(),
                row.correctCount(),
                hitRate
        );
    }
}
