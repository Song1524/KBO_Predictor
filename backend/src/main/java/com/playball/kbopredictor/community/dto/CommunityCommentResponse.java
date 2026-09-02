package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityComment;

import java.time.LocalDateTime;

public record CommunityCommentResponse(
        Long id,
        Long postId,
        Long authorId,
        String authorNickname,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommunityCommentResponse from(CommunityComment comment) {
        return new CommunityCommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                comment.getUser().getId(),
                comment.getUser().getNickname(),
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
