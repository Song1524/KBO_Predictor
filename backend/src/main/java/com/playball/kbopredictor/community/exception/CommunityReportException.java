package com.playball.kbopredictor.community.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class CommunityReportException extends RuntimeException {

    private final HttpStatus status;

    public CommunityReportException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
