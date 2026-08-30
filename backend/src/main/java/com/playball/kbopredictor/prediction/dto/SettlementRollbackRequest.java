package com.playball.kbopredictor.prediction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SettlementRollbackRequest(
        @Positive(message = "정산 회차는 1 이상이어야 합니다.")
        int settlementRevision,

        @NotBlank(message = "rollback 사유는 필수입니다.")
        @Size(max = 255, message = "rollback 사유는 255자 이하여야 합니다.")
        String reason
) {
}
