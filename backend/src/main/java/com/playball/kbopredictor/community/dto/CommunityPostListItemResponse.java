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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityPostListItemResponse from(
            CommunityPost post,
            long commentCount
    ) {
        return new CommunityPostListItemResponse(
                post.getId(),
                post.getTitle(),
                post.getUser().getId(),
                post.getUser().getNickname(),
                post.getViewCount(),
                commentCount,
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
