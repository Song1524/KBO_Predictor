package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.backfill.PredictionBackfillResponse;
import com.playball.kbopredictor.prediction.backfill.PredictionBackfillService;
import com.playball.kbopredictor.prediction.evaluation.PredictionEvaluationResponse;
import com.playball.kbopredictor.prediction.evaluation.PredictionEvaluationService;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import com.playball.kbopredictor.prediction.shadow.ShadowEvaluationResponse;
import com.playball.kbopredictor.prediction.shadow.ShadowEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/predictions")
public class PredictionAdminController {

    private final SystemPredictionGenerationService generationService;
    private final PredictionEvaluationService evaluationService;
    private final PredictionBackfillService backfillService;
    private final ShadowEvaluationService shadowEvaluationService;

    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestParam(required = false) Long gameId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        if ((gameId == null) == (date == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "gameId와 date 중 하나만 지정해야 합니다."
            );
        }
        return ResponseEntity.ok(gameId != null
                ? generationService.generate(gameId)
                : generationService.generateForDate(date));
    }

    @PostMapping("/backfill")
    public ResponseEntity<PredictionBackfillResponse> backfill(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "true") boolean syncGames
    ) {
        return ResponseEntity.ok(backfillService.backfill(from, to, syncGames));
    }

    @GetMapping("/evaluation")
    public ResponseEntity<PredictionEvaluationResponse> evaluate(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "baseline-v1") String modelVersion
    ) {
        if ((from == null) != (to == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "from과 to는 함께 지정해야 합니다."
            );
        }
        return ResponseEntity.ok(from == null
                ? evaluationService.evaluate()
                : evaluationService.evaluate(from, to, modelVersion));
    }

    @GetMapping("/shadow/evaluation")
    public ResponseEntity<ShadowEvaluationResponse> evaluateShadow(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(shadowEvaluationService.evaluate(from, to));
    }
}
