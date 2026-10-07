package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.security.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionAccessValidationFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void dbFailureFailsClosedWithoutInvalidatingSession() throws Exception {
        var users = mock(KboUserDetailsService.class);
        var principal = new AuthenticatedUser(1L, "test@example.com", "hash", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        when(users.findSessionAccess(1L)).thenThrow(new IllegalStateException("DB unavailable"));
        new SessionAccessValidationFilter(users).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Auth-Error")).isEqualTo("SESSION_VALIDATION_UNAVAILABLE");
        assertThat(session.isInvalid()).isFalse();
        verifyNoInteractions(chain);
    }

    @Test
    void missingAccountRevokesSession() throws Exception {
        var users = mock(KboUserDetailsService.class);
        var principal = new AuthenticatedUser(1L, "test@example.com", "hash", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        when(users.findSessionAccess(1L)).thenReturn(Optional.empty());
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new SessionAccessValidationFilter(users).doFilter(request, response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
