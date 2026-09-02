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
        name = "community_comment_reactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_community_comment_reactions_comment_user",
                columnNames = {"comment_id", "user_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityCommentReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private CommunityComment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 10)
    private CommunityReactionType reactionType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static CommunityCommentReaction create(
            CommunityComment comment,
            User user,
            CommunityReactionType reactionType,
            LocalDateTime now
    ) {
        CommunityCommentReaction reaction = new CommunityCommentReaction();
        reaction.comment = comment;
        reaction.user = user;
        reaction.reactionType = reactionType;
        reaction.createdAt = now;
        reaction.updatedAt = now;
        return reaction;
    }

    public void changeTo(
            CommunityReactionType reactionType,
            LocalDateTime now
    ) {
        this.reactionType = reactionType;
        this.updatedAt = now;
    }
}
