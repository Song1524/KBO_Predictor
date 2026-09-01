package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.backfill.PredictionBackfillService;
import com.playball.kbopredictor.prediction.backfill.PredictionBackfillResponse;
import com.playball.kbopredictor.prediction.backfill.HistoricalGameSyncSummary;
import com.playball.kbopredictor.prediction.evaluation.BenchmarkEvaluationResponse;
import com.playball.kbopredictor.prediction.evaluation.PredictionEvaluationResponse;
import com.playball.kbopredictor.prediction.evaluation.PredictionEvaluationService;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationResponse;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationService;
import com.playball.kbopredictor.prediction.generation.SystemPredictionGenerationStatus;
import com.playball.kbopredictor.prediction.shadow.ShadowEvaluationResponse;
import com.playball.kbopredictor.prediction.shadow.ShadowEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PredictionAdminController.class)
@Import(SecurityConfig.class)
class PredictionAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private SystemPredictionGenerationService generationService;

    @MockitoBean
    private PredictionEvaluationService evaluationService;

    @MockitoBean
    private PredictionBackfillService backfillService;

    @MockitoBean
    private ShadowEvaluationService shadowEvaluationService;

    @Test
    void predictionAdminApisRequireAdminRole() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/generate")
                        .with(csrf())
                        .queryParam("gameId", "10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/predictions/evaluation")
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/predictions/backfill")
                        .with(csrf())
                        .queryParam("from", "2026-05-01")
                        .queryParam("to", "2026-08-01"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/predictions/shadow/evaluation")
                        .queryParam("from", "2026-08-11")
                        .queryParam("to", "2026-10-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanRunIdempotentHistoricalBackfill() throws Exception {
        java.time.LocalDate from = java.time.LocalDate.of(2026, 5, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 8, 1);
        when(backfillService.backfill(from, to, false)).thenReturn(
                new PredictionBackfillResponse(
                        from,
                        to,
                        HistoricalGameSyncSummary.notRequested(),
                        357,
                        0,
                        357,
                        0,
                        357,
                        0,
                        List.of(),
                        LocalDateTime.of(2026, 8, 10, 12, 0),
                        LocalDateTime.of(2026, 8, 10, 12, 1)
                )
        );

        mockMvc.perform(post("/api/admin/predictions/backfill")
                        .with(csrf())
                        .queryParam("from", "2026-05-01")
                        .queryParam("to", "2026-08-01")
                        .queryParam("syncGames", "false")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishedGameCount").value(357))
                .andExpect(jsonPath("$.snapshotCreatedCount").value(0))
                .andExpect(jsonPath("$.historyExistingCount").value(357));
    }

    @Test
    void adminCanGenerateAndEvaluate() throws Exception {
        when(generationService.generate(10L)).thenReturn(
                new SystemPredictionGenerationResponse(
                        10L,
                        SystemPredictionGenerationStatus.CREATED,
                        PredictionOutcome.HOME_WIN,
                        new BigDecimal("55.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("35.00"),
                        "baseline-v1",
                        new BigDecimal("0.850"),
                        LocalDateTime.of(2026, 8, 10, 12, 0),
                        "created"
                )
        );
        when(evaluationService.evaluate()).thenReturn(
                new PredictionEvaluationResponse(
                        "baseline-v1",
                        java.time.LocalDate.of(2026, 5, 1),
                        java.time.LocalDate.of(2026, 8, 1),
                        10,
                        8,
                        8,
                        2,
                        new BigDecimal("80.00"),
                        new BigDecimal("65.00"),
                        0,
                        8,
                        java.util.Map.of(
                                "HOME_TEAM_ERA", 8,
                                "AWAY_TEAM_ERA", 8
                        ),
                        5,
                        new BigDecimal("62.50"),
                        4,
                        new BigDecimal("75.00"),
                        1,
                        BigDecimal.ZERO.setScale(2),
                        3,
                        new BigDecimal("66.67"),
                        new BigDecimal("0.800000"),
                        new BigDecimal("0.500000"),
                        List.of(new BenchmarkEvaluationResponse(
                                "baseline-v1",
                                8,
                                5,
                                new BigDecimal("62.50")
                        ))
                )
        );

        mockMvc.perform(post("/api/admin/predictions/generate")
                        .with(csrf())
                        .queryParam("gameId", "10")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("baseline-v1"))
                .andExpect(jsonPath("$.homeWinProbability").value(55.00));
        mockMvc.perform(get("/api/admin/predictions/evaluation")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluableGameCount").value(8));
    }

    @Test
    void generateRequiresExactlyOneTarget() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/generate")
                        .with(csrf())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/predictions/generate")
                        .with(csrf())
                        .queryParam("gameId", "10")
                        .queryParam("date", "2026-08-11")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanEvaluateShadowPeriod() throws Exception {
        java.time.LocalDate from = java.time.LocalDate.of(2026, 8, 11);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 31);
        when(shadowEvaluationService.evaluate(from, to))
                .thenReturn(mock(ShadowEvaluationResponse.class));

        mockMvc.perform(get("/api/admin/predictions/shadow/evaluation")
                        .queryParam("from", "2026-08-11")
                        .queryParam("to", "2026-10-31")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk());
    }

    private AuthenticatedUser principal(String role) {
        return new AuthenticatedUser(
                1L,
                "admin@test.com",
                "password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
