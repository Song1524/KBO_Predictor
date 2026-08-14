package com.playball.kbopredictor.ranking.service;

import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.ranking.RankingType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

@Component
@RequiredArgsConstructor
public class RankingPeriodCalculator {

    private final Clock clock;

    public RankingPeriod current(RankingType type) {
        if (!type.isPeriodRanking()) {
            throw new IllegalArgumentException("기간 랭킹 유형이 아닙니다.");
        }

        ZonedDateTime now = ZonedDateTime.now(clock)
                .withZoneSameInstant(TimeConfig.KOREA_ZONE);
        LocalDate today = now.toLocalDate();
        LocalDate startDate = switch (type) {
            case MONTHLY_PROFIT -> today.withDayOfMonth(1);
            case WEEKLY_PROFIT -> today.with(
                    TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            );
            case TOTAL_POINT -> throw new IllegalArgumentException(
                    "기간 랭킹 유형이 아닙니다."
            );
        };
        LocalDate endDate = switch (type) {
            case MONTHLY_PROFIT -> startDate.plusMonths(1);
            case WEEKLY_PROFIT -> startDate.plusWeeks(1);
            case TOTAL_POINT -> throw new IllegalArgumentException(
                    "기간 랭킹 유형이 아닙니다."
            );
        };

        return new RankingPeriod(
                startDate.atStartOfDay(TimeConfig.KOREA_ZONE),
                endDate.atStartOfDay(TimeConfig.KOREA_ZONE)
        );
    }
}
