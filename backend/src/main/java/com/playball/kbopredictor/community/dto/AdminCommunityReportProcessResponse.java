package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;

import java.time.LocalDateTime;

public record AdminCommunityReportProcessResponse(
        CommunityReportTargetType reportType,
        Long id,
        CommunityReportStatus status,
        LocalDateTime processedAt,
        Long processedById,
        String processedByNickname
) {
}
