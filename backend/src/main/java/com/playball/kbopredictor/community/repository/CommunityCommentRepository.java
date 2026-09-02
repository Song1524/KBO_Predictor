package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityCommentRepository
        extends JpaRepository<CommunityComment, Long> {

    @EntityGraph(attributePaths = {"user", "parent"})
    @Query("""
            select comment
            from CommunityComment comment
            where comment.post.id = :postId
              and (
                    comment.status = :activeStatus
                    or (
                        comment.parent is null
                        and exists (
                            select reply.id
                            from CommunityComment reply
                            where reply.parent = comment
                              and reply.status = :activeStatus
                        )
                    )
              )
            order by comment.createdAt asc, comment.id asc
            """)
    List<CommunityComment> findVisibleThreadComments(
            @Param("postId") Long postId,
            @Param("activeStatus") CommunityContentStatus activeStatus
    );

    @EntityGraph(attributePaths = {"user", "post", "parent"})
    Optional<CommunityComment> findByIdAndStatus(
            Long id,
            CommunityContentStatus status
    );

    @EntityGraph(attributePaths = {"post", "parent"})
    @Query("select comment from CommunityComment comment where comment.id = :id")
    Optional<CommunityComment> findByIdWithPostAndParent(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "post", "parent"})
    @Query("""
            select comment
            from CommunityComment comment
            where comment.id = :id
              and comment.status = :status
            """)
    Optional<CommunityComment> findByIdAndStatusForUpdate(
            @Param("id") Long id,
            @Param("status") CommunityContentStatus status
    );

    long countByPostIdAndStatus(
            Long postId,
            CommunityContentStatus status
    );

    @Query("""
            select comment.post.id as postId,
                   count(comment.id) as commentCount
            from CommunityComment comment
            where comment.post.id in :postIds
              and comment.status = :status
            group by comment.post.id
            """)
    List<CommentCount> countByPostIdsAndStatus(
            @Param("postIds") Collection<Long> postIds,
            @Param("status") CommunityContentStatus status
    );

    interface CommentCount {
        Long getPostId();

        long getCommentCount();
    }
}
