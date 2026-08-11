package com.playball.kbopredictor.point.dto;

import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;

import java.time.LocalDateTime;

public record PointHistoryResponse(
        Long id,
        PointHistoryType type,
        int pointChange,
        int balanceAfter,
        Long gameId,
        Long userPredictionId,
        String description,
        LocalDateTime createdAt
) {
    public static PointHistoryResponse from(PointHistory history) {
        return new PointHistoryResponse(
                history.getId(),
                history.getType(),
                history.getPointChange(),
                history.getBalanceAfter(),
                history.getGame() == null ? null : history.getGame().getId(),
                history.getUserPrediction() == null
                        ? null
                        : history.getUserPrediction().getId(),
                history.getDescription(),
                history.getCreatedAt()
        );
    }
}
