package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;

import java.time.LocalDateTime;

public record CommunityReportResponse(
        Long id,
        CommunityReportTargetType targetType,
        Long targetId,
        CommunityReportReason reason,
        CommunityReportStatus status,
        LocalDateTime createdAt
) {
}
