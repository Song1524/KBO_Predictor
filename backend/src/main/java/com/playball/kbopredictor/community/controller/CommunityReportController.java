package com.playball.kbopredictor.community.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.community.dto.CommunityReportRequest;
import com.playball.kbopredictor.community.dto.CommunityReportResponse;
import com.playball.kbopredictor.community.service.CommunityReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/community")
public class CommunityReportController {

    private final CommunityReportService reportService;

    @PostMapping("/posts/{postId}/reports")
    public ResponseEntity<CommunityReportResponse> reportPost(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CommunityReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                reportService.reportPost(
                        authenticatedUser.getUserId(),
                        postId,
                        request
                )
        );
    }

    @PostMapping("/comments/{commentId}/reports")
    public ResponseEntity<CommunityReportResponse> reportComment(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long commentId,
            @Valid @RequestBody CommunityReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                reportService.reportComment(
                        authenticatedUser.getUserId(),
                        commentId,
                        request
                )
        );
    }
}
