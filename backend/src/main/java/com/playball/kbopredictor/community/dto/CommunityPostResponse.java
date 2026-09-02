package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityPost;

import java.time.LocalDateTime;

public record CommunityPostResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        String authorNickname,
        long viewCount,
        long commentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityPostResponse from(
            CommunityPost post,
            long commentCount
    ) {
        return new CommunityPostResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getUser().getId(),
                post.getUser().getNickname(),
                post.getViewCount(),
                commentCount,
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
