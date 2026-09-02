package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostListItemResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.service.CommunityService;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-service;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
@ActiveProfiles("test")
@Import(CommunityServiceIntegrationTest.FixedClockConfiguration.class)
class CommunityServiceIntegrationTest {

    @Autowired
    private CommunityService communityService;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private CommunityCommentRepository commentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long ownerId;
    private Long otherUserId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            ownerId = createUser("owner", "USER").getId();
            otherUserId = createUser("other", "USER").getId();
            adminId = createUser("admin", "ADMIN").getId();
        });
    }

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            commentRepository.deleteAllInBatch();
            postRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
        });
    }

    @Test
    void postsArePagedNewestFirstWithActiveCommentCounts() {
        List<Long> ids = new ArrayList<>();
        for (int index = 1; index <= 21; index++) {
            ids.add(communityService.createPost(
                    ownerId,
                    new CommunityPostRequest(
                            "게시글 " + index,
                            "페이지네이션 본문 " + index
                    )
            ).id());
        }
        Long newestPostId = ids.getLast();
        communityService.createComment(
                otherUserId,
                newestPostId,
                new CommunityCommentRequest("첫 댓글")
        );
        CommunityCommentResponse deletedComment = communityService.createComment(
                ownerId,
                newestPostId,
                new CommunityCommentRequest("삭제할 댓글")
        );
        communityService.deleteComment(ownerId, deletedComment.id());

        CommunityPageResponse<CommunityPostListItemResponse> first =
                communityService.getPosts(0, 10);
        CommunityPageResponse<CommunityPostListItemResponse> last =
                communityService.getPosts(2, 10);

        assertThat(first.content()).hasSize(10);
        assertThat(first.content().getFirst().id()).isEqualTo(newestPostId);
        assertThat(first.content().getFirst().commentCount()).isEqualTo(1);
        assertThat(first.totalElements()).isEqualTo(21);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();
        assertThat(last.content()).hasSize(1);
        assertThat(last.last()).isTrue();
    }

    @Test
    void postOwnerCanUpdateAndSoftDeleteWhileOtherUserCannot() {
        CommunityPostResponse created = communityService.createPost(
                ownerId,
                new CommunityPostRequest("원래 제목", "원래 본문")
        );

        assertForbidden(() -> communityService.updatePost(
                otherUserId,
                created.id(),
                new CommunityPostRequest("탈취 제목", "탈취 본문")
        ));

        CommunityPostResponse updated = communityService.updatePost(
                ownerId,
                created.id(),
                new CommunityPostRequest("수정 제목", "수정 본문")
        );
        assertThat(updated.title()).isEqualTo("수정 제목");
        assertThat(updated.content()).isEqualTo("수정 본문");

        CommunityPostResponse viewed = communityService.getPost(created.id());
        assertThat(viewed.viewCount()).isEqualTo(1);
        assertForbidden(() -> communityService.deletePost(
                otherUserId,
                created.id()
        ));

        communityService.deletePost(ownerId, created.id());

        assertThat(postRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(CommunityContentStatus.DELETED);
        assertThatThrownBy(() -> communityService.getPost(created.id()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND)
                );
    }

    @Test
    void adminCanDeleteAnotherUsersPostButCannotEditIt() {
        CommunityPostResponse post = communityService.createPost(
                ownerId,
                new CommunityPostRequest("관리 대상", "관리 대상 본문")
        );

        assertForbidden(() -> communityService.updatePost(
                adminId,
                post.id(),
                new CommunityPostRequest("관리자 수정", "수정 시도")
        ));

        communityService.deletePost(adminId, post.id());

        assertThat(postRepository.findById(post.id()).orElseThrow().getStatus())
                .isEqualTo(CommunityContentStatus.DELETED);
    }

    @Test
    void commentsEnforceOwnershipAndExcludeSoftDeletedRows() {
        Long postId = communityService.createPost(
                ownerId,
                new CommunityPostRequest("댓글 글", "댓글 본문")
        ).id();
        CommunityCommentResponse ownerComment = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("작성자 댓글")
        );
        CommunityCommentResponse otherComment = communityService.createComment(
                otherUserId,
                postId,
                new CommunityCommentRequest("다른 사용자 댓글")
        );

        assertForbidden(() -> communityService.deleteComment(
                ownerId,
                otherComment.id()
        ));
        communityService.deleteComment(ownerId, ownerComment.id());

        assertThat(communityService.getComments(postId))
                .extracting(CommunityCommentResponse::id)
                .containsExactly(otherComment.id());
        assertThat(commentRepository.findById(ownerComment.id())
                .orElseThrow().getStatus())
                .isEqualTo(CommunityContentStatus.DELETED);

        communityService.deleteComment(adminId, otherComment.id());
        assertThat(communityService.getComments(postId)).isEmpty();
    }

    private User createUser(String prefix, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(
                prefix + "-" + suffix + "@example.com",
                "encoded-password",
                prefix + suffix,
                null,
                java.time.LocalDateTime.ofInstant(
                        Instant.parse("2026-09-02T03:00:00Z"),
                        ZoneId.of("Asia/Seoul")
                )
        );
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.saveAndFlush(user);
    }

    private void assertForbidden(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN)
                );
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedCommunityClock() {
            return Clock.fixed(
                    Instant.parse("2026-09-02T03:00:00Z"),
                    ZoneId.of("Asia/Seoul")
            );
        }
    }
}
