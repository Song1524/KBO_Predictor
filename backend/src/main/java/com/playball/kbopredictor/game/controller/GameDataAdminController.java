package com.playball.kbopredictor.game.controller;

import com.playball.kbopredictor.game.collection.GameSyncResponse;
import com.playball.kbopredictor.game.collection.GameSyncService;
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
public class GameDataAdminController {

    private final GameSyncService gameSyncService;

    @PostMapping("/games/sync")
    public ResponseEntity<GameSyncResponse> syncGames(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        return ResponseEntity.ok(gameSyncService.sync(date));
    }
}
