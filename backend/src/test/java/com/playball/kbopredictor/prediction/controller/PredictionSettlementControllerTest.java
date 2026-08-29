package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PredictionSettlementController.class)
@Import(SecurityConfig.class)
class PredictionSettlementControllerTest {

    private static final Long GAME_ID = 10L;
    private static final String SETTLEMENT_URL =
            "/api/admin/games/{gameId}/settlement";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private PredictionSettlementService predictionSettlementService;

    @Test
    void unauthenticatedUserCannotSettleGame() throws Exception {
        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(predictionSettlementService);
    }

    @Test
    void normalUserCannotSettleGame() throws Exception {
        mockMvc.perform(post(SETTLEMENT_URL, GAME_ID)
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(predictionSettlementService);
    }

    @Test
    void adminCanSettleGame() throws Exception {
        when(predictionSettlementService.settleGame(GAME_ID)).thenReturn(
                new PredictionSettlementResponse(
                        GAME_ID,
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
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(GAME_ID))
                .andExpect(jsonPath("$.result").value("HOME_WIN"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.totalPaidPoints").value(200));

        verify(predictionSettlementService).settleGame(GAME_ID);
    }

    @Test
    void legacyPublicSettlementUrlIsNotExposed() throws Exception {
        mockMvc.perform(post("/api/games/{gameId}/settlement", GAME_ID))
                .andExpect(status().isNotFound());

        verifyNoInteractions(predictionSettlementService);
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
