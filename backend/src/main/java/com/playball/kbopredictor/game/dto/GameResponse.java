package com.playball.kbopredictor.game.dto;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.dto.SystemPredictionResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record GameResponse(
        Long id,
        String externalGameId,
        Integer season,
        LocalDate gameDate,
        LocalTime gameTime,

        Long homeTeamId,
        String homeTeamName,

        Long awayTeamId,
        String awayTeamName,

        String stadium,
        GameStatus status,
        Integer homeScore,
        Integer awayScore,

        Long winnerTeamId,
        GameResult result,
        LocalDateTime predictionCloseAt,
        String cancelReason,
        SystemPredictionResponse aiPrediction,
        GameOddsResponse userOdds
) {

    public static GameResponse from(
            Game game,
            SystemPredictionResponse aiPrediction,
            GameOddsResponse userOdds
    ) {
        return new GameResponse(
                game.getId(),
                game.getExternalGameId(),
                game.getSeason(),
                game.getGameDate(),
                game.getGameTime(),

                game.getHomeTeam().getId(),
                game.getHomeTeam().getName(),

                game.getAwayTeam().getId(),
                game.getAwayTeam().getName(),

                game.getStadium(),
                game.getStatus(),
                game.getHomeScore(),
                game.getAwayScore(),

                game.getWinnerTeam() == null
                        ? null
                        : game.getWinnerTeam().getId(),

                game.getResult(),
                game.getPredictionCloseAt(),
                game.getCancelReason(),
                aiPrediction,
                userOdds
        );
    }
}
