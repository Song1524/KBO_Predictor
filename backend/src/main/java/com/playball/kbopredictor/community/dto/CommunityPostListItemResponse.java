package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityPost;

import java.time.LocalDateTime;

public record CommunityPostListItemResponse(
        Long id,
        String title,
        Long authorId,
        String authorNickname,
        long viewCount,
        long commentCount,
        long likeCount,
        long dislikeCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityPostListItemResponse from(
            CommunityPost post,
            long commentCount,
            CommunityReactionResponse reaction
    ) {
        return new CommunityPostListItemResponse(
                post.getId(),
                post.getTitle(),
                post.getUser().getId(),
                post.getUser().getNickname(),
                post.getViewCount(),
                commentCount,
                reaction.likeCount(),
                reaction.dislikeCount(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
