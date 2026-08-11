package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.dto.UserPredictionResponse;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPredictionServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 12, 0);

    @Mock
    private UserPredictionRepository userPredictionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameOddsService gameOddsService;

    @Mock
    private PointService pointService;

    private UserPredictionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        service = new UserPredictionService(
                userPredictionRepository,
                userRepository,
                gameRepository,
                gameOddsService,
                pointService,
                clock
        );
    }

    @ParameterizedTest
    @EnumSource(PredictionOutcome.class)
    void acceptsHomeDrawAndAwayOutcomes(PredictionOutcome outcome) {
        Game game = futureGame();
        User user = TestEntities.user(1L, 1_000);
        stubAvailablePrediction(game, user);

        UserPredictionResponse response = service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), outcome, 100)
        );

        assertThat(response.selectedOutcome()).isEqualTo(outcome);
        assertThat(response.gameDate()).isEqualTo(game.getGameDate());
        assertThat(response.homeTeamName()).isEqualTo("홈팀");
        assertThat(response.awayTeamName()).isEqualTo("원정팀");
        assertThat(response.settled()).isFalse();
        assertThat(user.getPoint()).isEqualTo(900);
        verify(gameOddsService).placeBet(game, outcome, 100);
    }

    @Test
    void blocksDuplicateParticipation() {
        Game game = futureGame();
        User user = TestEntities.user(1L, 1_000);
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(userRepository.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(userPredictionRepository.existsByUserIdAndGameId(user.getId(), game.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), PredictionOutcome.DRAW, 100)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );

        verifyNoInteractions(gameOddsService);
        assertThat(user.getPoint()).isEqualTo(1_000);
    }

    @Test
    void blocksParticipationWhenPointsAreInsufficient() {
        Game game = futureGame();
        User user = TestEntities.user(1L, 50);
        stubAvailablePrediction(game, user);
        doThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "보유 포인트가 부족합니다."
        )).when(pointService).useForPrediction(
                eq(user),
                any(UserPrediction.class)
        );

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), PredictionOutcome.HOME_WIN, 100)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(gameOddsService).placeBet(
                game,
                PredictionOutcome.HOME_WIN,
                100
        );
    }

    @Test
    void blocksParticipationFromThirtyMinutesBeforeGameStart() {
        Game game = TestEntities.game(
                10L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate(),
                NOW.toLocalTime().plusMinutes(20)
        );
        User user = TestEntities.user(1L, 1_000);
        stubAvailablePrediction(game, user);

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), PredictionOutcome.AWAY_WIN, 100)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verifyNoInteractions(gameOddsService);
        assertThat(user.getPoint()).isEqualTo(1_000);
    }

    private Game futureGame() {
        return TestEntities.game(
                10L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate().plusDays(1),
                LocalTime.of(18, 30)
        );
    }

    private void stubAvailablePrediction(Game game, User user) {
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(userRepository.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(userPredictionRepository.existsByUserIdAndGameId(user.getId(), game.getId()))
                .thenReturn(false);
        lenient().when(userPredictionRepository.save(any(UserPrediction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().doAnswer(invocation -> {
            UserPrediction prediction = invocation.getArgument(1);
            user.changePoint(-prediction.getPointAmount());
            return null;
        }).when(pointService).useForPrediction(
                eq(user),
                any(UserPrediction.class)
        );
    }
}
