package com.playball.kbopredictor.stats.controller;

import com.playball.kbopredictor.stats.dto.TeamStatResponse;
import com.playball.kbopredictor.stats.service.TeamStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/teams")
public class TeamStatController {

    private final TeamStatService teamStatService;

    @GetMapping("/{teamId}/stats/latest")
    public ResponseEntity<TeamStatResponse> getLatestTeamStat(
            @PathVariable Long teamId
    ) {
        return ResponseEntity.ok(
                teamStatService.getLatestTeamStat(teamId)
        );
    }
}