package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.dto.UserPredictionResponse;
import com.playball.kbopredictor.prediction.service.UserPredictionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user-predictions")
public class UserPredictionController {

    private final UserPredictionService userPredictionService;

    @PostMapping
    public ResponseEntity<UserPredictionResponse> createPrediction(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody UserPredictionRequest request
    ) {
        UserPredictionResponse response =
                userPredictionService.createPrediction(
                        authenticatedUser.getUserId(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<UserPredictionResponse>> getMyPredictions(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(
                userPredictionService.getPredictionsByUserId(
                        authenticatedUser.getUserId()
                )
        );
    }
}
