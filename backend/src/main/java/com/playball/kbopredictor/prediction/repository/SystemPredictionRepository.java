package com.playball.kbopredictor.prediction.repository;

import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface SystemPredictionRepository
        extends JpaRepository<SystemPrediction, Long> {

    Optional<SystemPrediction> findByGameId(Long gameId);

    List<SystemPrediction> findByGameIdIn(Collection<Long> gameIds);

    long countByGameGameDate(LocalDate gameDate);
}
