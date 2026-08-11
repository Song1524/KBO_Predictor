package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.dto.SignupRequest;
import com.playball.kbopredictor.auth.service.SignupService;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
@Transactional
class SignupServiceIntegrationTest {

    @Autowired SignupService signupService;
    @Autowired UserRepository userRepository;
    @Autowired PointHistoryRepository pointHistoryRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void signupPersistsUserAndBonusHistoryInOneTransaction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "signup-" + suffix + "@example.com";
        String nickname = "가입팬" + suffix;

        var response = signupService.signup(new SignupRequest(
                email, "password123", nickname, null
        ));

        var user = userRepository.findByEmail(email).orElseThrow();
        var histories = pointHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        assertThat(response.point()).isEqualTo(1000);
        assertThat(user.getPoint()).isEqualTo(1000);
        assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.getType()).isEqualTo(PointHistoryType.SIGNUP_BONUS);
            assertThat(history.getPointChange()).isEqualTo(1000);
            assertThat(history.getBalanceAfter()).isEqualTo(user.getPoint());
            assertThat(history.getGame()).isNull();
            assertThat(history.getUserPrediction()).isNull();
        });
    }
}
