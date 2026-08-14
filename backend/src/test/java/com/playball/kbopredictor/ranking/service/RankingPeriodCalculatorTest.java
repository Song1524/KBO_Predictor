package com.playball.kbopredictor.ranking.service;

import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.ranking.RankingType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RankingPeriodCalculatorTest {

    @Test
    void calculatesCurrentMonthAndWeekInAsiaSeoul() {
        RankingPeriodCalculator calculator = calculator(
                "2026-08-13T06:30:00Z"
        );

        RankingPeriod month = calculator.current(RankingType.MONTHLY_PROFIT);
        RankingPeriod week = calculator.current(RankingType.WEEKLY_PROFIT);

        assertThat(month.start()).hasToString("2026-08-01T00:00+09:00[Asia/Seoul]");
        assertThat(month.endExclusive()).hasToString("2026-09-01T00:00+09:00[Asia/Seoul]");
        assertThat(week.start()).hasToString("2026-08-10T00:00+09:00[Asia/Seoul]");
        assertThat(week.endExclusive()).hasToString("2026-08-17T00:00+09:00[Asia/Seoul]");
    }

    @Test
    void mondayMidnightStartsANewWeekInAsiaSeoul() {
        RankingPeriod beforeMonday = calculator("2026-08-16T14:59:59Z")
                .current(RankingType.WEEKLY_PROFIT);
        RankingPeriod atMonday = calculator("2026-08-16T15:00:00Z")
                .current(RankingType.WEEKLY_PROFIT);

        assertThat(beforeMonday.start().toLocalDate())
                .hasToString("2026-08-10");
        assertThat(atMonday.start().toLocalDate())
                .hasToString("2026-08-17");
    }

    private RankingPeriodCalculator calculator(String instant) {
        return new RankingPeriodCalculator(Clock.fixed(
                Instant.parse(instant),
                TimeConfig.KOREA_ZONE
        ));
    }
}
