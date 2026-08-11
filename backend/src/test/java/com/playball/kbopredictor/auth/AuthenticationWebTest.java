package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.controller.AuthController;
import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.exception.SignupBadRequestException;
import com.playball.kbopredictor.auth.exception.SignupConflictException;
import com.playball.kbopredictor.auth.service.SignupService;
import com.playball.kbopredictor.common.config.SecurityConfig;
import com.playball.kbopredictor.point.controller.PointController;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.controller.UserPredictionController;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.service.UserPredictionService;
import com.playball.kbopredictor.user.dto.UserResponse;
import com.playball.kbopredictor.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        PointController.class,
        UserPredictionController.class
})
@Import(SecurityConfig.class)
class AuthenticationWebTest {

    private static final Long AUTHENTICATED_USER_ID = 7L;

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
    private UserPredictionService userPredictionService;

    @MockitoBean
    private PointService pointService;

    @Test
    void unauthenticatedPredictionReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/user-predictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(predictionJson(999L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @Test
    void unauthenticatedPointHistoryReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/points/me/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @Test
    void predictionUsesAuthenticatedUserInsteadOfRequestUserId() throws Exception {
        mockMvc.perform(post("/api/user-predictions")
                        .with(user(authenticatedUser("encoded-password")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(predictionJson(999L)))
                .andExpect(status().isCreated());

        verify(userPredictionService).createPrediction(
                eq(AUTHENTICATED_USER_ID),
                argThat((UserPredictionRequest request) ->
                        request.gameId().equals(10L) &&
                                request.selectedOutcome().name().equals("DRAW") &&
                                request.pointAmount().equals(100)
                )
        );
    }

    @Test
    void meEndpointsUseAuthenticatedUser() throws Exception {
        UserResponse response = userResponse();
        when(userService.getUser(AUTHENTICATED_USER_ID)).thenReturn(response);
        when(userPredictionService.getPredictionsByUserId(AUTHENTICATED_USER_ID))
                .thenReturn(List.of());

        AuthenticatedUser principal = authenticatedUser("encoded-password");

        mockMvc.perform(get("/api/auth/me").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AUTHENTICATED_USER_ID))
                .andExpect(jsonPath("$.nickname").value("인증 사용자"));

        mockMvc.perform(get("/api/user-predictions/me").with(user(principal)))
                .andExpect(status().isOk());

        verify(userPredictionService)
                .getPredictionsByUserId(AUTHENTICATED_USER_ID);
    }

    @Test
    void pointHistoryUsesOnlyAuthenticatedUserId() throws Exception {
        when(pointService.getMyHistory(AUTHENTICATED_USER_ID))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/points/me/history")
                        .queryParam("userId", "999")
                        .with(user(authenticatedUser("encoded-password"))))
                .andExpect(status().isOk());

        verify(pointService).getMyHistory(AUTHENTICATED_USER_ID);

        mockMvc.perform(get("/api/points/users/999/history")
                        .with(user(authenticatedUser("encoded-password"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginCreatesSessionThatCanRestoreCurrentUser() throws Exception {
        String encodedPassword = passwordEncoder.encode("test1234!");
        AuthenticatedUser principal = authenticatedUser(encodedPassword);
        when(userDetailsService.loadUserByUsername("test@test.com"))
                .thenReturn(principal);
        when(userService.getUser(AUTHENTICATED_USER_ID))
                .thenReturn(userResponse());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@test.com",
                                  "password": "test1234!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AUTHENTICATED_USER_ID))
                .andReturn();

        MockHttpSession session =
                (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@test.com"));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        when(userDetailsService.loadUserByUsername("test@test.com"))
                .thenReturn(authenticatedUser(passwordEncoder.encode("test1234!")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@test.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupCreatesSessionAndNewUserCanPredictImmediately() throws Exception {
        Long newUserId = 88L;
        UserResponse registered = new UserResponse(
                newUserId, "new@example.com", "새야구팬",
                null, null, 1000, "USER", "ACTIVE"
        );
        when(signupService.signup(any(SignupRequest.class)))
                .thenReturn(registered);
        when(signupService.normalizeEmail("new@example.com"))
                .thenReturn("new@example.com");
        when(userDetailsService.loadUserByUsername("new@example.com"))
                .thenReturn(new AuthenticatedUser(
                        newUserId,
                        "new@example.com",
                        passwordEncoder.encode("password123"),
                        true,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                ));
        when(userService.getUser(newUserId)).thenReturn(registered);

        MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new@example.com",
                                  "password": "password123",
                                  "nickname": "새야구팬",
                                  "favoriteTeamId": null,
                                  "role": "ADMIN",
                                  "point": 999999,
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.point").value(1000))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                signupResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newUserId));
        mockMvc.perform(post("/api/user-predictions")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(predictionJson(999L)))
                .andExpect(status().isCreated());
        verify(userPredictionService).createPrediction(
                eq(newUserId), any(UserPredictionRequest.class)
        );
    }

    @Test
    void signupValidationReturnsConsistentBadRequestJson() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "short",
                                  "nickname": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.nickname").exists());
    }

    @Test
    void duplicateAndUnknownFavoriteTeamHaveExplicitStatuses() throws Exception {
        when(signupService.signup(any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(
                        "email", "이미 사용 중인 이메일입니다."
                ));
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSignupJson(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        reset(signupService);
        when(signupService.signup(any(SignupRequest.class)))
                .thenThrow(new SignupBadRequestException(
                        "favoriteTeamId", "존재하지 않는 응원팀입니다."
                ));
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSignupJson(999L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.favoriteTeamId").exists());
    }

    private AuthenticatedUser authenticatedUser(String encodedPassword) {
        return new AuthenticatedUser(
                AUTHENTICATED_USER_ID,
                "test@test.com",
                encodedPassword,
                true,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    private UserResponse userResponse() {
        return new UserResponse(
                AUTHENTICATED_USER_ID,
                "test@test.com",
                "인증 사용자",
                null,
                null,
                900,
                "USER",
                "ACTIVE"
        );
    }

    private String predictionJson(Long suppliedUserId) {
        return """
                {
                  "userId": %d,
                  "gameId": 10,
                  "selectedOutcome": "DRAW",
                  "pointAmount": 100
                }
                """.formatted(suppliedUserId);
    }

    private String validSignupJson(Long favoriteTeamId) {
        return """
                {
                  "email": "new@example.com",
                  "password": "password123",
                  "nickname": "새야구팬",
                  "favoriteTeamId": %d
                }
                """.formatted(favoriteTeamId);
    }
}
