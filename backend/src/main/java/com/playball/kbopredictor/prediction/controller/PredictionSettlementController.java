package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/games")
public class PredictionSettlementController {

    private final PredictionSettlementService predictionSettlementService;

    @PostMapping("/{gameId}/settlement")
    public ResponseEntity<PredictionSettlementResponse> settleGame(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(
                predictionSettlementService.settleGame(gameId)
        );
    }
}
