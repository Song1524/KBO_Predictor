package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityReactionType;

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
        long likeCount,
        long dislikeCount,
        CommunityReactionType myReaction,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CommunityCommentResponse> replies
) {
    public static CommunityCommentResponse from(CommunityComment comment) {
        return from(
                comment,
                CommunityReactionResponse.empty(),
                List.of()
        );
    }

    public static CommunityCommentResponse from(
            CommunityComment comment,
            List<CommunityCommentResponse> replies
    ) {
        return from(comment, CommunityReactionResponse.empty(), replies);
    }

    public static CommunityCommentResponse from(
            CommunityComment comment,
            CommunityReactionResponse reaction,
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
                reaction.likeCount(),
                reaction.dislikeCount(),
                reaction.myReaction(),
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
                0,
                0,
                null,
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                List.copyOf(replies)
        );
    }
}
