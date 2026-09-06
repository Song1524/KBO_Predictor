package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.exception.CommunityRateLimitException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-write-rate-limit;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false",
        "app.community.write-rate-limit.enabled=true"
})
@ActiveProfiles("test")
@Import(CommunityWriteRateLimitIntegrationTest.MutableClockConfiguration.class)
class CommunityWriteRateLimitIntegrationTest {

    private static final Instant INITIAL_INSTANT =
            Instant.parse("2026-09-03T03:00:00Z");

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
    @Autowired
    private MutableClock clock;

    private Long firstUserId;
    private Long secondUserId;
    private Long adminId;
    private Long targetPostId;

    @BeforeEach
    void setUp() {
        clock.setInstant(INITIAL_INSTANT);
        transactionTemplate.executeWithoutResult(status -> {
            User first = createUser("first", "USER");
            User second = createUser("second", "USER");
            User admin = createUser("admin", "ADMIN");
            User targetOwner = createUser("target-owner", "USER");
            firstUserId = first.getId();
            secondUserId = second.getId();
            adminId = admin.getId();
            targetPostId = postRepository.saveAndFlush(CommunityPost.create(
                    targetOwner,
                    "댓글 대상",
                    "댓글 작성 제한 테스트용 게시글",
                    now()
            )).getId();
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
    void postCooldownRejectsAt29SecondsAndAllowsExact30SecondBoundary() {
        createPost(firstUserId, "첫 글", "첫 내용");

        clock.advanceSeconds(29);
        assertRateLimited(
                () -> createPost(firstUserId, "둘째 글", "다른 내용"),
                1
        );

        clock.advanceSeconds(1);
        createPost(firstUserId, "둘째 글", "다른 내용");

        assertThat(postRepository.findRecentByUserId(
                firstUserId,
                now().minusMinutes(11),
                org.springframework.data.domain.PageRequest.of(0, 10)
        )).hasSize(2);
    }

    @Test
    void deletedPostStillParticipatesInCooldownAndDuplicateWindow() {
        Long postId = createPost(
                firstUserId,
                " 반복   제목 ",
                " 반복\n본문 "
        );
        communityService.deletePost(firstUserId, postId);

        clock.advanceSeconds(29);
        assertRateLimited(
                () -> createPost(firstUserId, "새 제목", "새 본문"),
                1
        );

        clock.advanceSeconds(1);
        assertRateLimited(
                () -> createPost(firstUserId, "반복 제목", "반복 본문"),
                570
        );

        clock.advanceSeconds(570);
        createPost(firstUserId, "반복 제목", "반복 본문");
    }

    @Test
    void commentsAndRepliesShareOneFiveSecondBucket() {
        Long parentId = communityService.createComment(
                secondUserId,
                targetPostId,
                new CommunityCommentRequest("부모 댓글")
        ).id();
        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("일반 댓글")
        );

        clock.advanceSeconds(4);
        assertRateLimited(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("답글", parentId)
                ),
                1
        );

        clock.advanceSeconds(1);
        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("답글", parentId)
        );

        clock.advanceSeconds(4);
        assertRateLimited(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("다음 일반 댓글")
                ),
                1
        );

        clock.advanceSeconds(1);
        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("다음 일반 댓글")
        );
    }

    @Test
    void deletedCommentStillParticipatesInCooldownAndDuplicateWindow() {
        Long commentId = communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest(" 반복   댓글 ")
        ).id();
        communityService.deleteComment(firstUserId, commentId);

        clock.advanceSeconds(4);
        assertRateLimited(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("다른 댓글")
                ),
                1
        );

        clock.advanceSeconds(1);
        assertRateLimited(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("반복 댓글")
                ),
                55
        );

        clock.advanceSeconds(55);
        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("반복 댓글")
        );
    }

    @Test
    void normalizedDuplicateContentIsBlockedButDifferentContentIsAllowed() {
        createPost(firstUserId, "같은   제목", "같은\n본문");
        clock.advanceSeconds(30);
        assertRateLimited(
                () -> createPost(firstUserId, " 같은 제목 ", " 같은 본문 "),
                570
        );

        createPost(firstUserId, "다른 제목", "다른 본문");
        clock.advanceSeconds(30);
        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("같은   댓글")
        );
        clock.advanceSeconds(5);
        assertRateLimited(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest(" 같은 댓글 ")
                ),
                55
        );

        communityService.createComment(
                firstUserId,
                targetPostId,
                new CommunityCommentRequest("실제로 다른 댓글")
        );
    }

    @Test
    void differentUsersDoNotShareBucketsAndAdminIsNotExempt() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                return createPost(firstUserId, "사용자 1", "내용 1");
            });
            Future<Long> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                return createPost(secondUserId, "사용자 2", "내용 2");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isPositive();
            assertThat(second.get(10, TimeUnit.SECONDS)).isPositive();
        } finally {
            executor.shutdownNow();
        }

        createPost(adminId, "관리자 글", "관리자 내용");
        clock.advanceSeconds(1);
        assertRateLimited(
                () -> createPost(adminId, "관리자 둘째 글", "다른 내용"),
                29
        );
    }

    @Test
    void simultaneousPostsFromSameUserCreateOnlyOneRow() throws Exception {
        ConcurrentResult result = runConcurrentCreates(
                () -> createPost(firstUserId, "동시 글 A", "내용 A"),
                () -> createPost(firstUserId, "동시 글 B", "내용 B")
        );

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.rateLimited()).isEqualTo(1);
        assertThat(postRepository.findRecentByUserId(
                firstUserId,
                now().minusMinutes(1),
                org.springframework.data.domain.PageRequest.of(0, 10)
        )).hasSize(1);
    }

    @Test
    void simultaneousCommentsFromSameUserCreateOnlyOneRow() throws Exception {
        ConcurrentResult result = runConcurrentCreates(
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("동시 댓글 A")
                ).id(),
                () -> communityService.createComment(
                        firstUserId,
                        targetPostId,
                        new CommunityCommentRequest("동시 댓글 B")
                ).id()
        );

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.rateLimited()).isEqualTo(1);
        assertThat(commentRepository.findRecentByUserId(
                firstUserId,
                now().minusMinutes(1),
                org.springframework.data.domain.PageRequest.of(0, 10)
        )).hasSize(1);
    }

    private ConcurrentResult runConcurrentCreates(
            CreateOperation firstOperation,
            CreateOperation secondOperation
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch userLocked = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        userRepository.findByIdForUpdate(firstUserId)
                                .orElseThrow();
                        userLocked.countDown();
                        await(secondReady);
                        firstOperation.create();
                        return true;
                    })
            );
            Future<Boolean> second = executor.submit(() -> {
                await(userLocked);
                secondReady.countDown();
                try {
                    secondOperation.create();
                    return true;
                } catch (CommunityRateLimitException exception) {
                    return false;
                }
            });

            boolean firstCreated = Boolean.TRUE.equals(
                    first.get(10, TimeUnit.SECONDS)
            );
            boolean secondCreated = Boolean.TRUE.equals(
                    second.get(10, TimeUnit.SECONDS)
            );
            int created = (firstCreated ? 1 : 0) + (secondCreated ? 1 : 0);
            return new ConcurrentResult(created, 2 - created);
        } finally {
            executor.shutdownNow();
        }
    }

    private Long createPost(Long userId, String title, String content) {
        return communityService.createPost(
                userId,
                new CommunityPostRequest(title, content)
        ).id();
    }

    private User createUser(String prefix, String role) {
        User user = User.createLocal(
                prefix + "-" + UUID.randomUUID() + "@example.test",
                "encoded-password",
                prefix + "-" + UUID.randomUUID(),
                null,
                now()
        );
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.saveAndFlush(user);
    }

    private void assertRateLimited(Runnable operation, long retryAfterSeconds) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        CommunityRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds())
                                .isEqualTo(retryAfterSeconds)
                );
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 latch timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface CreateOperation {
        Long create();
    }

    private record ConcurrentResult(int created, int rateLimited) {
    }

    @TestConfiguration
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableCommunityWriteClock() {
            return new MutableClock(
                    INITIAL_INSTANT,
                    ZoneId.of("Asia/Seoul")
            );
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> currentInstant;
        private final ZoneId zone;

        MutableClock(Instant currentInstant, ZoneId zone) {
            this.currentInstant = new AtomicReference<>(currentInstant);
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            currentInstant.set(instant);
        }

        void advanceSeconds(long seconds) {
            currentInstant.updateAndGet(value -> value.plusSeconds(seconds));
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
            return Clock.fixed(currentInstant.get(), requestedZone);
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }
}
