package com.playball.kbopredictor.community;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.community.controller.CommunityController;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.service.CommunityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommunityController.class)
@Import(SecurityConfig.class)
class CommunitySecurityWebTest {

    private static final Long USER_ID = 7L;
    private static final Long POST_ID = 11L;
    private static final Long COMMENT_ID = 19L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityService communityService;
    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @Test
    void anonymousUserCanReadPostsAndComments() throws Exception {
        when(communityService.getPosts(0, 15)).thenReturn(
                new CommunityPageResponse<>(
                        List.of(), 0, 15, 0, 0, true, true
                )
        );
        when(communityService.getPost(POST_ID)).thenReturn(postResponse());
        when(communityService.getComments(POST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/api/community/posts/{postId}", POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(POST_ID));
        mockMvc.perform(get(
                        "/api/community/posts/{postId}/comments",
                        POST_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void anonymousUserCannotWriteEvenWithCsrfToken() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPostJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                        "/api/community/posts/{postId}/comments",
                        POST_ID
                )
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "비로그인 댓글"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(communityService);
    }

    @Test
    void authenticatedMutationWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .with(user(authenticatedUser("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPostJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(communityService);
    }

    @Test
    void authenticatedUserIdDeterminesPostAndCommentAuthor() throws Exception {
        when(communityService.createPost(
                eq(USER_ID),
                any(CommunityPostRequest.class)
        )).thenReturn(postResponse());
        when(communityService.createComment(
                eq(USER_ID),
                eq(POST_ID),
                any()
        )).thenReturn(commentResponse());

        mockMvc.perform(post("/api/community/posts")
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 999,
                                  "title": "오늘 경기 이야기",
                                  "content": "세션 사용자가 작성자가 되어야 합니다."
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post(
                        "/api/community/posts/{postId}/comments",
                        POST_ID
                )
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 999, "content": "댓글입니다."}
                                """))
                .andExpect(status().isCreated());

        verify(communityService).createPost(
                eq(USER_ID),
                any(CommunityPostRequest.class)
        );
        verify(communityService).createComment(
                eq(USER_ID),
                eq(POST_ID),
                any()
        );
    }

    @Test
    void authenticatedUserCanReachUpdateAndDeleteEndpoints() throws Exception {
        when(communityService.updatePost(
                eq(USER_ID),
                eq(POST_ID),
                any(CommunityPostRequest.class)
        )).thenReturn(postResponse());

        mockMvc.perform(put("/api/community/posts/{postId}", POST_ID)
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPostJson()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/community/posts/{postId}", POST_ID)
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(
                        "/api/community/comments/{commentId}",
                        COMMENT_ID
                )
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void blankPostIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .with(user(authenticatedUser("ROLE_USER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "   ", "content": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.content").exists());

        verifyNoInteractions(communityService);
    }

    private AuthenticatedUser authenticatedUser(String role) {
        return new AuthenticatedUser(
                USER_ID,
                "community@example.com",
                "encoded-password",
                true,
                List.of(new SimpleGrantedAuthority(role))
        );
    }

    private CommunityPostResponse postResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        return new CommunityPostResponse(
                POST_ID,
                "오늘 경기 이야기",
                "커뮤니티 본문입니다.",
                USER_ID,
                "야구왕",
                3,
                0,
                now,
                now
        );
    }

    private CommunityCommentResponse commentResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 30);
        return new CommunityCommentResponse(
                COMMENT_ID,
                POST_ID,
                USER_ID,
                "야구왕",
                "댓글입니다.",
                now,
                now
        );
    }

    private String validPostJson() {
        return """
                {
                  "title": "오늘 경기 이야기",
                  "content": "커뮤니티 본문입니다."
                }
                """;
    }
}
