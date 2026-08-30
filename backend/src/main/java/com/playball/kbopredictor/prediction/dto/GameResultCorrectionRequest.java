package com.playball.kbopredictor.prediction.dto;

import com.playball.kbopredictor.game.entity.GameStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GameResultCorrectionRequest(
        @Positive(message = "정산 회차는 1 이상이어야 합니다.")
        int settlementRevision,

        @NotNull(message = "경기 상태는 필수입니다.")
        GameStatus status,

        Integer homeScore,
        Integer awayScore,

        @Size(max = 255, message = "취소 사유는 255자 이하여야 합니다.")
        String cancelReason,

        @NotBlank(message = "결과 정정 사유는 필수입니다.")
        @Size(max = 255, message = "결과 정정 사유는 255자 이하여야 합니다.")
        String reason
) {
}
