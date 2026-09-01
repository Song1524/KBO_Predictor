package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.game.controller.GameDataAdminController;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameDataAdminController.class)
@Import(SecurityConfig.class)
class GameDataAdminWebTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 12);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @MockitoBean
    private GameSyncService gameSyncService;

    @Test
    void unauthenticatedUserCannotSyncGames() throws Exception {
        mockMvc.perform(post("/api/admin/data/games/sync")
                        .with(csrf())
                        .queryParam("date", TARGET_DATE.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalUserCannotSyncGames() throws Exception {
        mockMvc.perform(post("/api/admin/data/games/sync")
                        .with(csrf())
                        .queryParam("date", TARGET_DATE.toString())
                        .with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSyncGames() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        when(gameSyncService.sync(TARGET_DATE)).thenReturn(
                new GameSyncResponse(
                        TARGET_DATE,
                        5,
                        5,
                        5,
                        0,
                        0,
                        List.of(),
                        now,
                        now.plusSeconds(1)
                )
        );

        mockMvc.perform(post("/api/admin/data/games/sync")
                        .with(csrf())
                        .queryParam("date", TARGET_DATE.toString())
                        .with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(5))
                .andExpect(jsonPath("$.failedCount").value(0));

        verify(gameSyncService).sync(TARGET_DATE);
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
