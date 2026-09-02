package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityCommentRepository
        extends JpaRepository<CommunityComment, Long> {

    @EntityGraph(attributePaths = "user")
    List<CommunityComment> findByPostIdAndStatusOrderByCreatedAtAscIdAsc(
            Long postId,
            CommunityContentStatus status
    );

    @EntityGraph(attributePaths = {"user", "post"})
    Optional<CommunityComment> findByIdAndStatus(
            Long id,
            CommunityContentStatus status
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
