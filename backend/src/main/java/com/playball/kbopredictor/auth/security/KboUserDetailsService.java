package com.playball.kbopredictor.auth.security;

import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KboUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email)
                .filter(candidate -> candidate.getPassword() != null)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "이메일 또는 비밀번호가 올바르지 않습니다."
                ));

        String role = user.getRole() == null || user.getRole().isBlank()
                ? "USER"
                : user.getRole();
        String authority = role.startsWith("ROLE_")
                ? role
                : "ROLE_" + role;

        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                "ACTIVE".equalsIgnoreCase(user.getStatus()),
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
