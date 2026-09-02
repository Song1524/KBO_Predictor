package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityComment;

import java.time.LocalDateTime;
import java.util.List;

public record CommunityCommentResponse(
        Long id,
        Long postId,
        Long parentCommentId,
        Long authorId,
        String authorNickname,
        String content,
        boolean deleted,
        boolean edited,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CommunityCommentResponse> replies
) {
    public static CommunityCommentResponse from(CommunityComment comment) {
        return from(comment, List.of());
    }

    public static CommunityCommentResponse from(
            CommunityComment comment,
            List<CommunityCommentResponse> replies
    ) {
        if (comment.isDeleted()) {
            return deleted(comment, replies);
        }
        return new CommunityCommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                comment.getParent() == null
                        ? null
                        : comment.getParent().getId(),
                comment.getUser().getId(),
                comment.getUser().getNickname(),
                comment.getContent(),
                false,
                comment.getUpdatedAt().isAfter(comment.getCreatedAt()),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                List.copyOf(replies)
        );
    }

    private static CommunityCommentResponse deleted(
            CommunityComment comment,
            List<CommunityCommentResponse> replies
    ) {
        return new CommunityCommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                null,
                null,
                null,
                null,
                true,
                false,
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                List.copyOf(replies)
        );
    }
}
