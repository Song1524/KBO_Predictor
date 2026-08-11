package com.playball.kbopredictor.admin.controller;

import com.playball.kbopredictor.admin.dto.AdminDashboardSummaryResponse;
import com.playball.kbopredictor.admin.service.AdminDashboardService;
import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
class AdminDashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean KboUserDetailsService userDetailsService;
    @MockitoBean AdminDashboardService dashboardService;

    @Test
    void unauthenticatedAndUserRoleCannotReadSummary() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadSummary() throws Exception {
        when(dashboardService.getSummary()).thenReturn(new AdminDashboardSummaryResponse(
                LocalDate.of(2026, 8, 11),
                5, 2, 1, 2, 0,
                4, 4, 7,
                "baseline-v1", "logistic-v1", "HASH"
        ));

        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGameCount").value(5))
                .andExpect(jsonPath("$.productionModelVersion").value("baseline-v1"))
                .andExpect(jsonPath("$.shadowModelVersion").value("logistic-v1"));
    }

    private AuthenticatedUser principal(String role) {
        return new AuthenticatedUser(
                1L, "admin@test.com", "password", true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
