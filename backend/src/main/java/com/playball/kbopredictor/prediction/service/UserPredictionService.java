package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.dto.UserPredictionResponse;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPredictionService {

    static final int MIN_POINT_AMOUNT = 100;
    static final int POINT_UNIT = 100;

    private final UserPredictionRepository userPredictionRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GameOddsService gameOddsService;
    private final PointService pointService;
    private final UserPointLockService userPointLockService;
    private final Clock clock;

    @Transactional
    public UserPredictionResponse createPrediction(
            Long authenticatedUserId,
            UserPredictionRequest request
    ) {
        validatePointAmount(request.pointAmount());

        Game game = gameRepository.findByIdForUpdate(request.gameId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));

        User user = userPointLockService.findByIdForUpdate(authenticatedUserId);

        validateDuplicatePrediction(user.getId(), game.getId());
        validateGameStatus(game);
        validatePredictionDeadline(game);
        validatePointBalance(user, request.pointAmount());

        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                request.selectedOutcome(),
                request.pointAmount()
        );

        UserPrediction savedPrediction = savePrediction(prediction);

        gameOddsService.placeBet(
                game,
                request.selectedOutcome(),
                request.pointAmount()
        );
        pointService.useForPrediction(user, savedPrediction);

        return UserPredictionResponse.from(savedPrediction);
    }

    private UserPrediction savePrediction(UserPrediction prediction) {
        try {
            return userPredictionRepository.saveAndFlush(prediction);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 해당 경기에 예측했습니다.",
                    exception
            );
        }
    }

    private void validatePointAmount(Integer pointAmount) {
        if (pointAmount == null || pointAmount < MIN_POINT_AMOUNT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "사용 포인트는 최소 100P입니다."
            );
        }
        if (pointAmount % POINT_UNIT != 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "사용 포인트는 100P 단위여야 합니다."
            );
        }
    }

    private void validatePointBalance(User user, int pointAmount) {
        if (user.getPoint() == null || user.getPoint() < pointAmount) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "보유 포인트가 부족합니다."
            );
        }
    }

    private void validateDuplicatePrediction(
            Long userId,
            Long gameId
    ) {
        if (userPredictionRepository.existsByUserIdAndGameId(
                userId,
                gameId
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 해당 경기에 예측했습니다."
            );
        }
    }

    private void validateGameStatus(Game game) {
        if (game.getStatus() != GameStatus.SCHEDULED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "예정된 경기만 예측할 수 있습니다."
            );
        }
    }

    private void validatePredictionDeadline(Game game) {
        LocalDateTime closeAt = game.getPredictionCloseAt();

        if (closeAt != null &&
                !LocalDateTime.now(clock).isBefore(closeAt)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "승부예측 참여 시간이 마감되었습니다."
            );
        }
    }

    public List<UserPredictionResponse> getPredictionsByUserId(Long userId) {

        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "사용자를 찾을 수 없습니다."
            );
        }

        return userPredictionRepository
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(UserPredictionResponse::from)
                .toList();
    }
}
