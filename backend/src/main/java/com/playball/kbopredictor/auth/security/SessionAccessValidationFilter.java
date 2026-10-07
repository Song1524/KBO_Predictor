package com.playball.kbopredictor.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Only a two-column projection is read; no stale authority cache or full user hydration. */
public class SessionAccessValidationFilter extends OncePerRequestFilter {
    private final KboUserDetailsService users;

    public SessionAccessValidationFilter(KboUserDetailsService users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            boolean valid;
            try {
                valid = users.findSessionAccess(principal.getUserId()).filter(access -> {
                    String role = access.getRole();
                    String authority = role == null || role.isBlank() ? "ROLE_USER"
                            : role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    return "ACTIVE".equalsIgnoreCase(access.getStatus())
                            // Spring Security also adds authentication factors (e.g. FACTOR_PASSWORD).
                            && authentication.getAuthorities().stream()
                                    .filter(value -> value.getAuthority().startsWith("ROLE_")).count() == 1
                            && authentication.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals(authority));
                }).isPresent();
            } catch (RuntimeException exception) {
                SecurityErrorResponse.write(response, 503, "SESSION_VALIDATION_UNAVAILABLE", "세션 확인을 일시적으로 수행할 수 없습니다.");
                return;
            }
            if (!valid) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                SecurityErrorResponse.write(response, 401, "SESSION_REVOKED", "계정 상태 또는 권한이 변경되었습니다. 다시 로그인해 주세요.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
