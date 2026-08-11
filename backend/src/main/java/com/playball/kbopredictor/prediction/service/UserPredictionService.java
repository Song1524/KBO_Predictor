package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.dto.UserPredictionResponse;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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

    private final UserPredictionRepository userPredictionRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GameOddsService gameOddsService;
    private final PointService pointService;
    private final Clock clock;

    @Transactional
    public UserPredictionResponse createPrediction(
            Long authenticatedUserId,
            UserPredictionRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(request.gameId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));

        User user = userRepository.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        validateDuplicatePrediction(user.getId(), game.getId());
        validateGameStatus(game);
        validatePredictionDeadline(game);

        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                request.selectedOutcome(),
                request.pointAmount()
        );

        UserPrediction savedPrediction =
                userPredictionRepository.save(prediction);

        gameOddsService.placeBet(
                game,
                request.selectedOutcome(),
                request.pointAmount()
        );
        pointService.useForPrediction(user, savedPrediction);

        return UserPredictionResponse.from(savedPrediction);
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
