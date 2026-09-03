package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityPostReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityPostReportRepository
        extends JpaRepository<CommunityPostReport, Long> {

    boolean existsByPostIdAndReporterId(Long postId, Long reporterId);

    long countByPostIdAndReporterId(Long postId, Long reporterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from CommunityPostReport report where report.id = :id")
    Optional<CommunityPostReport> findByIdForUpdate(@Param("id") Long id);
}
