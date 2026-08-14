package com.playball.kbopredictor.ranking.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.dto.RankingEntryResponse;
import com.playball.kbopredictor.ranking.dto.RankingResponse;
import com.playball.kbopredictor.ranking.service.RankingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RankingController.class)
@Import(SecurityConfig.class)
class RankingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RankingService rankingService;
    @MockitoBean
    private KboUserDetailsService userDetailsService;

    @Test
    void rankingsArePublicAndDoNotExposePrivateUserFields() throws Exception {
        when(rankingService.getRankings(
                RankingType.TOTAL_POINT, 20, null
        )).thenReturn(response(null));

        mockMvc.perform(get("/api/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankings.length()").value(1))
                .andExpect(jsonPath("$.rankings[0].nickname").value("공개닉네임"))
                .andExpect(jsonPath("$.rankings[0].email").doesNotExist())
                .andExpect(jsonPath("$.rankings[0].loginId").doesNotExist())
                .andExpect(jsonPath("$.myRanking").doesNotExist());
    }

    @Test
    void authenticatedUserReceivesMyRanking() throws Exception {
        AuthenticatedUser principal = new AuthenticatedUser(
                37L,
                "private@example.com",
                "encoded",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                principal.getPassword(),
                principal.getAuthorities()
        );
        when(rankingService.getRankings(
                RankingType.TOTAL_POINT, 20, 37L
        )).thenReturn(response(entry(37, 37)));

        mockMvc.perform(get("/api/rankings")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRanking.rank").value(37))
                .andExpect(jsonPath("$.myRanking.userId").value(37))
                .andExpect(jsonPath("$.myRanking.email").doesNotExist());
    }

    private RankingResponse response(RankingEntryResponse mine) {
        return new RankingResponse(
                RankingType.TOTAL_POINT,
                null,
                null,
                List.of(entry(1, 1)),
                mine
        );
    }

    private RankingEntryResponse entry(long rank, long userId) {
        return new RankingEntryResponse(
                rank,
                userId,
                "공개닉네임",
                1300L,
                null,
                2,
                1,
                new BigDecimal("50.0")
        );
    }
}
