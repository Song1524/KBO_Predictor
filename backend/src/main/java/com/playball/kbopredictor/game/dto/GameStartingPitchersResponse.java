package com.playball.kbopredictor.game.dto;

public record GameStartingPitchersResponse(
        Long gameId,
        StartingPitcherDetailResponse home,
        StartingPitcherDetailResponse away
) {
}
