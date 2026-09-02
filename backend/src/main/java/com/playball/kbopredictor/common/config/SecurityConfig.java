package com.playball.kbopredictor.common.config;

import com.playball.kbopredictor.auth.security.KboUserDetailsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_READ_ENDPOINTS = {
            "/api/auth/csrf",
            "/api/games",
            "/api/games/*",
            "/api/games/*/prediction",
            "/api/games/*/odds",
            "/api/games/*/starting-pitchers",
            "/api/teams",
            "/api/teams/*/stats/latest",
            "/api/standings",
            "/api/rankings"
    };

    private static final String[] PUBLIC_COMMUNITY_READ_ENDPOINTS = {
            "/api/community/posts",
            "/api/community/posts/*",
            "/api/community/posts/*/comments"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            KboUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository
    ) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(
                                new CsrfTokenRequestAttributeHandler()
                        )
                )
                .cors(Customizer.withDefaults())
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
                        )
                        .permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/signup"
                        )
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                PUBLIC_READ_ENDPOINTS
                        )
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.HEAD,
                                PUBLIC_READ_ENDPOINTS
                        )
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                PUBLIC_COMMUNITY_READ_ENDPOINTS
                        )
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.HEAD,
                                PUBLIC_COMMUNITY_READ_ENDPOINTS
                        )
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/community/posts",
                                "/api/community/posts/*/comments"
                        )
                        .authenticated()
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/community/posts/*"
                        )
                        .authenticated()
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/community/posts/*",
                                "/api/community/comments/*"
                        )
                        .authenticated()
                        .requestMatchers(
                                "/api/auth/me",
                                "/api/auth/logout",
                                "/api/user-predictions/**",
                                "/api/points/**"
                        )
                        .authenticated()
                        .anyRequest()
                        .denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType("application/json");
                            response.getWriter().write(
                                    "{\"message\":\"로그인이 필요합니다.\"}"
                            );
                        })
                );

        return http.build();
    }
}
