package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import jakarta.validation.constraints.NotNull;

public record AdminCommunityReportProcessRequest(
        @NotNull CommunityReportStatus status
) {
}
