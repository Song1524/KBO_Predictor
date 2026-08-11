package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/games")
public class GameOddsController {

    private final GameOddsService gameOddsService;

    @GetMapping("/{gameId}/odds")
    public ResponseEntity<GameOddsResponse> getOdds(
            @PathVariable Long gameId
    ) {
        return ResponseEntity.ok(gameOddsService.getOddsByGameId(gameId));
    }
}
