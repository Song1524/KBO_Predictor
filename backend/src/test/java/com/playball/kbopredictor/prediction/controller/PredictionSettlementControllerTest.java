package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionRequest;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionResponse;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementRollbackResponse;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.service.GameSettlementRecoveryService;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PredictionSettlementController.class)
@Import(SecurityConfig.class)
class PredictionSettlementControllerTest {

    private static final Long GAME_ID = 10L;
    private static final String SETTLEMENT_URL =
            "/api/admin/games/{gameId}/settlement";
    private static final String ROLLBACK_URL =
            "/api/admin/games/{gameId}/settlement/rollback";
    private static final String CORRECTION_URL =
            "/api/admin/games/{gameId}/result";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private PredictionSettlementService predictionSettlementService;

    @MockitoBean
    private GameSettlementRecoveryService recoveryService;

    @Test
    void unauthenticatedUserCannotSettleGame() throws Exception {
        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID).with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(predictionSettlementService);
    }

    @Test
    void normalUserCannotSettleGame() throws Exception {
        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(predictionSettlementService);
    }

    @Test
    void adminCanSettleGame() throws Exception {
        when(predictionSettlementService.settleGame(GAME_ID, 1L, null)).thenReturn(
                new PredictionSettlementResponse(
                        GAME_ID,
                        1,
                        GameResult.HOME_WIN,
                        false,
                        1L,
                        "LG",
                        3,
                        2,
                        1,
                        0,
                        200L
                )
        );

        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(GAME_ID))
                .andExpect(jsonPath("$.result").value("HOME_WIN"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.totalPaidPoints").value(200));

        verify(predictionSettlementService).settleGame(GAME_ID, 1L, null);
    }

    @Test
    void adminResettlementPassesRollbackRevision() throws Exception {
        when(predictionSettlementService.settleGame(GAME_ID, 1L, 1)).thenReturn(
                new PredictionSettlementResponse(
                        GAME_ID,
                        2,
                        GameResult.AWAY_WIN,
                        false,
                        2L,
                        "KT",
                        3,
                        1,
                        2,
                        0,
                        200L
                )
        );

        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID)
                        .param("rollbackRevision", "1")
                        .with(csrf())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementRevision").value(2));

        verify(predictionSettlementService).settleGame(GAME_ID, 1L, 1);
    }

    @Test
    void legacySettlementUrlIsDeniedByDefault() throws Exception {
        mockMvc.perform(post("/api/games/{gameId}/settlement", GAME_ID)
                        .with(csrf())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(predictionSettlementService);
    }

    @Test
    void unauthenticatedUserCannotRollbackSettlement() throws Exception {
        mockMvc.perform(post(ROLLBACK_URL, GAME_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rollbackRequest()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void normalUserCannotRollbackSettlement() throws Exception {
        mockMvc.perform(post(ROLLBACK_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rollbackRequest()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void adminCanRollbackSettlement() throws Exception {
        LocalDateTime rolledBackAt = LocalDateTime.of(2026, 8, 29, 12, 0);
        when(recoveryService.rollback(
                GAME_ID, 1, 1L, "공식 기록 정정"
        )).thenReturn(new PredictionSettlementRollbackResponse(
                GAME_ID,
                1,
                false,
                3,
                2,
                400L,
                1L,
                rolledBackAt
        ));

        mockMvc.perform(post(ROLLBACK_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rollbackRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(GAME_ID))
                .andExpect(jsonPath("$.settlementRevision").value(1))
                .andExpect(jsonPath("$.alreadyRolledBack").value(false))
                .andExpect(jsonPath("$.reversedPointTotal").value(400));

        verify(recoveryService).rollback(
                GAME_ID, 1, 1L, "공식 기록 정정"
        );
    }

    @Test
    void normalUserCannotCorrectGameResult() throws Exception {
        mockMvc.perform(put(CORRECTION_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionRequest()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void adminCanCorrectGameResult() throws Exception {
        LocalDateTime correctedAt = LocalDateTime.of(2026, 8, 29, 12, 5);
        var request = new GameResultCorrectionRequest(
                1,
                GameStatus.FINISHED,
                2,
                5,
                null,
                "공식 기록 정정"
        );
        when(recoveryService.correctResult(GAME_ID, 1L, request)).thenReturn(
                new GameResultCorrectionResponse(
                        GAME_ID,
                        1,
                        GameStatus.FINISHED,
                        GameResult.AWAY_WIN,
                        2,
                        5,
                        2L,
                        "KT",
                        1L,
                        "공식 기록 정정",
                        correctedAt
                )
        );

        mockMvc.perform(put(CORRECTION_URL, GAME_ID)
                        .with(csrf())
                        .with(user(principal("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("AWAY_WIN"))
                .andExpect(jsonPath("$.correctedByUserId").value(1));

        verify(recoveryService).correctResult(GAME_ID, 1L, request);
    }

    @Test
    void adminSettlementWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID)
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(predictionSettlementService);
    }

    private String rollbackRequest() {
        return """
                {
                  "settlementRevision": 1,
                  "reason": "공식 기록 정정"
                }
                """;
    }

    private String correctionRequest() {
        return """
                {
                  "settlementRevision": 1,
                  "status": "FINISHED",
                  "homeScore": 2,
                  "awayScore": 5,
                  "reason": "공식 기록 정정"
                }
                """;
    }

    private AuthenticatedUser principal(String role) {
        return new AuthenticatedUser(
                1L,
                "user@test.com",
                "encoded-password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
