package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReactionType;

public record CommunityReactionResponse(
        long likeCount,
        long dislikeCount,
        CommunityReactionType myReaction
) {
    public static CommunityReactionResponse empty() {
        return new CommunityReactionResponse(0, 0, null);
    }
}
