package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityPostReaction;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityPostReactionRepository
        extends JpaRepository<CommunityPostReaction, Long> {

    Optional<CommunityPostReaction> findByPostIdAndUserId(
            Long postId,
            Long userId
    );

    List<CommunityPostReaction> findByPostIdInAndUserId(
            Collection<Long> postIds,
            Long userId
    );

    long countByPostIdAndUserId(Long postId, Long userId);

    @Query("""
            select reaction.post.id as targetId,
                   reaction.reactionType as reactionType,
                   count(reaction.id) as reactionCount
            from CommunityPostReaction reaction
            where reaction.post.id in :postIds
            group by reaction.post.id, reaction.reactionType
            """)
    List<ReactionCount> countByPostIds(
            @Param("postIds") Collection<Long> postIds
    );

    @Query("""
            select post.id as id,
                   post.title as title,
                   post.user.nickname as authorNickname,
                   post.createdAt as createdAt,
                   post.viewCount as viewCount,
                   count(reaction.id) as likeCount
            from CommunityPostReaction reaction
            join reaction.post post
            where reaction.reactionType = :reactionType
              and post.status = :status
              and post.createdAt >= :createdAfter
            group by post.id,
                     post.title,
                     post.user.nickname,
                     post.createdAt,
                     post.viewCount
            order by count(reaction.id) desc,
                     post.createdAt desc,
                     post.id desc
            """)
    List<PopularPostCandidate> findPopularPostCandidates(
            @Param("reactionType") CommunityReactionType reactionType,
            @Param("status") CommunityContentStatus status,
            @Param("createdAfter") LocalDateTime createdAfter,
            Pageable pageable
    );

    interface ReactionCount {
        Long getTargetId();

        CommunityReactionType getReactionType();

        long getReactionCount();
    }

    interface PopularPostCandidate {
        Long getId();

        String getTitle();

        String getAuthorNickname();

        LocalDateTime getCreatedAt();

        long getViewCount();

        long getLikeCount();
    }
}
