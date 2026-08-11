package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.stats.controller.PregameDataAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PregameDataAdminController.class)
@Import(SecurityConfig.class)
class PregameDataAdminWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private TeamStatsSyncService teamStatsSyncService;

    @MockitoBean
    private StartingPitcherSyncService startingPitcherSyncService;

    @Test
    void nonAdminCannotRunPregameSync() throws Exception {
        mockMvc.perform(post("/api/admin/data/team-stats/sync"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/data/team-stats/sync")
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSyncTeamStatsAndStartingPitchers() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 10);
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        when(teamStatsSyncService.syncToday()).thenReturn(
                new TeamStatsSyncResponse(
                        today, 10, 10, 0, 0, List.of(), now, now
                )
        );
        when(startingPitcherSyncService.sync(today)).thenReturn(
                new StartingPitcherSyncResponse(
                        today, 5, 10, 10, 0, 10, 0, List.of(), now, now
                )
        );

        mockMvc.perform(post("/api/admin/data/team-stats/sync")
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTeamCount").value(10));

        mockMvc.perform(post("/api/admin/data/starting-pitchers/sync")
                        .queryParam("date", today.toString())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedPitcherCount").value(10));

        verify(teamStatsSyncService).syncToday();
        verify(startingPitcherSyncService).sync(today);
    }

    private AuthenticatedUser principal(String role) {
        return new AuthenticatedUser(
                1L,
                "admin@test.com",
                "encoded-password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
