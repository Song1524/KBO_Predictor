package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CommunityPostRepository
        extends JpaRepository<CommunityPost, Long> {

    @EntityGraph(attributePaths = "user")
    Page<CommunityPost> findByStatus(
            CommunityContentStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "user")
    Optional<CommunityPost> findByIdAndStatus(
            Long id,
            CommunityContentStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    @Query("""
            select post
            from CommunityPost post
            where post.id = :id
              and post.status = :status
            """)
    Optional<CommunityPost> findByIdAndStatusForUpdate(
            @Param("id") Long id,
            @Param("status") CommunityContentStatus status
    );

    @Query("""
            select post
            from CommunityPost post
            where post.user.id = :userId
              and post.createdAt > :createdAfter
            order by post.createdAt desc, post.id desc
            """)
    List<CommunityPost> findRecentByUserId(
            @Param("userId") Long userId,
            @Param("createdAfter") LocalDateTime createdAfter,
            Pageable pageable
    );
}
