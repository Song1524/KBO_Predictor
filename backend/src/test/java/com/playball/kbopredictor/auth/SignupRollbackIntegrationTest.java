package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.service.SignupService;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
class SignupRollbackIntegrationTest {

    @Autowired SignupService signupService;
    @Autowired UserRepository userRepository;

    @MockitoBean PointHistoryRepository pointHistoryRepository;

    @Test
    void pointHistoryFailureRollsBackNewUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "rollback-" + suffix + "@example.com";
        when(pointHistoryRepository.save(any(PointHistory.class)))
                .thenThrow(new IllegalStateException("history write failed"));

        assertThatThrownBy(() -> signupService.signup(new SignupRequest(
                email, "password123", "롤백팬" + suffix, null
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("history write failed");

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }
}
