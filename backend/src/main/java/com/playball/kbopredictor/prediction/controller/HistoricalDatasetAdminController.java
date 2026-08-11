package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.dataset.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/predictions/dataset")
public class HistoricalDatasetAdminController {

    private final HistoricalMlDatasetService datasetService;
    private final HistoricalDatasetQualityService qualityService;
    private final MultiSeasonBaselineEvaluationService evaluationService;

    @GetMapping
    public ResponseEntity<HistoricalMlDatasetResponse> dataset(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(datasetService.load(from, to));
    }

    @GetMapping("/csv")
    public ResponseEntity<String> csv(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        String filename = "kbo-historical-%s-%s.csv".formatted(from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "text/csv;charset=UTF-8"
                ))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\""
                )
                .body(datasetService.toCsv(from, to));
    }

    @GetMapping("/quality")
    public ResponseEntity<HistoricalDatasetQualityResponse> quality(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(qualityService.inspect(from, to));
    }

    @GetMapping("/evaluation")
    public ResponseEntity<MultiSeasonBaselineEvaluationResponse> evaluation(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(evaluationService.evaluate(from, to));
    }
}
