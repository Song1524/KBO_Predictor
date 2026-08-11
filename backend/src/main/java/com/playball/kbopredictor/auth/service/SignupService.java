package com.playball.kbopredictor.auth.service;

import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.exception.SignupBadRequestException;
import com.playball.kbopredictor.auth.exception.SignupConflictException;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import com.playball.kbopredictor.user.config.UserRegistrationProperties;
import com.playball.kbopredictor.user.dto.UserResponse;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PasswordEncoder passwordEncoder;
    private final PointService pointService;
    private final UserRegistrationProperties properties;
    private final Clock clock;

    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String nickname = request.nickname().trim();
        rejectDuplicates(email, nickname);
        Team favoriteTeam = favoriteTeam(request.favoriteTeamId());
        User user = User.createLocal(
                email,
                passwordEncoder.encode(request.password()),
                nickname,
                favoriteTeam,
                LocalDateTime.now(clock)
        );
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw concurrentDuplicate(exception);
        }
        pointService.grantSignupBonus(user, properties.getInitialPoints());
        return UserResponse.from(user);
    }

    public String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void rejectDuplicates(String email, String nickname) {
        if (userRepository.existsByEmail(email)) {
            throw new SignupConflictException(
                    "email", "이미 사용 중인 이메일입니다."
            );
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new SignupConflictException(
                    "nickname", "이미 사용 중인 닉네임입니다."
            );
        }
    }

    private Team favoriteTeam(Long favoriteTeamId) {
        if (favoriteTeamId == null) return null;
        return teamRepository.findById(favoriteTeamId).orElseThrow(
                () -> new SignupBadRequestException(
                        "favoriteTeamId", "존재하지 않는 응원팀입니다."
                )
        );
    }

    private SignupConflictException concurrentDuplicate(
            DataIntegrityViolationException exception
    ) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage())
                .toLowerCase(Locale.ROOT);
        if (message.contains("nickname")) {
            return new SignupConflictException(
                    "nickname", "이미 사용 중인 닉네임입니다."
            );
        }
        return new SignupConflictException(
                "email", "이미 사용 중인 이메일입니다."
        );
    }
}
