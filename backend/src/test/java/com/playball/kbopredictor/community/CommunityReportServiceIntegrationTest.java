package com.playball.kbopredictor.community;

import com.playball.kbopredictor.community.dto.AdminCommunityReportProcessResponse;
import com.playball.kbopredictor.community.dto.AdminCommunityReportResponse;
import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.dto.CommunityReportRequest;
import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;
import com.playball.kbopredictor.community.exception.CommunityReportException;
import com.playball.kbopredictor.community.repository.CommunityCommentReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityCommentReportRepository;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReportRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.service.CommunityReportService;
import com.playball.kbopredictor.community.service.CommunityService;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
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
        "spring.datasource.url=jdbc:h2:mem:community-report-service;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class CommunityReportServiceIntegrationTest {

    @Autowired
    private CommunityReportService reportService;
    @Autowired
    private CommunityService communityService;
    @Autowired
    private CommunityPostReportRepository postReportRepository;
    @Autowired
    private CommunityCommentReportRepository commentReportRepository;
    @Autowired
    private CommunityPostReactionRepository postReactionRepository;
    @Autowired
    private CommunityCommentReactionRepository commentReactionRepository;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private CommunityCommentRepository commentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long ownerId;
    private Long reporterId;
    private Long otherReporterId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            ownerId = createUser("owner", "USER").getId();
            reporterId = createUser("reporter", "USER").getId();
            otherReporterId = createUser("other", "USER").getId();
            adminId = createUser("admin", "ADMIN").getId();
        });
    }

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            commentReportRepository.deleteAllInBatch();
            postReportRepository.deleteAllInBatch();
            commentReactionRepository.deleteAllInBatch();
            postReactionRepository.deleteAllInBatch();
            commentRepository.deleteAllInBatch();
            postRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
        });
    }

    @Test
    void userCanReportPostCommentAndReplyWithPolicyValidation() {
        CommunityPostResponse post = createPost("신고 대상 게시글");
        CommunityCommentResponse comment = communityService.createComment(
                ownerId,
                post.id(),
                new CommunityCommentRequest("신고 대상 댓글")
        );
        CommunityCommentResponse reply = communityService.createComment(
                ownerId,
                post.id(),
                new CommunityCommentRequest("신고 대상 답글", comment.id())
        );

        assertThat(reportService.reportPost(
                reporterId,
                post.id(),
                request(CommunityReportReason.ABUSE, null)
        ).status()).isEqualTo(CommunityReportStatus.PENDING);
        assertThat(reportService.reportComment(
                reporterId,
                comment.id(),
                request(CommunityReportReason.SPAM, null)
        ).targetType()).isEqualTo(CommunityReportTargetType.COMMENT);
        assertThat(reportService.reportComment(
                reporterId,
                reply.id(),
                request(CommunityReportReason.OTHER, "경기와 무관한 내용")
        ).targetId()).isEqualTo(reply.id());

        assertThat(postReportRepository.countByPostIdAndReporterId(
                post.id(), reporterId
        )).isOne();
        assertThat(commentReportRepository.count()).isEqualTo(2);

        assertStatus(HttpStatus.CONFLICT, () -> reportService.reportPost(
                reporterId,
                post.id(),
                request(CommunityReportReason.ABUSE, null)
        ));
        assertStatus(HttpStatus.FORBIDDEN, () -> reportService.reportPost(
                ownerId,
                post.id(),
                request(CommunityReportReason.ABUSE, null)
        ));
        assertStatus(HttpStatus.FORBIDDEN, () -> reportService.reportComment(
                ownerId,
                comment.id(),
                request(CommunityReportReason.ABUSE, null)
        ));
        assertStatus(HttpStatus.BAD_REQUEST, () -> reportService.reportPost(
                otherReporterId,
                post.id(),
                request(CommunityReportReason.SPAM, "상세 사유")
        ));

        reportService.reportPost(
                otherReporterId,
                post.id(),
                request(CommunityReportReason.INAPPROPRIATE, null)
        );
        assertThat(postReportRepository.count()).isEqualTo(2);

        CommunityPostResponse deletedPost = createPost("삭제할 게시글");
        communityService.deletePost(ownerId, deletedPost.id());
        assertStatus(HttpStatus.NOT_FOUND, () -> reportService.reportPost(
                reporterId,
                deletedPost.id(),
                request(CommunityReportReason.ABUSE, null)
        ));

        CommunityCommentResponse deletedComment = communityService.createComment(
                ownerId,
                post.id(),
                new CommunityCommentRequest("삭제할 댓글")
        );
        communityService.deleteComment(ownerId, deletedComment.id());
        assertStatus(HttpStatus.NOT_FOUND, () -> reportService.reportComment(
                reporterId,
                deletedComment.id(),
                request(CommunityReportReason.ABUSE, null)
        ));
    }

    @Test
    void adminCanFilterPaginateAndProcessReportsWhileDeletedTargetsRemain() {
        CommunityPostResponse post = createPost("관리자 확인 게시글");
        CommunityCommentResponse comment = communityService.createComment(
                ownerId,
                post.id(),
                new CommunityCommentRequest("관리자 확인 댓글")
        );
        Long postReportId = reportService.reportPost(
                reporterId,
                post.id(),
                request(CommunityReportReason.ABUSE, null)
        ).id();
        Long commentReportId = reportService.reportComment(
                otherReporterId,
                comment.id(),
                request(CommunityReportReason.OTHER, "관리자 확인 필요")
        ).id();

        CommunityPageResponse<AdminCommunityReportResponse> firstPage =
                reportService.getReports(CommunityReportStatus.PENDING, 0, 1);
        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content()).hasSize(1);

        AdminCommunityReportProcessResponse resolved =
                reportService.processReport(
                        adminId,
                        CommunityReportTargetType.POST,
                        postReportId,
                        CommunityReportStatus.RESOLVED
                );
        AdminCommunityReportProcessResponse rejected =
                reportService.processReport(
                        adminId,
                        CommunityReportTargetType.COMMENT,
                        commentReportId,
                        CommunityReportStatus.REJECTED
                );

        assertThat(resolved.processedAt()).isNotNull();
        assertThat(resolved.processedById()).isEqualTo(adminId);
        assertThat(rejected.status()).isEqualTo(CommunityReportStatus.REJECTED);
        assertThat(postReportRepository.findById(postReportId).orElseThrow()
                .getProcessedBy().getId()).isEqualTo(adminId);
        assertThat(commentReportRepository.findById(commentReportId).orElseThrow()
                .getProcessedAt()).isNotNull();

        assertThat(reportService.getReports(
                CommunityReportStatus.PENDING, 0, 20
        ).totalElements()).isZero();
        assertThat(reportService.getReports(
                CommunityReportStatus.RESOLVED, 0, 20
        ).totalElements()).isOne();
        assertThat(reportService.getReports(
                CommunityReportStatus.REJECTED, 0, 20
        ).totalElements()).isOne();

        communityService.deleteComment(ownerId, comment.id());
        communityService.deletePost(ownerId, post.id());

        CommunityPageResponse<AdminCommunityReportResponse> afterDelete =
                reportService.getReports(null, 0, 20);
        assertThat(afterDelete.totalElements()).isEqualTo(2);
        assertThat(afterDelete.content())
                .allSatisfy(report -> {
                    assertThat(report.contentDeleted()).isTrue();
                    assertThat(report.targetContent()).isEqualTo("삭제된 콘텐츠");
                });

        assertStatus(HttpStatus.CONFLICT, () -> reportService.processReport(
                adminId,
                CommunityReportTargetType.POST,
                postReportId,
                CommunityReportStatus.REJECTED
        ));
        assertStatus(HttpStatus.BAD_REQUEST, () -> reportService.processReport(
                adminId,
                CommunityReportTargetType.POST,
                postReportId,
                CommunityReportStatus.PENDING
        ));
    }

    @Test
    void concurrentDuplicateReportsCreateOnlyOneRow() throws Exception {
        CommunityPostResponse post = createPost("동시 신고 게시글");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> reportOutcome(
                    ready, start, post.id()
            ));
            Future<String> second = executor.submit(() -> reportOutcome(
                    ready, start, post.id()
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder("CREATED", "CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        assertThat(postReportRepository.countByPostIdAndReporterId(
                post.id(),
                reporterId
        )).isOne();
    }

    private String reportOutcome(
            CountDownLatch ready,
            CountDownLatch start,
            Long postId
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 신고 시작 대기 실패");
            }
            reportService.reportPost(
                    reporterId,
                    postId,
                    request(CommunityReportReason.ABUSE, null)
            );
            return "CREATED";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (CommunityReportException exception) {
            return exception.getStatus().value() == 409
                    ? "CONFLICT"
                    : "ERROR-" + exception.getStatus().value();
        }
    }

    private CommunityPostResponse createPost(String title) {
        return communityService.createPost(
                ownerId,
                new CommunityPostRequest(title, title + " 본문")
        );
    }

    private CommunityReportRequest request(
            CommunityReportReason reason,
            String detail
    ) {
        return new CommunityReportRequest(reason, detail);
    }

    private User createUser(String prefix, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(
                prefix + "-" + suffix + "@example.com",
                "encoded-password",
                prefix + suffix,
                null,
                LocalDateTime.of(2026, 9, 3, 12, 0)
        );
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.saveAndFlush(user);
    }

    private void assertStatus(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        CommunityReportException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(status)
                );
    }
}
