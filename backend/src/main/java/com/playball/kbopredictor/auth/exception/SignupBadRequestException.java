package com.playball.kbopredictor.auth.exception;

import lombok.Getter;

@Getter
public class SignupBadRequestException extends RuntimeException {

    private final String field;

    public SignupBadRequestException(String field, String message) {
        super(message);
        this.field = field;
    }
}
