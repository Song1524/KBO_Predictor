package com.playball.kbopredictor.ranking.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.dto.RankingResponse;
import com.playball.kbopredictor.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rankings")
public class RankingController {

    private final RankingService rankingService;

    @GetMapping
    public ResponseEntity<RankingResponse> getRankings(
            @RequestParam(defaultValue = "TOTAL_POINT") RankingType type,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        Long userId = authenticatedUser == null
                ? null
                : authenticatedUser.getUserId();
        return ResponseEntity.ok(
                rankingService.getRankings(type, limit, userId)
        );
    }
}
