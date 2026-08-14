package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public record UserPredictionRequest(
        @NotNull(message = "경기 ID는 필수입니다.")
        Long gameId,

        @NotNull(message = "예측 결과 선택은 필수입니다.")
        PredictionOutcome selectedOutcome,

        @NotNull(message = "사용 포인트는 필수입니다.")
        @Min(value = 100, message = "사용 포인트는 최소 100P입니다.")
        Integer pointAmount
) {
}
