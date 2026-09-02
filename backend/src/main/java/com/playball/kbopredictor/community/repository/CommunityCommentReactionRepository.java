package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.entity.CommunityCommentReaction;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityCommentReactionRepository
        extends JpaRepository<CommunityCommentReaction, Long> {

    Optional<CommunityCommentReaction> findByCommentIdAndUserId(
            Long commentId,
            Long userId
    );

    List<CommunityCommentReaction> findByCommentIdInAndUserId(
            Collection<Long> commentIds,
            Long userId
    );

    long countByCommentIdAndUserId(Long commentId, Long userId);

    @Query("""
            select reaction.comment.id as targetId,
                   reaction.reactionType as reactionType,
                   count(reaction.id) as reactionCount
            from CommunityCommentReaction reaction
            where reaction.comment.id in :commentIds
            group by reaction.comment.id, reaction.reactionType
            """)
    List<ReactionCount> countByCommentIds(
            @Param("commentIds") Collection<Long> commentIds
    );

    interface ReactionCount {
        Long getTargetId();

        CommunityReactionType getReactionType();

        long getReactionCount();
    }
}
