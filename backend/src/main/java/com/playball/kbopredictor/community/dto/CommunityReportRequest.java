package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CommunityReportRequest(
        @NotNull CommunityReportReason reason,
        @Size(max = 500) String detail
) {
}
