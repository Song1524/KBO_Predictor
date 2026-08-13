package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.dto.SettlementCorrectionRequest;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionResponse;
import com.playball.kbopredictor.prediction.service.SettlementCorrectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/predictions/settlement-corrections")
public class SettlementCorrectionAdminController {

    private final SettlementCorrectionService settlementCorrectionService;

    @PostMapping("/{predictionId}")
    public ResponseEntity<SettlementCorrectionResponse> correct(
            @PathVariable Long predictionId,
            @Valid @RequestBody SettlementCorrectionRequest request
    ) {
        return ResponseEntity.ok(
                settlementCorrectionService.correct(predictionId, request)
        );
    }
}
