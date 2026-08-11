package com.playball.kbopredictor.auth.service;

import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.exception.SignupBadRequestException;
import com.playball.kbopredictor.auth.exception.SignupConflictException;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import com.playball.kbopredictor.user.config.UserRegistrationProperties;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock UserRepository userRepository;
    @Mock TeamRepository teamRepository;
    @Mock PointService pointService;

    private BCryptPasswordEncoder passwordEncoder;
    private SignupService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        UserRegistrationProperties properties = new UserRegistrationProperties();
        properties.setInitialPoints(1000);
        service = new SignupService(
                userRepository, teamRepository, passwordEncoder, pointService,
                properties,
                Clock.fixed(Instant.parse("2026-08-11T03:00:00Z"),
                        ZoneId.of("Asia/Seoul"))
        );
    }

    @Test
    void createsOnlyActiveLocalUserRoleAndUsesBcrypt() {
        Team team = mock(Team.class);
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            return user;
        });
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.changePoint(invocation.getArgument(1));
            return null;
        }).when(pointService).grantSignupBonus(any(User.class), eq(1000));

        var response = service.signup(new SignupRequest(
                " USER@Example.com ", "password123", " 야구팬 ", 1L
        ));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User user = captor.getValue();
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getNickname()).isEqualTo("야구팬");
        assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
        assertThat(user.getPassword()).isNotEqualTo("password123");
        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getProvider()).isEqualTo("LOCAL");
        assertThat(user.getFavoriteTeam()).isSameAs(team);
        assertThat(response.point()).isEqualTo(1000);
        verify(pointService).grantSignupBonus(user, 1000);
    }

    @Test
    void allowsMissingFavoriteTeam() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.signup(new SignupRequest(
                "user@example.com", "password123", "야구팬", null
        ));

        verifyNoInteractions(teamRepository);
    }

    @Test
    void rejectsDuplicateEmailAndNickname() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(new SignupRequest(
                "user@example.com", "password123", "야구팬", null
        ))).isInstanceOf(SignupConflictException.class)
                .hasMessageContaining("이메일");

        reset(userRepository);
        when(userRepository.existsByNickname("야구팬")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(new SignupRequest(
                "other@example.com", "password123", "야구팬", null
        ))).isInstanceOf(SignupConflictException.class)
                .hasMessageContaining("닉네임");
    }

    @Test
    void rejectsUnknownFavoriteTeam() {
        when(teamRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signup(new SignupRequest(
                "user@example.com", "password123", "야구팬", 999L
        ))).isInstanceOf(SignupBadRequestException.class)
                .hasMessageContaining("응원팀");
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(pointService);
    }

    @Test
    void databaseUniqueViolationIsConvertedToConflict() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate", new RuntimeException("uk_users_email")
                )
        );

        assertThatThrownBy(() -> service.signup(new SignupRequest(
                "user@example.com", "password123", "야구팬", null
        ))).isInstanceOf(SignupConflictException.class);
        verifyNoInteractions(pointService);
    }
}
