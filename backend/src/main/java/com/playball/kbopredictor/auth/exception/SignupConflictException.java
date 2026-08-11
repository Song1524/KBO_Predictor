package com.playball.kbopredictor.auth.exception;

import lombok.Getter;

@Getter
public class SignupConflictException extends RuntimeException {

    private final String field;

    public SignupConflictException(String field, String message) {
        super(message);
        this.field = field;
    }
}
