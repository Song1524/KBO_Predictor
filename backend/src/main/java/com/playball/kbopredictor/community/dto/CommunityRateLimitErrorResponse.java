package com.playball.kbopredictor.community.dto;

public record CommunityRateLimitErrorResponse(
        String message,
        long retryAfterSeconds
) {
}
