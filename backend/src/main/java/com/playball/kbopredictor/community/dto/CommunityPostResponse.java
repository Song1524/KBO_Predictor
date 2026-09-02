package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.entity.CommunityReactionType;

import java.time.LocalDateTime;

public record CommunityPostResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        String authorNickname,
        long viewCount,
        long commentCount,
        long likeCount,
        long dislikeCount,
        CommunityReactionType myReaction,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityPostResponse from(
            CommunityPost post,
            long commentCount,
            CommunityReactionResponse reaction
    ) {
        return new CommunityPostResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getUser().getId(),
                post.getUser().getNickname(),
                post.getViewCount(),
                commentCount,
                reaction.likeCount(),
                reaction.dislikeCount(),
                reaction.myReaction(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
