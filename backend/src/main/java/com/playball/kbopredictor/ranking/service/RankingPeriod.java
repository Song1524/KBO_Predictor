package com.playball.kbopredictor.ranking.service;

import java.time.ZonedDateTime;

public record RankingPeriod(
        ZonedDateTime start,
        ZonedDateTime endExclusive
) {
}
