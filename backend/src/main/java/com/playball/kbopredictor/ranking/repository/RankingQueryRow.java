package com.playball.kbopredictor.ranking.repository;

public record RankingQueryRow(
        long rank,
        long userId,
        String nickname,
        long score,
        long predictionCount,
        long correctCount,
        long gradedPredictionCount
) {
}
