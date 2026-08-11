package com.playball.kbopredictor.stats.controller;

import com.playball.kbopredictor.stats.collection.StartingPitcherSyncResponse;
import com.playball.kbopredictor.stats.collection.StartingPitcherSyncService;
import com.playball.kbopredictor.stats.collection.TeamStatsSyncResponse;
import com.playball.kbopredictor.stats.collection.TeamStatsSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/data")
public class PregameDataAdminController {

    private final TeamStatsSyncService teamStatsSyncService;
    private final StartingPitcherSyncService startingPitcherSyncService;

    @PostMapping("/team-stats/sync")
    public ResponseEntity<TeamStatsSyncResponse> syncTeamStats() {
        return ResponseEntity.ok(teamStatsSyncService.syncToday());
    }

    @PostMapping("/starting-pitchers/sync")
    public ResponseEntity<StartingPitcherSyncResponse> syncStartingPitchers(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        return ResponseEntity.ok(date == null
                ? startingPitcherSyncService.syncToday()
                : startingPitcherSyncService.sync(date));
    }
}
