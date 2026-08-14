package com.playball.kbopredictor.ranking;

public enum RankingType {
    TOTAL_POINT,
    MONTHLY_PROFIT,
    WEEKLY_PROFIT;

    public boolean isPeriodRanking() {
        return this != TOTAL_POINT;
    }
}
