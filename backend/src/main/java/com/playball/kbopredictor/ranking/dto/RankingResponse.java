package com.playball.kbopredictor.ranking.dto;

import com.playball.kbopredictor.ranking.RankingType;

import java.time.ZonedDateTime;
import java.util.List;

public record RankingResponse(
        RankingType type,
        ZonedDateTime periodStart,
        ZonedDateTime periodEndExclusive,
        List<RankingEntryResponse> rankings,
        RankingEntryResponse myRanking
) {
}
