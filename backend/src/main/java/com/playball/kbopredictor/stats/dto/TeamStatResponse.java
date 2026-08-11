package com.playball.kbopredictor.stats.dto;

import com.playball.kbopredictor.stats.entity.TeamStat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TeamStatResponse(
        Long id,

        Long teamId,
        String teamName,

        Integer season,
        LocalDate statDate,

        Integer wins,
        Integer losses,
        Integer draws,
        BigDecimal winRate,

        Integer recent10Wins,
        Integer recent10Losses,
        Integer recent10Draws,

        Integer homeWins,
        Integer homeLosses,
        Integer homeDraws,
        Integer awayWins,
        Integer awayLosses,
        Integer awayDraws,

        BigDecimal recent5WinRate,
        BigDecimal recent10WinRate,
        BigDecimal recent5AvgRuns,
        BigDecimal recent5AvgRunsAllowed,
        BigDecimal recent10AvgRuns,
        BigDecimal recent10AvgRunsAllowed,

        BigDecimal battingAverage,
        BigDecimal era,

        LocalDateTime collectedAt
) {

    public static TeamStatResponse from(TeamStat teamStat) {
        return new TeamStatResponse(
                teamStat.getId(),

                teamStat.getTeam().getId(),
                teamStat.getTeam().getName(),

                teamStat.getSeason(),
                teamStat.getStatDate(),

                teamStat.getWins(),
                teamStat.getLosses(),
                teamStat.getDraws(),
                teamStat.getWinRate(),

                teamStat.getRecent10Wins(),
                teamStat.getRecent10Losses(),
                teamStat.getRecent10Draws(),

                teamStat.getHomeWins(),
                teamStat.getHomeLosses(),
                teamStat.getHomeDraws(),
                teamStat.getAwayWins(),
                teamStat.getAwayLosses(),
                teamStat.getAwayDraws(),

                teamStat.getRecent5WinRate(),
                teamStat.getRecent10WinRate(),
                teamStat.getRecent5AvgRuns(),
                teamStat.getRecent5AvgRunsAllowed(),
                teamStat.getRecent10AvgRuns(),
                teamStat.getRecent10AvgRunsAllowed(),

                teamStat.getBattingAverage(),
                teamStat.getEra(),

                teamStat.getCollectedAt()
        );
    }
}
