package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityCommentUpdateRequest;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostListItemResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.dto.CommunityReactionResponse;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import com.playball.kbopredictor.community.repository.CommunityCommentReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.service.CommunityReactionService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
@Import(CommunityServiceIntegrationTest.MutableClockConfiguration.class)
class CommunityServiceIntegrationTest {

    private static final Instant INITIAL_INSTANT =
            Instant.parse("2026-09-02T03:00:00Z");

    @Autowired
    private CommunityService communityService;
    @Autowired
    private CommunityReactionService reactionService;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private CommunityCommentRepository commentRepository;
    @Autowired
    private CommunityPostReactionRepository postReactionRepository;
    @Autowired
    private CommunityCommentReactionRepository commentReactionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MutableClock clock;

    private Long ownerId;
    private Long otherUserId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        clock.setInstant(INITIAL_INSTANT);
        transactionTemplate.executeWithoutResult(status -> {
            ownerId = createUser("owner", "USER").getId();
            otherUserId = createUser("other", "USER").getId();
            adminId = createUser("admin", "ADMIN").getId();
        });
    }

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            commentReactionRepository.deleteAllInBatch();
            postReactionRepository.deleteAllInBatch();
            commentRepository.deleteAllInBatch();
            postRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
        });
    }

    @Test
    void postReactionsToggleAndAggregateWithoutAllowingOwnerOrDeletedPost() {
        Long postId = createPost("반응 전이 게시글");

        var liked = reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.LIKE
        );
        assertThat(liked.likeCount()).isEqualTo(1);
        assertThat(liked.dislikeCount()).isZero();
        assertThat(liked.myReaction()).isEqualTo(CommunityReactionType.LIKE);

        var cancelled = reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.LIKE
        );
        assertThat(cancelled.likeCount()).isZero();
        assertThat(cancelled.myReaction()).isNull();

        reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.LIKE
        );
        var disliked = reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.DISLIKE
        );
        assertThat(disliked.likeCount()).isZero();
        assertThat(disliked.dislikeCount()).isEqualTo(1);
        assertThat(disliked.myReaction())
                .isEqualTo(CommunityReactionType.DISLIKE);

        var switchedBack = reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.LIKE
        );
        assertThat(switchedBack.likeCount()).isEqualTo(1);
        assertThat(switchedBack.dislikeCount()).isZero();
        assertThat(postReactionRepository.countByPostIdAndUserId(
                postId,
                otherUserId
        )).isEqualTo(1);

        reactionService.togglePostReaction(
                adminId,
                postId,
                CommunityReactionType.DISLIKE
        );
        CommunityPostResponse viewedByOther = communityService.getPost(
                postId,
                otherUserId
        );
        assertThat(viewedByOther.likeCount()).isEqualTo(1);
        assertThat(viewedByOther.dislikeCount()).isEqualTo(1);
        assertThat(viewedByOther.myReaction())
                .isEqualTo(CommunityReactionType.LIKE);
        assertThat(communityService.getPost(postId, null).myReaction()).isNull();

        CommunityPostListItemResponse listItem = communityService
                .getPosts(0, 15)
                .content()
                .getFirst();
        assertThat(listItem.likeCount()).isEqualTo(1);
        assertThat(listItem.dislikeCount()).isEqualTo(1);

        assertForbidden(() -> reactionService.togglePostReaction(
                ownerId,
                postId,
                CommunityReactionType.LIKE
        ));

        communityService.deletePost(ownerId, postId);
        assertStatus(HttpStatus.NOT_FOUND, () ->
                reactionService.togglePostReaction(
                        otherUserId,
                        postId,
                        CommunityReactionType.DISLIKE
                )
        );
        assertThat(postReactionRepository.count()).isEqualTo(2);
    }

    @Test
    void commentAndReplyReactionsRespectDeletionAndPlaceholderPolicy() {
        Long postId = createPost("댓글 반응 게시글");
        CommunityCommentResponse parent = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("부모 댓글")
        );
        CommunityCommentResponse reply = communityService.createComment(
                otherUserId,
                postId,
                new CommunityCommentRequest("답글", parent.id())
        );

        reactionService.toggleCommentReaction(
                otherUserId,
                parent.id(),
                CommunityReactionType.LIKE
        );
        reactionService.toggleCommentReaction(
                ownerId,
                reply.id(),
                CommunityReactionType.DISLIKE
        );

        List<CommunityCommentResponse> viewed = communityService.getComments(
                postId,
                otherUserId
        );
        assertThat(viewed.getFirst().likeCount()).isEqualTo(1);
        assertThat(viewed.getFirst().myReaction())
                .isEqualTo(CommunityReactionType.LIKE);
        assertThat(viewed.getFirst().replies().getFirst().dislikeCount())
                .isEqualTo(1);
        assertThat(viewed.getFirst().replies().getFirst().myReaction()).isNull();
        assertThat(communityService.getComments(postId, null)
                .getFirst().myReaction()).isNull();

        assertForbidden(() -> reactionService.toggleCommentReaction(
                ownerId,
                parent.id(),
                CommunityReactionType.LIKE
        ));
        assertForbidden(() -> reactionService.toggleCommentReaction(
                otherUserId,
                reply.id(),
                CommunityReactionType.LIKE
        ));

        communityService.deleteComment(ownerId, parent.id());
        CommunityCommentResponse placeholder = communityService
                .getComments(postId, otherUserId)
                .getFirst();
        assertThat(placeholder.deleted()).isTrue();
        assertThat(placeholder.likeCount()).isZero();
        assertThat(placeholder.dislikeCount()).isZero();
        assertThat(placeholder.myReaction()).isNull();
        assertThat(placeholder.replies()).extracting(CommunityCommentResponse::id)
                .containsExactly(reply.id());

        assertStatus(HttpStatus.NOT_FOUND, () ->
                reactionService.toggleCommentReaction(
                        adminId,
                        parent.id(),
                        CommunityReactionType.DISLIKE
                )
        );

        communityService.deleteComment(otherUserId, reply.id());
        assertThat(communityService.getComments(postId, otherUserId)).isEmpty();
        assertThat(commentReactionRepository.count()).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalReactionsAreSerializedWithoutDuplicateRows()
            throws Exception {
        Long postId = createPost("동시 반응 게시글");
        CommunityCommentResponse comment = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("동시 반응 댓글")
        );

        runConcurrently(() -> reactionService.togglePostReaction(
                otherUserId,
                postId,
                CommunityReactionType.LIKE
        ));
        assertThat(postReactionRepository.countByPostIdAndUserId(
                postId,
                otherUserId
        )).isZero();
        assertThat(reactionService.getPostReaction(postId, otherUserId))
                .isEqualTo(CommunityReactionResponse.empty());

        runConcurrently(() -> reactionService.toggleCommentReaction(
                otherUserId,
                comment.id(),
                CommunityReactionType.DISLIKE
        ));
        assertThat(commentReactionRepository.countByCommentIdAndUserId(
                comment.id(),
                otherUserId
        )).isZero();
        assertThat(reactionService.getCommentReaction(
                comment.id(),
                otherUserId
        )).isEqualTo(CommunityReactionResponse.empty());
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
        CommunityCommentResponse parent = communityService.createComment(
                otherUserId,
                newestPostId,
                new CommunityCommentRequest("첫 댓글")
        );
        communityService.createComment(
                ownerId,
                newestPostId,
                new CommunityCommentRequest("첫 답글", parent.id())
        );
        communityService.deleteComment(otherUserId, parent.id());
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

    @Test
    void commentOwnerCanEditButOtherUserAndAdminCannotEdit() {
        Long postId = createPost("수정 테스트");
        CommunityCommentResponse comment = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("원래 댓글")
        );

        assertForbidden(() -> communityService.updateComment(
                otherUserId,
                comment.id(),
                new CommunityCommentUpdateRequest("타인 수정")
        ));
        assertForbidden(() -> communityService.updateComment(
                adminId,
                comment.id(),
                new CommunityCommentUpdateRequest("관리자 수정")
        ));

        clock.setInstant(INITIAL_INSTANT.plusSeconds(60));
        CommunityCommentResponse updated = communityService.updateComment(
                ownerId,
                comment.id(),
                new CommunityCommentUpdateRequest("수정된 댓글")
        );

        assertThat(updated.content()).isEqualTo("수정된 댓글");
        assertThat(updated.edited()).isTrue();
        assertThat(updated.updatedAt()).isAfter(updated.createdAt());

        communityService.deleteComment(ownerId, comment.id());
        assertStatus(HttpStatus.NOT_FOUND, () -> communityService.updateComment(
                ownerId,
                comment.id(),
                new CommunityCommentUpdateRequest("삭제 후 수정")
        ));
    }

    @Test
    void repliesAreReturnedNestedAndOldestFirst() {
        Long postId = createPost("답글 정렬");
        CommunityCommentResponse parent = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("부모 댓글")
        );
        CommunityCommentResponse firstReply = communityService.createComment(
                otherUserId,
                postId,
                new CommunityCommentRequest("첫 답글", parent.id())
        );
        CommunityCommentResponse secondReply = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("두 번째 답글", parent.id())
        );

        List<CommunityCommentResponse> comments =
                communityService.getComments(postId);

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().id()).isEqualTo(parent.id());
        assertThat(comments.getFirst().replies())
                .extracting(CommunityCommentResponse::id)
                .containsExactly(firstReply.id(), secondReply.id());
        assertThat(comments.getFirst().replies())
                .extracting(CommunityCommentResponse::parentCommentId)
                .containsOnly(parent.id());
    }

    @Test
    void invalidReplyParentsAreRejectedByServer() {
        Long firstPostId = createPost("첫 게시글");
        Long secondPostId = createPost("둘째 게시글");
        CommunityCommentResponse parent = communityService.createComment(
                ownerId,
                firstPostId,
                new CommunityCommentRequest("부모 댓글")
        );

        assertStatus(HttpStatus.BAD_REQUEST, () -> communityService.createComment(
                otherUserId,
                secondPostId,
                new CommunityCommentRequest("타 게시글 답글", parent.id())
        ));

        CommunityCommentResponse reply = communityService.createComment(
                otherUserId,
                firstPostId,
                new CommunityCommentRequest("정상 답글", parent.id())
        );
        assertStatus(HttpStatus.BAD_REQUEST, () -> communityService.createComment(
                ownerId,
                firstPostId,
                new CommunityCommentRequest("2단계 답글", reply.id())
        ));

        communityService.deleteComment(ownerId, parent.id());
        assertStatus(HttpStatus.BAD_REQUEST, () -> communityService.createComment(
                ownerId,
                firstPostId,
                new CommunityCommentRequest("삭제 부모 답글", parent.id())
        ));
    }

    @Test
    void deletedParentKeepsAnonymousPlaceholderUntilActiveRepliesAreGone() {
        Long postId = createPost("삭제 placeholder");
        CommunityCommentResponse parent = communityService.createComment(
                ownerId,
                postId,
                new CommunityCommentRequest("숨겨질 작성자 본문")
        );
        CommunityCommentResponse reply = communityService.createComment(
                otherUserId,
                postId,
                new CommunityCommentRequest("유지될 답글", parent.id())
        );

        communityService.deleteComment(ownerId, parent.id());

        List<CommunityCommentResponse> comments =
                communityService.getComments(postId);
        assertThat(comments).hasSize(1);
        CommunityCommentResponse placeholder = comments.getFirst();
        assertThat(placeholder.deleted()).isTrue();
        assertThat(placeholder.authorId()).isNull();
        assertThat(placeholder.authorNickname()).isNull();
        assertThat(placeholder.content()).isNull();
        assertThat(placeholder.replies())
                .extracting(CommunityCommentResponse::id)
                .containsExactly(reply.id());
        assertThat(communityService.getPost(postId).commentCount()).isEqualTo(1);

        communityService.deleteComment(adminId, reply.id());

        assertThat(communityService.getComments(postId)).isEmpty();
        assertThat(communityService.getPost(postId).commentCount()).isZero();
    }

    private Long createPost(String title) {
        return communityService.createPost(
                ownerId,
                new CommunityPostRequest(title, "게시글 본문")
        ).id();
    }

    private void runConcurrently(Runnable action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 요청 시작 대기 실패");
                    }
                    action.run();
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
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
        assertStatus(HttpStatus.FORBIDDEN, operation);
    }

    private void assertStatus(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(status)
                );
    }

    @TestConfiguration
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableCommunityClock() {
            return new MutableClock(
                    INITIAL_INSTANT,
                    ZoneId.of("Asia/Seoul")
            );
        }
    }

    static final class MutableClock extends Clock {

        private Instant currentInstant;
        private final ZoneId zone;

        MutableClock(Instant currentInstant, ZoneId zone) {
            this.currentInstant = currentInstant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            if (zone.equals(requestedZone)) {
                return this;
            }
            return Clock.fixed(currentInstant, requestedZone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
