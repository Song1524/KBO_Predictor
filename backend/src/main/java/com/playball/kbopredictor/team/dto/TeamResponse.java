package com.playball.kbopredictor.team.dto;

import com.playball.kbopredictor.team.entity.Team;

public record TeamResponse(
        Long id,
        String kboTeamCode,
        String name,
        String shortName,
        String primaryColor,
        String secondaryColor
) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(
                team.getId(),
                team.getKboTeamCode(),
                team.getName(),
                team.getShortName(),
                team.getPrimaryColor(),
                team.getSecondaryColor()
        );
    }
}
