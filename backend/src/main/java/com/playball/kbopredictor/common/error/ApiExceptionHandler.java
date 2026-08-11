package com.playball.kbopredictor.common.error;

import com.playball.kbopredictor.auth.exception.SignupBadRequestException;
import com.playball.kbopredictor.auth.exception.SignupConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return response(
                HttpStatus.BAD_REQUEST,
                "입력값을 확인해 주세요.",
                fields
        );
    }

    @ExceptionHandler(SignupConflictException.class)
    public ResponseEntity<ApiErrorResponse> signupConflict(
            SignupConflictException exception
    ) {
        return response(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of(exception.getField(), exception.getMessage())
        );
    }

    @ExceptionHandler(SignupBadRequestException.class)
    public ResponseEntity<ApiErrorResponse> signupBadRequest(
            SignupBadRequestException exception
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                Map.of(exception.getField(), exception.getMessage())
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String message,
            Map<String, String> fields
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                fields,
                LocalDateTime.now()
        ));
    }
}
