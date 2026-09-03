package com.playball.kbopredictor.community;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.community.controller.AdminCommunityReportController;
import com.playball.kbopredictor.community.controller.CommunityReportController;
import com.playball.kbopredictor.community.dto.AdminCommunityReportProcessResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityReportRequest;
import com.playball.kbopredictor.community.dto.CommunityReportResponse;
import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;
import com.playball.kbopredictor.community.exception.CommunityReportException;
import com.playball.kbopredictor.community.service.CommunityReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        CommunityReportController.class,
        AdminCommunityReportController.class
})
@Import(SecurityConfig.class)
class CommunityReportSecurityWebTest {

    private static final Long USER_ID = 7L;
    private static final Long POST_ID = 11L;
    private static final Long COMMENT_ID = 19L;
    private static final Long REPORT_ID = 23L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityReportService reportService;
    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @Test
    void anonymousUserCannotReportPostOrComment() throws Exception {
        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                        "/api/community/comments/{commentId}/reports",
                        COMMENT_ID
                )
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    void reportMutationRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(user(authenticatedUser("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    void duplicateReportReturnsConflictInsteadOfFallingThroughErrorDispatch() throws Exception {
        when(reportService.reportPost(
                eq(USER_ID),
                eq(POST_ID),
                any(CommunityReportRequest.class)
        )).thenThrow(new CommunityReportException(
                HttpStatus.CONFLICT,
                "이미 신고한 콘텐츠입니다."
        ));

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 신고한 콘텐츠입니다."));
    }

    @Test
    void authenticatedUserCanReportPostAndComment() throws Exception {
        when(reportService.reportPost(
                eq(USER_ID),
                eq(POST_ID),
                any(CommunityReportRequest.class)
        )).thenReturn(reportResponse(
                CommunityReportTargetType.POST,
                POST_ID
        ));
        when(reportService.reportComment(
                eq(USER_ID),
                eq(COMMENT_ID),
                any(CommunityReportRequest.class)
        )).thenReturn(reportResponse(
                CommunityReportTargetType.COMMENT,
                COMMENT_ID
        ));

        mockMvc.perform(post("/api/community/posts/{postId}/reports", POST_ID)
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("POST"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post(
                        "/api/community/comments/{commentId}/reports",
                        COMMENT_ID
                )
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("COMMENT"));
    }

    @Test
    void userCannotAccessAdminReportApis() throws Exception {
        mockMvc.perform(get("/api/admin/community/reports")
                        .with(user(authenticatedUser("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(
                        "/api/admin/community/reports/POST/{reportId}",
                        REPORT_ID
                )
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    void adminCanListAndProcessReports() throws Exception {
        when(reportService.getReports(null, 0, 20)).thenReturn(
                new CommunityPageResponse<>(
                        List.of(), 0, 20, 0, 0, true, true
                )
        );
        when(reportService.processReport(
                USER_ID,
                CommunityReportTargetType.POST,
                REPORT_ID,
                CommunityReportStatus.RESOLVED
        )).thenReturn(new AdminCommunityReportProcessResponse(
                CommunityReportTargetType.POST,
                REPORT_ID,
                CommunityReportStatus.RESOLVED,
                LocalDateTime.of(2026, 9, 3, 12, 0),
                USER_ID,
                "admin"
        ));

        mockMvc.perform(get("/api/admin/community/reports")
                        .with(user(authenticatedUser("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(patch(
                        "/api/admin/community/reports/POST/{reportId}",
                        REPORT_ID
                )
                        .with(user(authenticatedUser("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.processedById").value(USER_ID));
    }

    @Test
    void adminReportProcessingRequiresCsrf() throws Exception {
        mockMvc.perform(patch(
                        "/api/admin/community/reports/POST/{reportId}",
                        REPORT_ID
                )
                        .with(user(authenticatedUser("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    private AuthenticatedUser authenticatedUser(String authority) {
        return new AuthenticatedUser(
                USER_ID,
                "user@example.com",
                "password",
                true,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private CommunityReportResponse reportResponse(
            CommunityReportTargetType targetType,
            Long targetId
    ) {
        return new CommunityReportResponse(
                REPORT_ID,
                targetType,
                targetId,
                CommunityReportReason.ABUSE,
                CommunityReportStatus.PENDING,
                LocalDateTime.of(2026, 9, 3, 11, 0)
        );
    }

    private String reportJson() {
        return """
                {"reason": "ABUSE", "detail": null}
                """;
    }

    private String processJson() {
        return """
                {"status": "RESOLVED"}
                """;
    }
}
