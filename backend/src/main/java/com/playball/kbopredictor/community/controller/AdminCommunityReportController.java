package com.playball.kbopredictor.community.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.community.dto.AdminCommunityReportProcessRequest;
import com.playball.kbopredictor.community.dto.AdminCommunityReportProcessResponse;
import com.playball.kbopredictor.community.dto.AdminCommunityReportResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;
import com.playball.kbopredictor.community.service.CommunityReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/admin/community/reports")
public class AdminCommunityReportController {

    private final CommunityReportService reportService;

    @GetMapping
    public ResponseEntity<CommunityPageResponse<AdminCommunityReportResponse>>
    getReports(
            @RequestParam(required = false) CommunityReportStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ResponseEntity.ok(reportService.getReports(status, page, size));
    }

    @PatchMapping("/{reportType}/{reportId}")
    public ResponseEntity<AdminCommunityReportProcessResponse> processReport(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable CommunityReportTargetType reportType,
            @PathVariable @Positive Long reportId,
            @Valid @RequestBody AdminCommunityReportProcessRequest request
    ) {
        return ResponseEntity.ok(reportService.processReport(
                authenticatedUser.getUserId(),
                reportType,
                reportId,
                request.status()
        ));
    }
}
