package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityCommentReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityCommentReportRepository
        extends JpaRepository<CommunityCommentReport, Long> {

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    long countByCommentIdAndReporterId(Long commentId, Long reporterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from CommunityCommentReport report where report.id = :id")
    Optional<CommunityCommentReport> findByIdForUpdate(@Param("id") Long id);
}
