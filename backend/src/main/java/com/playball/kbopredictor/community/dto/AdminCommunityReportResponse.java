package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;

import java.time.LocalDateTime;

public record AdminCommunityReportResponse(
        CommunityReportTargetType reportType,
        Long id,
        Long targetId,
        String targetContent,
        boolean contentDeleted,
        Long reporterId,
        String reporterNickname,
        Long authorId,
        String authorNickname,
        CommunityReportReason reason,
        String detail,
        CommunityReportStatus status,
        LocalDateTime createdAt,
        LocalDateTime processedAt,
        Long processedById,
        String processedByNickname
) {
}
