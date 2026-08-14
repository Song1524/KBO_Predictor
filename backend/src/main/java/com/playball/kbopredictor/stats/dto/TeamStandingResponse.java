package com.playball.kbopredictor.stats.dto;

import com.playball.kbopredictor.stats.entity.TeamStat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TeamStandingResponse(
        Integer rank,
        Long teamId,
        String teamName,
        Integer games,
        Integer wins,
        Integer losses,
        Integer draws,
        BigDecimal winRate,
        BigDecimal gamesBehind,
        String streak,
        LocalDate statDate,
        LocalDateTime collectedAt
) {
    public static TeamStandingResponse from(TeamStat stat) {
        return new TeamStandingResponse(
                stat.getOfficialRank(),
                stat.getTeam().getId(),
                stat.getTeam().getName(),
                stat.getGamesPlayed(),
                stat.getWins(),
                stat.getLosses(),
                stat.getDraws(),
                stat.getWinRate(),
                stat.getGamesBehind(),
                stat.getStreak(),
                stat.getStatDate(),
                stat.getCollectedAt()
        );
    }
}
