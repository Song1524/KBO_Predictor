package com.playball.kbopredictor.prediction.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.prediction.evaluation.ModelComparisonResponse;
import com.playball.kbopredictor.prediction.evaluation.PredictionModelComparisonService;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingResult;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingService;
import com.playball.kbopredictor.prediction.shadow.GameModelComparisonResponse;
import com.playball.kbopredictor.prediction.shadow.GameModelComparisonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PredictionModelAdminController.class)
@Import(SecurityConfig.class)
class PredictionModelAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;
    @MockitoBean
    private BaselineV2TrainingService trainingService;
    @MockitoBean
    private PredictionModelComparisonService comparisonService;
    @MockitoBean
    private GameModelComparisonService gameModelComparisonService;

    @Test
    void modelEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/models/baseline-v2/train"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/predictions/models/comparison")
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanTrainAndCompareWithoutActivatingV2() throws Exception {
        when(trainingService.train()).thenReturn(
                mock(BaselineV2TrainingResult.class)
        );
        when(comparisonService.compare(false)).thenReturn(
                mock(ModelComparisonResponse.class)
        );

        mockMvc.perform(post("/api/admin/predictions/models/baseline-v2/train")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/predictions/models/comparison")
                        .queryParam("includeWalkForward", "false")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk());

        verify(trainingService).train();
        verify(comparisonService).compare(false);
    }

    @Test
    void adminCanCompareModelsForOneGame() throws Exception {
        when(gameModelComparisonService.compare(10L))
                .thenReturn(mock(GameModelComparisonResponse.class));

        mockMvc.perform(get("/api/admin/predictions/models/comparison/10")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk());

        verify(gameModelComparisonService).compare(10L);
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
