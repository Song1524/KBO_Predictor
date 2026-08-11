package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;
import com.playball.kbopredictor.prediction.entity.UserPrediction;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserPredictionResponse(
        Long id,

        Long userId,
        String nickname,

        Long gameId,
        LocalDate gameDate,
        String homeTeamName,
        String awayTeamName,

        PredictionOutcome selectedOutcome,

        Integer pointAmount,
        Boolean isCorrect,
        Boolean settled,
        PredictionSettlementStatus settlementStatus,

        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static UserPredictionResponse from(
            UserPrediction prediction
    ) {
        return new UserPredictionResponse(
                prediction.getId(),

                prediction.getUser().getId(),
                prediction.getUser().getNickname(),

                prediction.getGame().getId(),
                prediction.getGame().getGameDate(),
                prediction.getGame().getHomeTeam().getName(),
                prediction.getGame().getAwayTeam().getName(),

                prediction.getSelectedOutcome(),

                prediction.getPointAmount(),
                prediction.getIsCorrect(),
                prediction.getSettled(),
                prediction.getSettlementStatus(),

                prediction.getCreatedAt(),
                prediction.getUpdatedAt()
        );
    }
}
