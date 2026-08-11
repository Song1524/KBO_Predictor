package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.dto.SystemPredictionResponse;
import com.playball.kbopredictor.prediction.service.SystemPredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/games")
public class SystemPredictionController {

    private final SystemPredictionService systemPredictionService;

    @GetMapping("/{gameId}/prediction")
    public ResponseEntity<SystemPredictionResponse> getPrediction(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(
                systemPredictionService.getPredictionByGameId(gameId)
        );
    }
}