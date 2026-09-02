package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityPostReaction;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    interface ReactionCount {
        Long getTargetId();

        CommunityReactionType getReactionType();

        long getReactionCount();
    }
}
