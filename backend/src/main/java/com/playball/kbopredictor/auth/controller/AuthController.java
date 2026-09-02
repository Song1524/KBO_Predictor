package com.playball.kbopredictor.auth.controller;

import com.playball.kbopredictor.auth.dto.DailyLoginBonusResult;
import com.playball.kbopredictor.auth.dto.LoginRequest;
import com.playball.kbopredictor.auth.dto.LoginResponse;
import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.service.DailyLoginBonusService;
import com.playball.kbopredictor.auth.service.SignupService;
import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.user.dto.UserResponse;
import com.playball.kbopredictor.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserService userService;
    private final SignupService signupService;
    private final DailyLoginBonusService dailyLoginBonusService;

    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.email().trim().toLowerCase(Locale.ROOT),
                            request.password()
                    )
            );

            AuthenticatedUser principal =
                    (AuthenticatedUser) authentication.getPrincipal();
            DailyLoginBonusResult bonus =
                    dailyLoginBonusService.grantIfEligible(
                            principal.getUserId()
                    );
            UserResponse user = userService.getUser(principal.getUserId());

            establishSession(authentication, servletRequest, servletResponse);
            return ResponseEntity.ok(
                    LoginResponse.from(user, bonus)
            );
        } catch (AuthenticationException exception) {
            log.warn(
                    "Login failed: reason=bad_credentials, remoteAddress={}",
                    servletRequest.getRemoteAddr()
            );
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "이메일 또는 비밀번호가 올바르지 않습니다."
            );
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        UserResponse registered = signupService.signup(request);
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        signupService.normalizeEmail(request.email()),
                        request.password()
                )
        );
        establishSession(authentication, servletRequest, servletResponse);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(
                userService.getUser(authenticatedUser.getUserId())
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) {
        new SecurityContextLogoutHandler().logout(
                request,
                response,
                authentication
        );
        return ResponseEntity.noContent().build();
    }

    private void establishSession(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) existingSession.invalidate();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
