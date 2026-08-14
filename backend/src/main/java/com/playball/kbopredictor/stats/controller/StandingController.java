package com.playball.kbopredictor.stats.controller;

import com.playball.kbopredictor.stats.dto.TeamStandingResponse;
import com.playball.kbopredictor.stats.service.StandingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/standings")
public class StandingController {

    private final StandingService standingService;

    @GetMapping
    public ResponseEntity<List<TeamStandingResponse>> getCurrentStandings() {
        return ResponseEntity.ok(standingService.getCurrentStandings());
    }
}
