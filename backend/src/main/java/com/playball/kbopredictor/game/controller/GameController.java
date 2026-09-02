package com.playball.kbopredictor.game.controller;

import com.playball.kbopredictor.game.dto.GameResponse;
import com.playball.kbopredictor.game.dto.GameStartingPitchersResponse;
import com.playball.kbopredictor.game.service.GameStartingPitcherService;
import com.playball.kbopredictor.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;
    private final GameStartingPitcherService gameStartingPitcherService;

    @GetMapping
    public ResponseEntity<List<GameResponse>> getGamesByDate(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        return ResponseEntity.ok(gameService.getGamesByDate(date));
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<GameResponse> getGame(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(
                gameService.getGame(gameId)
        );
    }

    @GetMapping("/{gameId}/starting-pitchers")
    public ResponseEntity<GameStartingPitchersResponse> getStartingPitchers(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(
                gameStartingPitcherService.getByGameId(gameId)
        );
    }
}
