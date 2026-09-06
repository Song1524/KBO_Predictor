package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.repository.CommunityPostReactionRepository;

import java.time.LocalDateTime;

public record CommunityPopularPostResponse(
        Long id,
        String title,
        String authorNickname,
        LocalDateTime createdAt,
        long likeCount,
        long commentCount,
        long viewCount
) {
    public static CommunityPopularPostResponse from(
            CommunityPostReactionRepository.PopularPostCandidate candidate,
            long commentCount
    ) {
        return new CommunityPopularPostResponse(
                candidate.getId(),
                candidate.getTitle(),
                candidate.getAuthorNickname(),
                candidate.getCreatedAt(),
                candidate.getLikeCount(),
                commentCount,
                candidate.getViewCount()
        );
    }
}
