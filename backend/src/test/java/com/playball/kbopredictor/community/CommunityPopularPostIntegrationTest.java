package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.CommunityPopularPostResponse;
import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.entity.CommunityPostReaction;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.service.CommunityService;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-popular-post;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false",
        "app.community.write-rate-limit.enabled=false"
})
@ActiveProfiles("test")
@Import(CommunityPopularPostIntegrationTest.MutableClockConfiguration.class)
class CommunityPopularPostIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-09-02T03:00:00Z");

    @Autowired
    private CommunityService communityService;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private CommunityPostReactionRepository postReactionRepository;
    @Autowired
    private CommunityCommentRepository commentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MutableClock clock;

    private User author;
    private final List<User> voters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        transactionTemplate.executeWithoutResult(status ->
                author = createUser("popular-author")
        );
    }

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            postReactionRepository.deleteAllInBatch();
            commentRepository.deleteAllInBatch();
            postRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
        });
        voters.clear();
    }

    @Test
    void ranksOnlyByLikesThenCreatedTimeThenId() {
        LocalDateTime now = now();
        CommunityPost tenLikes = createPost(
                "추천 10개",
                now.minusHours(3),
                1
        );
        CommunityPost nineLikes = createPost(
                "추천 9개",
                now.minusHours(1),
                10_000
        );
        CommunityPost olderTie = createPost(
                "동률 이전 글",
                now.minusHours(2),
                50
        );
        CommunityPost firstSameTimeTie = createPost(
                "동률 같은 시각 이전 ID",
                now.minusMinutes(30),
                5
        );
        CommunityPost secondSameTimeTie = createPost(
                "동률 같은 시각 최신 ID",
                now.minusMinutes(30),
                0
        );

        addReactions(tenLikes, CommunityReactionType.LIKE, 10, 0);
        addReactions(tenLikes, CommunityReactionType.DISLIKE, 10, 10);
        addReactions(nineLikes, CommunityReactionType.LIKE, 9, 0);
        addReactions(olderTie, CommunityReactionType.LIKE, 5, 0);
        addReactions(firstSameTimeTie, CommunityReactionType.LIKE, 5, 0);
        addReactions(secondSameTimeTie, CommunityReactionType.LIKE, 5, 0);

        addActiveComment(tenLikes, "댓글 1");
        addActiveComment(nineLikes, "댓글 1");
        addActiveComment(nineLikes, "댓글 2");
        addActiveComment(nineLikes, "댓글 3");

        List<CommunityPopularPostResponse> result =
                communityService.getPopularPosts();

        assertThat(result).extracting(CommunityPopularPostResponse::id)
                .containsExactly(
                        tenLikes.getId(),
                        nineLikes.getId(),
                        secondSameTimeTie.getId(),
                        firstSameTimeTie.getId(),
                        olderTie.getId()
                );
        assertThat(result.getFirst().likeCount()).isEqualTo(10);
        assertThat(result.getFirst().commentCount()).isEqualTo(1);
        assertThat(result.get(1).likeCount()).isEqualTo(9);
        assertThat(result.get(1).commentCount()).isEqualTo(3);
        assertThat(result.get(1).viewCount()).isEqualTo(10_000);
    }

    @Test
    void includesExactTwentyFourHourBoundaryAndFiltersInvalidCandidates() {
        LocalDateTime now = now();
        CommunityPost recent = createPost("최근 글", now.minusHours(1), 0);
        CommunityPost boundary = createPost(
                "정확히 24시간 경계",
                now.minusHours(24),
                0
        );
        CommunityPost tooOld = createPost(
                "24시간 초과",
                now.minusHours(24).minusNanos(1_000),
                0
        );
        CommunityPost zeroLikes = createPost("추천 0개", now, 0);
        CommunityPost deleted = createPost("삭제 글", now, 0);

        addReactions(recent, CommunityReactionType.LIKE, 1, 0);
        addReactions(boundary, CommunityReactionType.LIKE, 1, 0);
        addReactions(tooOld, CommunityReactionType.LIKE, 10, 0);
        addReactions(deleted, CommunityReactionType.LIKE, 10, 0);
        deleted.delete(now);
        postRepository.saveAndFlush(deleted);

        List<CommunityPopularPostResponse> result =
                communityService.getPopularPosts();

        assertThat(result).extracting(CommunityPopularPostResponse::id)
                .containsExactly(recent.getId(), boundary.getId())
                .doesNotContain(
                        tooOld.getId(),
                        zeroLikes.getId(),
                        deleted.getId()
                );
        assertThat(result).allMatch(post -> post.likeCount() >= 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5, 6})
    void returnsOnlyAvailableCandidatesUpToFive(int candidateCount) {
        List<CommunityPost> posts = new ArrayList<>();
        for (int index = 0; index < candidateCount; index++) {
            CommunityPost post = createPost(
                    "후보 " + index,
                    now().minusMinutes(index),
                    0
            );
            addReactions(
                    post,
                    CommunityReactionType.LIKE,
                    index + 1,
                    0
            );
            posts.add(post);
        }

        List<CommunityPopularPostResponse> result =
                communityService.getPopularPosts();

        assertThat(result).hasSize(Math.min(candidateCount, 5));
        if (candidateCount == 6) {
            assertThat(result).extracting(CommunityPopularPostResponse::id)
                    .containsExactly(
                            posts.get(5).getId(),
                            posts.get(4).getId(),
                            posts.get(3).getId(),
                            posts.get(2).getId(),
                            posts.get(1).getId()
                    );
        }
    }

    @Test
    void countsOnlyActiveCommentsAndDoesNotIncreaseViewCount() {
        CommunityPost post = createPost("댓글 수와 조회수", now(), 42);
        addReactions(post, CommunityReactionType.LIKE, 1, 0);

        CommunityComment activeParent = addActiveComment(post, "활성 댓글");
        addActiveReply(post, activeParent, "활성 답글");

        CommunityComment deletedParent = addActiveComment(post, "삭제 부모");
        deletedParent.delete(now());
        commentRepository.saveAndFlush(deletedParent);
        addActiveReply(post, deletedParent, "삭제 부모 아래 활성 답글");

        CommunityComment deletedComment = addActiveComment(post, "삭제 댓글");
        deletedComment.delete(now());
        commentRepository.saveAndFlush(deletedComment);

        CommunityPopularPostResponse result = communityService
                .getPopularPosts()
                .getFirst();

        assertThat(result.commentCount()).isEqualTo(3);
        assertThat(result.viewCount()).isEqualTo(42);
        assertThat(postRepository.findById(post.getId()))
                .get()
                .extracting(CommunityPost::getViewCount)
                .isEqualTo(42L);
    }

    private CommunityPost createPost(
            String title,
            LocalDateTime createdAt,
            long viewCount
    ) {
        CommunityPost post = CommunityPost.create(
                author,
                title,
                "인기글 테스트 본문",
                createdAt
        );
        ReflectionTestUtils.setField(post, "viewCount", viewCount);
        return postRepository.saveAndFlush(post);
    }

    private void addReactions(
            CommunityPost post,
            CommunityReactionType type,
            int count,
            int voterOffset
    ) {
        for (int index = 0; index < count; index++) {
            postReactionRepository.save(CommunityPostReaction.create(
                    post,
                    voter(voterOffset + index),
                    type,
                    now()
            ));
        }
        postReactionRepository.flush();
    }

    private CommunityComment addActiveComment(
            CommunityPost post,
            String content
    ) {
        return commentRepository.saveAndFlush(CommunityComment.create(
                post,
                voter(30),
                null,
                content,
                now()
        ));
    }

    private CommunityComment addActiveReply(
            CommunityPost post,
            CommunityComment parent,
            String content
    ) {
        return commentRepository.saveAndFlush(CommunityComment.create(
                post,
                voter(31),
                parent,
                content,
                now()
        ));
    }

    private User voter(int index) {
        while (voters.size() <= index) {
            voters.add(createUser("popular-voter-" + voters.size()));
        }
        return voters.get(index);
    }

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.createLocal(
                prefix + "-" + suffix + "@example.com",
                "encoded-password",
                prefix + suffix,
                null,
                now()
        ));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @TestConfiguration
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock mutablePopularPostClock() {
            return new MutableClock(NOW, SEOUL);
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
            currentInstant = instant;
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
