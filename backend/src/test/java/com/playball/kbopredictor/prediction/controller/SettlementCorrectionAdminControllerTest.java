package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionResponse;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionStatus;
import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;
import com.playball.kbopredictor.prediction.service.SettlementCorrectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementCorrectionAdminController.class)
@Import(SecurityConfig.class)
class SettlementCorrectionAdminControllerTest {

    private static final String PATH =
            "/api/admin/predictions/settlement-corrections/2";
    private static final String BODY = """
            {
              "expectedUserId": 2,
              "expectedExternalGameId": "20260812SSHT0",
              "expectedOutcome": "HOME_WIN",
              "expectedPointAmount": 100,
              "expectedFinalOdds": 2.00,
              "expectedCurrentPoint": 900
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private SettlementCorrectionService settlementCorrectionService;

    @Test
    void endpointRequiresAdminRole() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(BODY)
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanApplySingleSettlementCorrection() throws Exception {
        when(settlementCorrectionService.correct(eq(2L), any()))
                .thenReturn(new SettlementCorrectionResponse(
                        SettlementCorrectionStatus.APPLIED,
                        2L,
                        2L,
                        "20260812SSHT0",
                        true,
                        true,
                        PredictionSettlementStatus.WON,
                        200,
                        1_100
                ));

        mockMvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(BODY)
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.predictionId").value(2))
                .andExpect(jsonPath("$.settlementStatus").value("WON"))
                .andExpect(jsonPath("$.rewardPoint").value(200))
                .andExpect(jsonPath("$.currentPoint").value(1100));
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
