package com.playball.kbopredictor.community.service;

import com.playball.kbopredictor.community.dto.AdminCommunityReportProcessResponse;
import com.playball.kbopredictor.community.dto.AdminCommunityReportResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityReportRequest;
import com.playball.kbopredictor.community.dto.CommunityReportResponse;
import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityCommentReport;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.entity.CommunityPostReport;
import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;
import com.playball.kbopredictor.community.exception.CommunityReportException;
import com.playball.kbopredictor.community.repository.CommunityCommentReportRepository;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReportRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.community.repository.CommunityReportQueryRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityReportService {

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostReportRepository postReportRepository;
    private final CommunityCommentReportRepository commentReportRepository;
    private final CommunityReportQueryRepository reportQueryRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public CommunityReportResponse reportPost(
            Long reporterId,
            Long postId,
            CommunityReportRequest request
    ) {
        CommunityPost post = postRepository.findByIdAndStatusForUpdate(
                postId,
                CommunityContentStatus.ACTIVE
        ).orElseThrow(() -> notFound("신고할 게시글을 찾을 수 없습니다."));
        requireNotOwner(post.getUser(), reporterId);
        requireNotReportedPost(postId, reporterId);

        CommunityPostReport report = CommunityPostReport.create(
                post,
                user(reporterId),
                request.reason(),
                normalizedDetail(request.reason(), request.detail()),
                now()
        );
        try {
            postReportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateReport();
        }
        return response(report);
    }

    @Transactional
    public CommunityReportResponse reportComment(
            Long reporterId,
            Long commentId,
            CommunityReportRequest request
    ) {
        CommunityComment comment = commentRepository.findByIdAndStatusForUpdate(
                commentId,
                CommunityContentStatus.ACTIVE
        ).orElseThrow(() -> notFound("신고할 댓글을 찾을 수 없습니다."));
        requireNotOwner(comment.getUser(), reporterId);
        requireNotReportedComment(commentId, reporterId);

        CommunityCommentReport report = CommunityCommentReport.create(
                comment,
                user(reporterId),
                request.reason(),
                normalizedDetail(request.reason(), request.detail()),
                now()
        );
        try {
            commentReportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateReport();
        }
        return response(report);
    }

    public CommunityPageResponse<AdminCommunityReportResponse> getReports(
            CommunityReportStatus status,
            int page,
            int size
    ) {
        List<AdminCommunityReportResponse> content =
                reportQueryRepository.findPage(status, page, size);
        long totalElements = reportQueryRepository.count(status);
        int totalPages = totalElements == 0
                ? 0
                : Math.toIntExact((totalElements + size - 1) / size);
        return new CommunityPageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                totalPages == 0 || page >= totalPages - 1
        );
    }

    @Transactional
    public AdminCommunityReportProcessResponse processReport(
            Long adminId,
            CommunityReportTargetType reportType,
            Long reportId,
            CommunityReportStatus nextStatus
    ) {
        requireTerminalStatus(nextStatus);
        User processor = user(adminId);
        LocalDateTime processedAt = now();

        if (reportType == CommunityReportTargetType.POST) {
            CommunityPostReport report = postReportRepository
                    .findByIdForUpdate(reportId)
                    .orElseThrow(() -> notFound("게시글 신고를 찾을 수 없습니다."));
            requirePending(report.getStatus());
            report.process(nextStatus, processor, processedAt);
        } else {
            CommunityCommentReport report = commentReportRepository
                    .findByIdForUpdate(reportId)
                    .orElseThrow(() -> notFound("댓글 신고를 찾을 수 없습니다."));
            requirePending(report.getStatus());
            report.process(nextStatus, processor, processedAt);
        }

        return new AdminCommunityReportProcessResponse(
                reportType,
                reportId,
                nextStatus,
                processedAt,
                processor.getId(),
                processor.getNickname()
        );
    }

    private void requireNotReportedPost(Long postId, Long reporterId) {
        if (postReportRepository.existsByPostIdAndReporterId(
                postId,
                reporterId
        )) {
            throw duplicateReport();
        }
    }

    private void requireNotReportedComment(Long commentId, Long reporterId) {
        if (commentReportRepository.existsByCommentIdAndReporterId(
                commentId,
                reporterId
        )) {
            throw duplicateReport();
        }
    }

    private void requireNotOwner(User owner, Long reporterId) {
        if (owner.getId().equals(reporterId)) {
            throw new CommunityReportException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 콘텐츠는 신고할 수 없습니다."
            );
        }
    }

    private String normalizedDetail(
            CommunityReportReason reason,
            String detail
    ) {
        if (reason == null) {
            throw new CommunityReportException(
                    HttpStatus.BAD_REQUEST,
                    "신고 사유를 선택해 주세요."
            );
        }
        String normalized = detail == null || detail.isBlank()
                ? null
                : detail.trim();
        if (reason != CommunityReportReason.OTHER && normalized != null) {
            throw new CommunityReportException(
                    HttpStatus.BAD_REQUEST,
                    "상세 사유는 기타 신고에서만 입력할 수 있습니다."
            );
        }
        return normalized;
    }

    private void requireTerminalStatus(CommunityReportStatus status) {
        if (status != CommunityReportStatus.RESOLVED
                && status != CommunityReportStatus.REJECTED) {
            throw new CommunityReportException(
                    HttpStatus.BAD_REQUEST,
                    "신고는 처리 완료 또는 기각 상태로만 변경할 수 있습니다."
            );
        }
    }

    private void requirePending(CommunityReportStatus status) {
        if (status != CommunityReportStatus.PENDING) {
            throw new CommunityReportException(
                    HttpStatus.CONFLICT,
                    "이미 처리된 신고입니다."
            );
        }
    }

    private CommunityReportResponse response(CommunityPostReport report) {
        return new CommunityReportResponse(
                report.getId(),
                CommunityReportTargetType.POST,
                report.getPost().getId(),
                report.getReason(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    private CommunityReportResponse response(CommunityCommentReport report) {
        return new CommunityReportResponse(
                report.getId(),
                CommunityReportTargetType.COMMENT,
                report.getComment().getId(),
                report.getReason(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    private User user(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> notFound("사용자를 찾을 수 없습니다."));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private CommunityReportException duplicateReport() {
        return new CommunityReportException(
                HttpStatus.CONFLICT,
                "이미 신고한 콘텐츠입니다."
        );
    }

    private CommunityReportException notFound(String message) {
        return new CommunityReportException(HttpStatus.NOT_FOUND, message);
    }
}
