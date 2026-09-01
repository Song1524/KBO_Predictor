package com.playball.kbopredictor.common.config;

import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.prediction.controller.GameOddsController;
import com.playball.kbopredictor.prediction.controller.SystemPredictionController;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import com.playball.kbopredictor.prediction.service.SystemPredictionService;
import com.playball.kbopredictor.stats.controller.TeamStatController;
import com.playball.kbopredictor.stats.service.TeamStatService;
import com.playball.kbopredictor.team.controller.TeamController;
import com.playball.kbopredictor.team.service.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        TeamController.class,
        TeamStatController.class,
        SystemPredictionController.class,
        GameOddsController.class
})
@Import(SecurityConfig.class)
class PublicApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KboUserDetailsService userDetailsService;
    @MockitoBean
    private TeamService teamService;
    @MockitoBean
    private TeamStatService teamStatService;
    @MockitoBean
    private SystemPredictionService systemPredictionService;
    @MockitoBean
    private GameOddsService gameOddsService;

    @Test
    void mainPageGetApisRemainPublic() throws Exception {
        when(teamService.getTeams()).thenReturn(List.of());

        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/teams/1/stats/latest"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/games/1/prediction"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/games/1/odds"))
                .andExpect(status().isOk());
        mockMvc.perform(head("/api/teams"))
                .andExpect(status().isOk());
    }
}
