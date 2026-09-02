package com.playball.kbopredictor.game.dto;

import com.playball.kbopredictor.stats.entity.PitcherStat;
import com.playball.kbopredictor.stats.entity.StartingPitcher;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StartingPitcherDetailResponse(
        Long playerId,
        String playerName,
        boolean statsAvailable,
        Integer season,
        LocalDate statDate,
        BigDecimal era,
        Integer wins,
        Integer losses,
        String innings,
        BigDecimal whip
) {

    public static StartingPitcherDetailResponse from(
            StartingPitcher startingPitcher,
            PitcherStat stat
    ) {
        return new StartingPitcherDetailResponse(
                startingPitcher.getPlayer().getId(),
                startingPitcher.getPlayer().getName(),
                stat != null,
                stat == null ? null : stat.getSeason(),
                stat == null ? null : stat.getStatDate(),
                stat == null ? null : stat.getEra(),
                stat == null ? null : stat.getWins(),
                stat == null ? null : stat.getLosses(),
                stat == null ? null : stat.getInnings(),
                stat == null ? null : stat.getWhip()
        );
    }
}
