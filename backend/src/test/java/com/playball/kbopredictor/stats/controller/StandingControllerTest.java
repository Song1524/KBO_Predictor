package com.playball.kbopredictor.stats.controller;

import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.stats.dto.TeamStandingResponse;
import com.playball.kbopredictor.stats.service.StandingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StandingController.class)
@Import(SecurityConfig.class)
class StandingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StandingService standingService;

    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @Test
    void publicEndpointReturnsTenTeamsInRankOrderWithoutAuthentication() throws Exception {
        when(standingService.getCurrentStandings()).thenReturn(
                IntStream.rangeClosed(1, 10)
                        .mapToObj(this::response)
                        .toList()
        );

        mockMvc.perform(get("/api/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].games").value(100))
                .andExpect(jsonPath("$[0].wins").value(50))
                .andExpect(jsonPath("$[9].rank").value(10));
    }

    @Test
    void emptySnapshotReturnsAnEmptyArray() throws Exception {
        when(standingService.getCurrentStandings()).thenReturn(List.of());

        mockMvc.perform(get("/api/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private TeamStandingResponse response(int rank) {
        return new TeamStandingResponse(
                rank,
                (long) rank,
                "팀 " + rank,
                100,
                50,
                48,
                2,
                new BigDecimal("0.510"),
                BigDecimal.valueOf(rank - 1L).setScale(1),
                "1승",
                LocalDate.of(2026, 8, 13),
                LocalDateTime.of(2026, 8, 14, 6, 20)
        );
    }
}
