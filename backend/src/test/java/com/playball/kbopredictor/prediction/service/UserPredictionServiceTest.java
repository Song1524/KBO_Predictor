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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.List;
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

    @ParameterizedTest
    @ValueSource(ints = {100, 300, 500})
    void acceptsSupportedPointAmounts(int pointAmount) {
        Game game = futureGame();
        User user = TestEntities.user(1L, 1_000);
        stubAvailablePrediction(game, user);

        UserPredictionResponse response = service.createPrediction(
                user.getId(),
                new UserPredictionRequest(
                        game.getId(),
                        PredictionOutcome.HOME_WIN,
                        pointAmount
                )
        );

        assertThat(response.pointAmount()).isEqualTo(pointAmount);
        assertThat(user.getPoint()).isEqualTo(1_000 - pointAmount);
        verify(gameOddsService).placeBet(
                game,
                PredictionOutcome.HOME_WIN,
                pointAmount
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, 0, 50, 150})
    void blocksInvalidPointAmounts(int pointAmount) {
        assertThatThrownBy(() -> service.createPrediction(
                1L,
                new UserPredictionRequest(
                        10L,
                        PredictionOutcome.HOME_WIN,
                        pointAmount
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verifyNoInteractions(gameRepository, userRepository, gameOddsService, pointService);
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
        User user = TestEntities.user(1L, 200);
        stubAvailablePrediction(game, user);

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), PredictionOutcome.HOME_WIN, 300)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verifyNoInteractions(gameOddsService, pointService);
        assertThat(user.getPoint()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(value = GameStatus.class, names = {
            "IN_PROGRESS",
            "FINISHED",
            "CANCELLED"
    })
    void blocksParticipationUnlessGameIsScheduled(GameStatus status) {
        Game game = TestEntities.game(
                10L,
                status,
                NOW.toLocalDate().plusDays(1),
                LocalTime.of(18, 30)
        );
        User user = TestEntities.user(1L, 1_000);
        stubAvailablePrediction(game, user);

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(game.getId(), PredictionOutcome.DRAW, 100)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verifyNoInteractions(gameOddsService, pointService);
        assertThat(user.getPoint()).isEqualTo(1_000);
    }

    @Test
    void repeatedRequestDeductsPointsOnlyOnce() {
        Game game = futureGame();
        User user = TestEntities.user(1L, 1_000);
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(userRepository.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(userPredictionRepository.existsByUserIdAndGameId(user.getId(), game.getId()))
                .thenReturn(false, true);
        when(userPredictionRepository.saveAndFlush(any(UserPrediction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            UserPrediction prediction = invocation.getArgument(1);
            user.changePoint(-prediction.getPointAmount());
            return null;
        }).when(pointService).useForPrediction(eq(user), any(UserPrediction.class));

        UserPredictionRequest request = new UserPredictionRequest(
                game.getId(),
                PredictionOutcome.AWAY_WIN,
                300
        );
        service.createPrediction(user.getId(), request);

        assertThatThrownBy(() -> service.createPrediction(user.getId(), request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
                );

        assertThat(user.getPoint()).isEqualTo(700);
        verify(pointService, times(1)).useForPrediction(
                eq(user),
                any(UserPrediction.class)
        );
        verify(gameOddsService, times(1)).placeBet(
                game,
                PredictionOutcome.AWAY_WIN,
                300
        );
    }

    @Test
    void uniqueConstraintRaceReturnsConflictBeforeOddsOrPointsChange() {
        Game game = futureGame();
        User user = TestEntities.user(1L, 1_000);
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(userRepository.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(userPredictionRepository.existsByUserIdAndGameId(user.getId(), game.getId()))
                .thenReturn(false);
        when(userPredictionRepository.saveAndFlush(any(UserPrediction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate prediction"));

        assertThatThrownBy(() -> service.createPrediction(
                user.getId(),
                new UserPredictionRequest(
                        game.getId(),
                        PredictionOutcome.HOME_WIN,
                        300
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );

        verifyNoInteractions(gameOddsService, pointService);
        assertThat(user.getPoint()).isEqualTo(1_000);
    }

    @Test
    void reloadRestoresSelectedOutcomeAndPointAmount() {
        Game game = futureGame();
        User user = TestEntities.user(1L, 700);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.DRAW,
                300
        );
        when(userRepository.existsById(user.getId())).thenReturn(true);
        when(userPredictionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(List.of(prediction));

        List<UserPredictionResponse> responses =
                service.getPredictionsByUserId(user.getId());

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.gameId()).isEqualTo(game.getId());
            assertThat(response.selectedOutcome()).isEqualTo(PredictionOutcome.DRAW);
            assertThat(response.pointAmount()).isEqualTo(300);
            assertThat(response.settlementStatus().name()).isEqualTo("PENDING");
        });
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
        lenient().when(userPredictionRepository.saveAndFlush(any(UserPrediction.class)))
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
