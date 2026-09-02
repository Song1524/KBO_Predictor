package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.controller.AuthController;
import com.playball.kbopredictor.auth.dto.DailyLoginBonusResult;
import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.auth.service.DailyLoginBonusService;
import com.playball.kbopredictor.auth.service.SignupService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.user.dto.UserResponse;
import com.playball.kbopredictor.user.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class CsrfCookieIntegrationTest {

    private static final Long USER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private KboUserDetailsService userDetailsService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private SignupService signupService;
    @MockitoBean
    private DailyLoginBonusService dailyLoginBonusService;

    @Test
    void issuedCookieAndMatchingHeaderAllowLogin() throws Exception {
        String encodedPassword = passwordEncoder.encode("test1234!");
        when(userDetailsService.loadUserByUsername("test@test.com"))
                .thenReturn(new AuthenticatedUser(
                        USER_ID,
                        "test@test.com",
                        encodedPassword,
                        true,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                ));
        when(userService.getUser(USER_ID)).thenReturn(new UserResponse(
                USER_ID,
                "test@test.com",
                "인증 사용자",
                null,
                null,
                900,
                "USER",
                "ACTIVE"
        ));
        when(dailyLoginBonusService.grantIfEligible(USER_ID))
                .thenReturn(DailyLoginBonusResult.notGranted());

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@test.com",
                                  "password": "test1234!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@test.com",
                                  "password": "test1234!"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
