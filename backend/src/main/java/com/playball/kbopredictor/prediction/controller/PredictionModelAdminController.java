package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.evaluation.ModelComparisonResponse;
import com.playball.kbopredictor.prediction.evaluation.PredictionModelComparisonService;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingResult;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingService;
import com.playball.kbopredictor.prediction.shadow.GameModelComparisonResponse;
import com.playball.kbopredictor.prediction.shadow.GameModelComparisonService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/predictions/models")
public class PredictionModelAdminController {

    private final BaselineV2TrainingService trainingService;
    private final PredictionModelComparisonService comparisonService;
    private final GameModelComparisonService gameModelComparisonService;

    @PostMapping("/baseline-v2/train")
    public ResponseEntity<BaselineV2TrainingResult> train(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) Integer candidateCount,
            @RequestParam(required = false) Long seed
    ) {
        if (from == null && to == null
                && candidateCount == null && seed == null) {
            return ResponseEntity.ok(trainingService.train());
        }
        if (from == null || to == null
                || candidateCount == null || seed == null) {
            throw new IllegalArgumentException(
                    "from, to, candidateCount and seed must be supplied together."
            );
        }
        return ResponseEntity.ok(trainingService.train(
                from, to, candidateCount, seed
        ));
    }

    @GetMapping("/comparison")
    public ResponseEntity<ModelComparisonResponse> compare(
            @RequestParam(defaultValue = "true") boolean includeWalkForward
    ) {
        return ResponseEntity.ok(
                comparisonService.compare(includeWalkForward)
        );
    }

    @GetMapping("/comparison/{gameId}")
    public ResponseEntity<GameModelComparisonResponse> compareGame(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(gameModelComparisonService.compare(gameId));
    }
}
