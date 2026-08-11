package com.playball.kbopredictor.auth.security;

import org.springframework.security.core.GrantedAuthority;

import java.io.Serial;
import java.util.Collection;

public final class AuthenticatedUser
        extends org.springframework.security.core.userdetails.User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;

    public AuthenticatedUser(
            Long userId,
            String email,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(email, password, enabled, true, true, true, authorities);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
