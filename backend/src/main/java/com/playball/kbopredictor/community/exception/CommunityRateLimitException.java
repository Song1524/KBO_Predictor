package com.playball.kbopredictor.community.exception;

import lombok.Getter;

@Getter
public class CommunityRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public CommunityRateLimitException(
            String message,
            long retryAfterSeconds
    ) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
