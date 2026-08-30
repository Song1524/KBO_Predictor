package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionRequest;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionResponse;
import com.playball.kbopredictor.prediction.dto.GameSettlementStatusResponse;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementRollbackResponse;
import com.playball.kbopredictor.prediction.dto.SettlementRollbackRequest;
import com.playball.kbopredictor.prediction.service.GameSettlementRecoveryService;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/games")
public class PredictionSettlementController {

    private final PredictionSettlementService predictionSettlementService;
    private final GameSettlementRecoveryService recoveryService;

    @GetMapping("/{gameId}/settlement/status")
    public ResponseEntity<GameSettlementStatusResponse> getSettlementStatus(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(recoveryService.getStatus(gameId));
    }

    @PostMapping("/{gameId}/settlement")
    public ResponseEntity<PredictionSettlementResponse> settleGame(
            @PathVariable Long gameId,
            @RequestParam(required = false) Integer rollbackRevision,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(
                predictionSettlementService.settleGame(
                        gameId,
                        authenticatedUser.getUserId(),
                        rollbackRevision
                )
        );
    }

    @PostMapping("/{gameId}/settlement/rollback")
    public ResponseEntity<PredictionSettlementRollbackResponse> rollback(
            @PathVariable Long gameId,
            @Valid @RequestBody SettlementRollbackRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(recoveryService.rollback(
                gameId,
                request.settlementRevision(),
                authenticatedUser.getUserId(),
                request.reason()
        ));
    }

    @PutMapping("/{gameId}/result")
    public ResponseEntity<GameResultCorrectionResponse> correctResult(
            @PathVariable Long gameId,
            @Valid @RequestBody GameResultCorrectionRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(recoveryService.correctResult(
                gameId,
                authenticatedUser.getUserId(),
                request
        ));
    }
}
