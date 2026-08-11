package com.playball.kbopredictor.prediction.repository;

import com.playball.kbopredictor.prediction.entity.UserPrediction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPredictionRepository
        extends JpaRepository<UserPrediction, Long> {

    Optional<UserPrediction> findByUserIdAndGameId(
            Long userId,
            Long gameId
    );

    boolean existsByUserIdAndGameId(
            Long userId,
            Long gameId
    );

    @EntityGraph(attributePaths = {
            "user",
            "game",
            "game.homeTeam",
            "game.awayTeam"
    })
    List<UserPrediction> findByUserIdOrderByCreatedAtDesc(
            Long userId
    );

    List<UserPrediction> findByGameId(
            Long gameId
    );

    List<UserPrediction> findByGameIdAndSettledFalse(
            Long gameId
    );

    boolean existsByGameIdAndSettledFalse(Long gameId);

    boolean existsByGameIdAndSettledTrue(Long gameId);

    long countBySettledFalse();
}
