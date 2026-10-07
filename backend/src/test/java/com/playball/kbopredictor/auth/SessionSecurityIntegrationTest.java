package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    private Long id;
    private String email;

    @BeforeEach
    void createUser() {
        email = "session-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        id = users.saveAndFlush(User.createLocal(email, passwords.encode("password123!"), email,
                null, LocalDateTime.now())).getId();
    }

    @AfterEach
    void cleanOwnUser() {
        jdbc.update("delete from point_histories where user_id = ?", id);
        jdbc.update("delete from users where id = ?", id);
    }

    @Test
    void successfulLogoutInvalidatesServerSession() throws Exception {
        var session = login();
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionLogoutRequiresAuthenticationAndMeConfirmsAbsence() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrf())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
        mvc.perform(get("/api/auth/me").with(request -> {
            request.setRequestedSessionId("expired-id");
            request.setRequestedSessionIdValid(false);
            return request;
        })).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void csrfRejectedLogoutKeepsServerSessionAlive() throws Exception {
        var session = login();
        mvc.perform(post("/api/auth/logout").session(session).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden()).andExpect(header().string("X-Auth-Error", "CSRF_INVALID"));
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void userPermissionDenialIsNotCsrfFailureAndSessionRemainsValid() throws Exception {
        var session = login();
        mvc.perform(get("/api/admin/dashboard/summary").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void adminRoleRemovalRevokesExistingAdminSessionBeforeController() throws Exception {
        jdbc.update("update users set role = 'ADMIN' where id = ?", id);
        var session = login();
        mvc.perform(get("/api/admin/dashboard/summary").session(session)).andExpect(status().isOk());
        jdbc.update("update users set role = 'USER' where id = ?", id);
        mvc.perform(get("/api/admin/dashboard/summary").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(session.isInvalid()).isTrue();
        // Reauthentication obtains the new USER authority.
        var newSession = login();
        mvc.perform(get("/api/admin/dashboard/summary").session(newSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(newSession)).andExpect(status().isOk());
    }

    @Test
    void inactiveUserSessionIsRevokedOnNextRequest() throws Exception {
        var session = login();
        jdbc.update("update users set status = 'INACTIVE' where id = ?", id);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void withdrawnUserCannotUseAnExistingSessionOrLoginAgain() throws Exception {
        var session = login();
        jdbc.update("update users set status = 'DELETED' where id = ?", id);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(credentials())).andExpect(status().isUnauthorized());
    }

    @Test
    void unchangedUserSessionKeepsNormalAuthenticatedAccess() throws Exception {
        var session = login();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/points/me/history").session(session)).andExpect(status().isOk());
    }

    private String credentials() { return "{\"email\":\"" + email + "\",\"password\":\"password123!\"}"; }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(credentials())).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
