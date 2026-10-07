package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/** Existing MVC tests model a stable DB role. Revocation tests stub explicit changed values. */
public final class SessionAccessTestSupport {
    private SessionAccessTestSupport() {}
    public static void stableRole(KboUserDetailsService users) {
        lenient().when(users.findSessionAccess(anyLong())).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            String role = authentication.getAuthorities().stream()
                    .map(value -> value.getAuthority()).filter(value -> value.startsWith("ROLE_"))
                    .findFirst().orElseThrow();
            return Optional.of(new UserRepository.SessionAccess() {
                public String getRole() { return role; }
                public String getStatus() { return "ACTIVE"; }
            });
        });
    }
}
