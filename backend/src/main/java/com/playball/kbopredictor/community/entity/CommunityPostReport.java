package com.playball.kbopredictor.community.entity;

import com.playball.kbopredictor.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "community_post_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_community_post_reports_post_reporter",
                columnNames = {"post_id", "reporter_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityReportReason reason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityReportStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private User processedBy;

    public static CommunityPostReport create(
            CommunityPost post,
            User reporter,
            CommunityReportReason reason,
            String detail,
            LocalDateTime now
    ) {
        CommunityPostReport report = new CommunityPostReport();
        report.post = post;
        report.reporter = reporter;
        report.reason = reason;
        report.detail = detail;
        report.status = CommunityReportStatus.PENDING;
        report.createdAt = now;
        return report;
    }

    public void process(
            CommunityReportStatus nextStatus,
            User processor,
            LocalDateTime now
    ) {
        this.status = nextStatus;
        this.processedBy = processor;
        this.processedAt = now;
    }
}
