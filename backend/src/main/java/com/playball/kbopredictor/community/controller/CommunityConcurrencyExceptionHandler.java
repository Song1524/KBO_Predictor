package com.playball.kbopredictor.community.controller;

import com.playball.kbopredictor.common.error.ApiErrorResponse;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice(assignableTypes = CommunityController.class)
public class CommunityConcurrencyExceptionHandler {
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> conflict(PessimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                409, "Conflict", "다른 요청이 게시글을 변경하고 있습니다. 잠시 후 다시 시도해 주세요.",
                Map.of(), LocalDateTime.now()));
    }
}
