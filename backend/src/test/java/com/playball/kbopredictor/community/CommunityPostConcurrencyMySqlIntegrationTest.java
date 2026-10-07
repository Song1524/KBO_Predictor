package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.*;
import com.playball.kbopredictor.community.entity.*;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.service.*;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.community.write-rate-limit.enabled=false")
@ActiveProfiles("test")
class CommunityPostConcurrencyMySqlIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired CommunityService service;
    @Autowired CommunityReactionService reactions;
    @Autowired CommunityReportService reports;
    @Autowired CommunityPostRepository posts;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    Long ownerId, otherId, postId;

    @BeforeEach void createOwnFixture() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        ownerId = users.saveAndFlush(User.createLocal("owner-" + suffix + "@example.com", "hash",
                "owner-" + suffix, null, LocalDateTime.now())).getId();
        otherId = users.saveAndFlush(User.createLocal("other-" + suffix + "@example.com", "hash",
                "other-" + suffix, null, LocalDateTime.now())).getId();
        postId = service.createPost(ownerId, new CommunityPostRequest("original", "original body")).id();
    }

    @AfterEach void cleanOwnFixture() {
        if (postId != null) {
            jdbc.update("delete from community_comment_reports where comment_id in (select id from community_comments where post_id = ?)", postId);
            jdbc.update("delete from community_comment_reactions where comment_id in (select id from community_comments where post_id = ?)", postId);
            jdbc.update("delete from community_comments where post_id = ? and parent_comment_id is not null", postId);
            jdbc.update("delete from community_comments where post_id = ?", postId);
            jdbc.update("delete from community_post_reports where post_id = ?", postId);
            jdbc.update("delete from community_post_reactions where post_id = ?", postId);
            jdbc.update("delete from community_posts where id = ?", postId);
        }
        if (ownerId != null) jdbc.update("delete from users where id in (?, ?)", ownerId, otherId);
    }

    @Test void thirtySimultaneousDetailRequestsIncrementExactlyThirtyTimes() throws Exception {
        int count = 30;
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> {
                ready.countDown(); await(start); return service.getPost(postId).viewCount();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            Set<Long> observed = new HashSet<>();
            for (var future : futures) observed.add(future.get(20, TimeUnit.SECONDS));
            assertThat(observed).hasSize(count);
        } finally { start.countDown(); }
        assertThat(row().getViewCount()).isEqualTo(count);
    }

    @Test void viewThenConcurrentEditPreservesContentAndCounter() throws Exception {
        assertThat(orderedRace(() -> service.getPost(postId), this::edit)).isEqualTo(200);
        assertThat(row().getTitle()).isEqualTo("edited"); assertThat(row().getViewCount()).isEqualTo(1);
    }
    @Test void editThenConcurrentViewPreservesContentAndCounter() throws Exception {
        assertThat(orderedRace(this::edit, () -> service.getPost(postId))).isEqualTo(200);
        assertThat(row().getContent()).isEqualTo("edited body"); assertThat(row().getViewCount()).isEqualTo(1);
    }
    @Test void viewThenConcurrentDeleteNeverRestoresActiveStatus() throws Exception {
        assertThat(orderedRace(() -> service.getPost(postId), this::delete)).isEqualTo(200);
        assertDeleted(); assertThat(row().getViewCount()).isEqualTo(1);
    }
    @Test void deleteThenConcurrentViewDoesNotCountDeletedPost() throws Exception {
        assertThat(orderedRace(this::delete, () -> service.getPost(postId))).isEqualTo(404);
        assertDeleted(); assertThat(row().getViewCount()).isZero();
    }
    @Test void simultaneousEditsSerializeAndSecondWriterWins() throws Exception {
        assertThat(orderedRace(this::edit,
                () -> service.updatePost(ownerId, postId, new CommunityPostRequest("second", "second body")))).isEqualTo(200);
        assertThat(row().getTitle()).isEqualTo("second"); assertThat(row().getContent()).isEqualTo("second body");
    }
    @Test void editThenConcurrentDeleteKeepsUpdatedContentButDeletedStatus() throws Exception {
        assertThat(orderedRace(this::edit, this::delete)).isEqualTo(200);
        assertDeleted(); assertThat(row().getContent()).isEqualTo("edited body");
    }
    @Test void deleteThenConcurrentEditCannotRestoreContentOrStatus() throws Exception {
        assertThat(orderedRace(this::delete, this::edit)).isEqualTo(404);
        assertDeleted(); assertThat(row().getContent()).isEqualTo("original body");
    }
    @Test void simultaneousDeletesLeaveOneDeletedRow() throws Exception {
        assertThat(orderedRace(this::delete, this::delete)).isEqualTo(404);
        assertDeleted(); assertThat(jdbc.queryForObject("select count(*) from community_posts where id = ?", Long.class, postId)).isEqualTo(1);
    }
    @Test void reactionsCommentsRepliesReportsAndPopularPostsStillWork() throws Exception {
        var comment = service.createComment(otherId, postId, new CommunityCommentRequest("comment"));
        service.createComment(ownerId, postId, new CommunityCommentRequest("reply", comment.id()));
        assertThat(reactions.togglePostReaction(otherId, postId, CommunityReactionType.DISLIKE).dislikeCount()).isEqualTo(1);
        assertThat(reactions.togglePostReaction(otherId, postId, CommunityReactionType.LIKE).likeCount()).isEqualTo(1);
        reports.reportPost(otherId, postId, new CommunityReportRequest(CommunityReportReason.SPAM, null));
        assertThat(service.getComments(postId)).hasSize(1);
        assertThat(service.getComments(postId).getFirst().replies()).hasSize(1);
        assertThat(service.getPopularPosts()).extracting(CommunityPopularPostResponse::id).contains(postId);
        assertThat(service.getPost(postId).commentCount()).isEqualTo(2);
    }
    @Test void nonOwnerCannotEditOrDeleteAndDeletedPostRemainsUnavailable() {
        assertThatThrownBy(() -> service.updatePost(otherId, postId, new CommunityPostRequest("bad", "bad")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.deletePost(otherId, postId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        delete();
        assertThatThrownBy(this::edit).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test void failedDetailTransactionRollsBackOnlyItsCounterAndPreservesPost() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.getPost(postId);
            throw new IllegalStateException("response assembly failed");
        })).isInstanceOf(IllegalStateException.class);
        var post = row();
        assertThat(post.getViewCount()).isZero();
        assertThat(post.getStatus()).isEqualTo(CommunityContentStatus.ACTIVE);
        assertThat(post.getContent()).isEqualTo("original body");
        edit();
        assertThat(row().getContent()).isEqualTo("edited body");
    }

    // Two distinct transactions overlap. Assert an actual InnoDB lock wait before releasing the first commit.
    private int orderedRace(Runnable first, Runnable second) throws Exception {
        var holding = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> transactions.executeWithoutResult(status -> {
                posts.findByIdAndStatusForUpdate(postId, CommunityContentStatus.ACTIVE).orElseThrow();
                first.run(); holding.countDown(); await(release);
            }));
            try {
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                var b = executor.submit(() -> {
                    try { second.run(); return 200; }
                    catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !b.isDone()) {
                    if (jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits", Long.class) > 0) {
                        waiting = true; break;
                    }
                    Thread.sleep(20);
                }
                assertThat(waiting).as("second transaction must actually wait on the first InnoDB lock").isTrue();
                release.countDown(); a.get(15, TimeUnit.SECONDS); return b.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
    private void edit() { service.updatePost(ownerId, postId, new CommunityPostRequest("edited", "edited body")); }
    private void delete() { service.deletePost(ownerId, postId); }
    private CommunityPost row() { return posts.findById(postId).orElseThrow(); }
    private void assertDeleted() { assertThat(row().getStatus()).isEqualTo(CommunityContentStatus.DELETED); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
